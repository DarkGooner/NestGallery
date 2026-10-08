package com.nestgallery.viewer.data.nsfw

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** One scanned photo ready to be persisted; [lastModified]/[size] are the values seen when it was decoded. */
class NsfwScannedFile(val path: String, val lastModified: Long, val size: Long, val result: NsfwResult)

/**
 * SQLite store of NSFW detections, separate from the face database (own file, own versioning).
 *
 * Every row carries the [NsfwModel.id] that produced it, so each model variant keeps its own results: switching the
 * model in Settings and back again never throws a finished scan away.
 * nsfw_files: one row per scanned photo and model (also undecodable ones, width = 0, so they are not retried).
 * nsfw_detections: every region with score >= [YoloDecoder.MIN_SCORE]; the counting threshold is applied when loading,
 * so changing it never needs a rescan.
 */
class NsfwDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS nsfw_files (
                model TEXT NOT NULL, path TEXT NOT NULL, last_modified INTEGER NOT NULL, size INTEGER NOT NULL,
                width INTEGER NOT NULL, height INTEGER NOT NULL, ms INTEGER NOT NULL, scanned_at INTEGER NOT NULL,
                PRIMARY KEY(model, path))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS nsfw_detections (
                model TEXT NOT NULL, path TEXT NOT NULL, label TEXT NOT NULL, score REAL NOT NULL,
                x INTEGER NOT NULL, y INTEGER NOT NULL, w INTEGER NOT NULL, h INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nsfw_det_model_path ON nsfw_detections(model, path)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 held NudeNet + EraX results mixed under one model id: not reusable, the next scan redoes them.
        for (t in listOf("nsfw_files", "nsfw_detections", "meta")) db.execSQL("DROP TABLE IF EXISTS $t")
        onCreate(db)
    }

    /** path -> (lastModified, size) for every file under [folderPath] scanned with [model]; one query for the folder. */
    fun loadIndexedFiles(model: String, folderPath: String): HashMap<String, Pair<Long, Long>> {
        val (eq, lo, hi) = range(folderPath)
        val out = HashMap<String, Pair<Long, Long>>()
        readableDatabase.rawQuery(
            "SELECT path, last_modified, size FROM nsfw_files WHERE model = ? AND (path = ? OR (path >= ? AND path < ?))",
            arrayOf(model, eq, lo, hi)
        ).use { while (it.moveToNext()) out[it.getString(0)] = Pair(it.getLong(1), it.getLong(2)) }
        return out
    }

    /** Persists a batch of photos in one transaction, replacing older results of the same model for those paths. */
    fun saveBatch(model: String, files: List<NsfwScannedFile>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val det = db.compileStatement("INSERT INTO nsfw_detections(model,path,label,score,x,y,w,h) VALUES(?,?,?,?,?,?,?,?)")
            for (f in files) {
                db.delete("nsfw_detections", "model = ? AND path = ?", arrayOf(model, f.path))
                db.insertWithOnConflict("nsfw_files", null, ContentValues().apply {
                    put("model", model); put("path", f.path); put("last_modified", f.lastModified); put("size", f.size)
                    put("width", f.result.width); put("height", f.result.height); put("ms", f.result.ms); put("scanned_at", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                for (d in f.result.detections) {
                    det.clearBindings()
                    det.bindString(1, model); det.bindString(2, f.path); det.bindString(3, d.label); det.bindDouble(4, d.score.toDouble())
                    det.bindLong(5, d.x.toLong()); det.bindLong(6, d.y.toLong()); det.bindLong(7, d.w.toLong()); det.bindLong(8, d.h.toLong())
                    det.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every stored result of [model] (two scans joined in memory: ~100k detection rows load in well under a second). */
    fun loadAll(model: String): HashMap<String, NsfwResult> {
        val dets = HashMap<String, ArrayList<NsfwDetection>>()
        val labels = HashMap<String, String>()          // intern: 18 distinct strings for 100k rows
        readableDatabase.rawQuery("SELECT path, label, score, x, y, w, h FROM nsfw_detections WHERE model = ?", arrayOf(model)).use {
            while (it.moveToNext()) {
                val label = it.getString(1).let { l -> labels.getOrPut(l) { l } }
                dets.getOrPut(it.getString(0)) { ArrayList(4) }
                    .add(NsfwDetection(label, it.getFloat(2), it.getInt(3), it.getInt(4), it.getInt(5), it.getInt(6)))
            }
        }
        val out = HashMap<String, NsfwResult>()
        readableDatabase.rawQuery("SELECT path, width, height, ms FROM nsfw_files WHERE model = ?", arrayOf(model)).use {
            while (it.moveToNext()) {
                val path = it.getString(0)
                out[path] = NsfwResult(it.getInt(1), it.getInt(2), dets[path] ?: emptyList(), it.getLong(3))
            }
        }
        return out
    }

    /** How many photos each model has results for (Settings shows it). */
    fun countByModel(): Map<String, Int> {
        val out = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT model, COUNT(*) FROM nsfw_files GROUP BY model", null).use {
            while (it.moveToNext()) out[it.getString(0)] = it.getInt(1)
        }
        return out
    }

    /** Forgets [model]'s results under [folderPath] (the next scan redoes them). */
    fun clearFolder(model: String, folderPath: String) {
        val (eq, lo, hi) = range(folderPath)
        val where = "model = ? AND (path = ? OR (path >= ? AND path < ?))"
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("nsfw_detections", where, arrayOf(model, eq, lo, hi))
            db.delete("nsfw_files", where, arrayOf(model, eq, lo, hi))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** [folderPath] itself, and the half-open range of paths strictly inside it ('0' sorts right after '/'). */
    private fun range(folderPath: String): Triple<String, String, String> {
        val root = folderPath.trimEnd(File.separatorChar, '/')
        return Triple(root, "$root/", "${root}0")
    }

    companion object {
        private const val DB_NAME = "nest_nsfw.db"
        private const val DB_VERSION = 2
        @Volatile private var INSTANCE: NsfwDatabase? = null
        fun getInstance(context: Context): NsfwDatabase =
            INSTANCE ?: synchronized(this) { INSTANCE ?: NsfwDatabase(context).also { INSTANCE = it } }
    }
}

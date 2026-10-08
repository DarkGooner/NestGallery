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
 * nsfw_files: one row per scanned photo (also undecodable ones, width = 0, so they are not retried every scan).
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
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS nsfw_files (
                path TEXT PRIMARY KEY, last_modified INTEGER NOT NULL, size INTEGER NOT NULL,
                width INTEGER NOT NULL, height INTEGER NOT NULL, ms INTEGER NOT NULL, scanned_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS nsfw_detections (
                path TEXT NOT NULL, label TEXT NOT NULL, score REAL NOT NULL,
                x INTEGER NOT NULL, y INTEGER NOT NULL, w INTEGER NOT NULL, h INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nsfw_det_path ON nsfw_detections(path)")
        db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('model', '${NsfwModels.MODEL_ID}')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        dropAll(db); onCreate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Results of other models (or model settings) are not comparable: start clean, the next scan redoes them.
        val stored = db.rawQuery("SELECT value FROM meta WHERE key='model'", null).use { if (it.moveToFirst()) it.getString(0) else null }
        if (stored != NsfwModels.MODEL_ID && !db.isReadOnly) { dropAll(db); onCreate(db) }
    }

    private fun dropAll(db: SQLiteDatabase) {
        for (t in listOf("nsfw_files", "nsfw_detections", "meta")) db.execSQL("DROP TABLE IF EXISTS $t")
    }

    /** path -> (lastModified, size) for every scanned file under [folderPath]; one query instead of one per photo. */
    fun loadIndexedFiles(folderPath: String): HashMap<String, Pair<Long, Long>> {
        val (eq, lo, hi) = range(folderPath)
        val out = HashMap<String, Pair<Long, Long>>()
        readableDatabase.rawQuery(
            "SELECT path, last_modified, size FROM nsfw_files WHERE path = ? OR (path >= ? AND path < ?)", arrayOf(eq, lo, hi)
        ).use { while (it.moveToNext()) out[it.getString(0)] = Pair(it.getLong(1), it.getLong(2)) }
        return out
    }

    /** Persists a batch of photos in one transaction, replacing any older results for the same paths. */
    fun saveBatch(files: List<NsfwScannedFile>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val det = db.compileStatement("INSERT INTO nsfw_detections(path,label,score,x,y,w,h) VALUES(?,?,?,?,?,?,?)")
            for (f in files) {
                db.delete("nsfw_detections", "path = ?", arrayOf(f.path))
                db.insertWithOnConflict("nsfw_files", null, ContentValues().apply {
                    put("path", f.path); put("last_modified", f.lastModified); put("size", f.size)
                    put("width", f.result.width); put("height", f.result.height); put("ms", f.result.ms); put("scanned_at", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                for (d in f.result.detections) {
                    det.clearBindings()
                    det.bindString(1, f.path); det.bindString(2, d.label); det.bindDouble(3, d.score.toDouble())
                    det.bindLong(4, d.x.toLong()); det.bindLong(5, d.y.toLong()); det.bindLong(6, d.w.toLong()); det.bindLong(7, d.h.toLong())
                    det.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every stored result (two sequential scans, joined in memory: ~100k detection rows load in well under a second). */
    fun loadAll(): HashMap<String, NsfwResult> {
        val dets = HashMap<String, ArrayList<NsfwDetection>>()
        val labels = HashMap<String, String>()          // intern: a few dozen distinct strings for 100k rows
        readableDatabase.rawQuery("SELECT path, label, score, x, y, w, h FROM nsfw_detections", null).use {
            while (it.moveToNext()) {
                val label = it.getString(1).let { l -> labels.getOrPut(l) { l } }
                dets.getOrPut(it.getString(0)) { ArrayList(4) }
                    .add(NsfwDetection(label, it.getFloat(2), it.getInt(3), it.getInt(4), it.getInt(5), it.getInt(6)))
            }
        }
        val out = HashMap<String, NsfwResult>()
        readableDatabase.rawQuery("SELECT path, width, height, ms FROM nsfw_files", null).use {
            while (it.moveToNext()) {
                val path = it.getString(0)
                out[path] = NsfwResult(it.getInt(1), it.getInt(2), dets[path] ?: emptyList(), it.getLong(3))
            }
        }
        return out
    }

    /** Forgets every result under [folderPath] (the next scan redoes them). */
    fun clearFolder(folderPath: String) {
        val (eq, lo, hi) = range(folderPath)
        val where = "path = ? OR (path >= ? AND path < ?)"
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("nsfw_detections", where, arrayOf(eq, lo, hi))
            db.delete("nsfw_files", where, arrayOf(eq, lo, hi))
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
        private const val DB_VERSION = 1
        @Volatile private var INSTANCE: NsfwDatabase? = null
        fun getInstance(context: Context): NsfwDatabase =
            INSTANCE ?: synchronized(this) { INSTANCE ?: NsfwDatabase(context).also { INSTANCE = it } }
    }
}

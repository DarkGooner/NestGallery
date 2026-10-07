package com.nestgallery.viewer.data.face

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.RectF
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PersonEntity(
    val id: Long,
    val name: String,
    val coverFaceId: Long,
    val faceCount: Int,
    val coverThumbnailPath: String?
)

data class FaceMatch(
    val filePath: String,
    /** Raw cosine similarity of the embeddings. Use [FaceMath.matchProbability] for a 0..1 display score. */
    val similarity: Float,
    val faceRect: RectF?,
    val thumbnailPath: String? = null
)

data class FaceDbStats(val totalPhotosIndexed: Int, val totalFacesFound: Int, val totalPeople: Int)

/** A face ready to be persisted. */
class NewFace(val bounds: RectF, val landmarksNorm: FloatArray, val embedding: FloatArray, val quality: Float)

/** Everything detected in one photo; [lastModified]/[size] are the values seen when it was decoded. */
class ScannedFile(val path: String, val lastModified: Long, val size: Long, val faces: List<NewFace>)

/** One face row as loaded into the in-memory index. */
class StoredFace(val id: Long, val path: String, val personId: Long, val quality: Float, val embedding: FloatArray)

class FaceLocation(val path: String, val landmarksNorm: FloatArray)

/**
 * SQLite persistence for indexed photos, face embeddings and people.
 *
 * person_id: > 0 person, 0 not yet assigned, -1 dismissed by the user (never re-clustered).
 * Embeddings are stored as int8 + scale (516 bytes instead of 2 KB); the in-memory index uses the same format.
 */
class FaceDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()          // commits no longer block readers; far cheaper fsyncs
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS indexed_files (
                path TEXT PRIMARY KEY, last_modified INTEGER NOT NULL, size INTEGER NOT NULL,
                face_count INTEGER NOT NULL, scanned_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS people (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, named INTEGER NOT NULL DEFAULT 0,
                cover_face_id INTEGER DEFAULT 0, face_count INTEGER DEFAULT 0, updated_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS faces (
                id INTEGER PRIMARY KEY AUTOINCREMENT, file_path TEXT NOT NULL,
                rect_left REAL NOT NULL, rect_top REAL NOT NULL, rect_right REAL NOT NULL, rect_bottom REAL NOT NULL,
                landmarks BLOB, quality REAL NOT NULL DEFAULT 0, person_id INTEGER DEFAULT 0,
                embedding BLOB NOT NULL, thumbnail_path TEXT, created_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_faces_file_path ON faces(file_path)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_faces_person_id ON faces(person_id)")
        db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('model', '${ArcFaceEmbedder.MODEL_ID}')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Embeddings from the old FaceNet model live in a different vector space - they cannot be reused.
        dropAll(db)
        onCreate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Safety net: a different embedding model than the one that wrote the data => start clean.
        val stored = db.rawQuery("SELECT value FROM meta WHERE key='model'", null).use { if (it.moveToFirst()) it.getString(0) else null }
        if (stored != ArcFaceEmbedder.MODEL_ID && !db.isReadOnly) { dropAll(db); onCreate(db) }
    }

    private fun dropAll(db: SQLiteDatabase) {
        for (t in listOf("faces", "people", "indexed_files", "meta")) db.execSQL("DROP TABLE IF EXISTS $t")
    }

    // ---- scanning ------------------------------------------------------------------------------

    /** path -> (lastModified, size) for every indexed file under [folderPath]; one query instead of one per photo. */
    fun loadIndexedFiles(folderPath: String): HashMap<String, Pair<Long, Long>> {
        val (eq, lo, hi) = range(folderPath)
        val out = HashMap<String, Pair<Long, Long>>()
        readableDatabase.rawQuery(
            "SELECT path, last_modified, size FROM indexed_files WHERE path = ? OR (path >= ? AND path < ?)", arrayOf(eq, lo, hi)
        ).use { while (it.moveToNext()) out[it.getString(0)] = Pair(it.getLong(1), it.getLong(2)) }
        return out
    }

    /**
     * Persists a whole batch of photos in ONE transaction (the old code committed - i.e. fsynced - per photo).
     * @return for each saved face, in order: its new row id, flattened across [files]
     */
    fun saveBatch(files: List<ScannedFile>): List<List<Long>> {
        val db = writableDatabase
        val ids = ArrayList<List<Long>>(files.size)
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val insertFace = db.compileStatement(
                "INSERT INTO faces(file_path,rect_left,rect_top,rect_right,rect_bottom,landmarks,quality,person_id,embedding,created_at) VALUES(?,?,?,?,?,?,?,0,?,?)"
            )
            for (f in files) {
                db.delete("faces", "file_path = ?", arrayOf(f.path))
                val faceIds = ArrayList<Long>(f.faces.size)
                for (face in f.faces) {
                    insertFace.clearBindings()
                    insertFace.bindString(1, f.path)
                    insertFace.bindDouble(2, face.bounds.left.toDouble()); insertFace.bindDouble(3, face.bounds.top.toDouble())
                    insertFace.bindDouble(4, face.bounds.right.toDouble()); insertFace.bindDouble(5, face.bounds.bottom.toDouble())
                    insertFace.bindBlob(6, floatsToBytes(face.landmarksNorm))
                    insertFace.bindDouble(7, face.quality.toDouble())
                    insertFace.bindBlob(8, encodeEmbedding(face.embedding))
                    insertFace.bindLong(9, now)
                    faceIds.add(insertFace.executeInsert())
                }
                ids.add(faceIds)
                val cv = ContentValues().apply {
                    put("path", f.path); put("last_modified", f.lastModified); put("size", f.size)
                    put("face_count", f.faces.size); put("scanned_at", now)
                }
                db.insertWithOnConflict("indexed_files", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            insertFace.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ids
    }

    /** Streams every stored face (used once to build the in-memory index). */
    fun forEachFace(block: (StoredFace) -> Unit) {
        readableDatabase.rawQuery("SELECT id, file_path, person_id, quality, embedding FROM faces", null).use {
            while (it.moveToNext()) block(StoredFace(it.getLong(0), it.getString(1), it.getLong(2), it.getFloat(3), decodeEmbedding(it.getBlob(4))))
        }
    }

    // ---- people / clustering --------------------------------------------------------------------

    fun namedPersonIds(): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery("SELECT id FROM people WHERE named = 1", null).use { while (it.moveToNext()) out.add(it.getLong(0)) }
        return out
    }

    fun getMeta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(key: String, value: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", arrayOf(key, value))
    }

    /** Un-groups every person the user has not named, so the next clustering run rebuilds them from scratch. */
    fun unassignUnnamedPeople() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE faces SET person_id = 0 WHERE person_id > 0 AND person_id NOT IN (SELECT id FROM people WHERE named = 1)")
            db.execSQL("DELETE FROM people WHERE named = 0")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun maxPersonId(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(id),0) FROM people", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Writes the result of a clustering run in one transaction. Names the user typed are never overwritten. */
    fun applyClustering(
        changedFaces: List<Pair<Long, Long>>,        // faceId -> personId
        faceCounts: Map<Long, Int>,
        coverFaceByPerson: Map<Long, Long>
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val upd = db.compileStatement("UPDATE faces SET person_id = ? WHERE id = ?")
            for ((faceId, personId) in changedFaces) { upd.bindLong(1, personId); upd.bindLong(2, faceId); upd.executeUpdateDelete() }
            upd.close()
            val insert = db.compileStatement("INSERT OR IGNORE INTO people(id,name,named,cover_face_id,face_count,updated_at) VALUES(?,?,0,0,0,?)")
            val update = db.compileStatement("UPDATE people SET cover_face_id = ?, face_count = ?, updated_at = ? WHERE id = ?")
            for ((personId, count) in faceCounts) {
                insert.bindLong(1, personId); insert.bindString(2, "Person $personId"); insert.bindLong(3, now); insert.executeInsert()
                update.bindLong(1, coverFaceByPerson[personId] ?: 0L); update.bindLong(2, count.toLong())
                update.bindLong(3, now); update.bindLong(4, personId); update.executeUpdateDelete()
            }
            insert.close(); update.close()
            db.execSQL("DELETE FROM people WHERE id NOT IN (SELECT DISTINCT person_id FROM faces WHERE person_id > 0)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getFaceLocation(faceId: Long): FaceLocation? =
        readableDatabase.rawQuery("SELECT file_path, landmarks FROM faces WHERE id = ?", arrayOf(faceId.toString())).use {
            if (it.moveToFirst() && !it.isNull(1)) FaceLocation(it.getString(0), bytesToFloats(it.getBlob(1))) else null
        }

    fun setThumbnail(faceId: Long, path: String) {
        writableDatabase.update("faces", ContentValues().apply { put("thumbnail_path", path) }, "id = ?", arrayOf(faceId.toString()))
    }

    fun hasThumbnail(faceId: Long): String? =
        readableDatabase.rawQuery("SELECT thumbnail_path FROM faces WHERE id = ?", arrayOf(faceId.toString())).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /** People with at least [MIN_FACES_TO_SHOW] faces inside the folder tree (one-off strangers stay hidden). */
    fun getPeopleInFolder(folderPath: String): List<PersonEntity> {
        val (eq, lo, hi) = range(folderPath)
        val sql = """
            SELECT p.id, p.name, p.cover_face_id, COUNT(f.id) AS n, fc.thumbnail_path
            FROM faces f JOIN people p ON p.id = f.person_id
            LEFT JOIN faces fc ON fc.id = p.cover_face_id
            WHERE f.person_id > 0 AND (f.file_path = ? OR (f.file_path >= ? AND f.file_path < ?))
            GROUP BY p.id HAVING n >= $MIN_FACES_TO_SHOW
            ORDER BY n DESC, p.id ASC"""
        val out = ArrayList<PersonEntity>()
        readableDatabase.rawQuery(sql, arrayOf(eq, lo, hi)).use {
            while (it.moveToNext()) out.add(PersonEntity(it.getLong(0), it.getString(1), it.getLong(2), it.getInt(3), it.getString(4)))
        }
        return out
    }

    fun getPersonById(personId: Long): PersonEntity? =
        readableDatabase.rawQuery(
            "SELECT p.id, p.name, p.cover_face_id, p.face_count, f.thumbnail_path FROM people p LEFT JOIN faces f ON f.id = p.cover_face_id WHERE p.id = ?",
            arrayOf(personId.toString())
        ).use { if (it.moveToFirst()) PersonEntity(it.getLong(0), it.getString(1), it.getLong(2), it.getInt(3), it.getString(4)) else null }

    fun getImagePathsForPerson(personId: Long, folderPath: String? = null): List<String> {
        val out = ArrayList<String>()
        if (folderPath != null) {
            val (eq, lo, hi) = range(folderPath)
            readableDatabase.rawQuery(
                "SELECT DISTINCT file_path FROM faces WHERE person_id = ? AND (file_path = ? OR (file_path >= ? AND file_path < ?)) ORDER BY file_path",
                arrayOf(personId.toString(), eq, lo, hi)
            ).use { while (it.moveToNext()) out.add(it.getString(0)) }
        } else {
            readableDatabase.rawQuery("SELECT DISTINCT file_path FROM faces WHERE person_id = ? ORDER BY file_path", arrayOf(personId.toString()))
                .use { while (it.moveToNext()) out.add(it.getString(0)) }
        }
        return out
    }

    fun renamePerson(personId: Long, newName: String) {
        writableDatabase.update(
            "people", ContentValues().apply { put("name", newName); put("named", 1); put("updated_at", System.currentTimeMillis()) },
            "id = ?", arrayOf(personId.toString())
        )
    }

    /** Removes the person and marks their faces "dismissed" (-1) so the next scan doesn't simply recreate them. */
    fun deletePerson(personId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("people", "id = ?", arrayOf(personId.toString()))
            db.update("faces", ContentValues().apply { put("person_id", -1L) }, "person_id = ?", arrayOf(personId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun getFolderStats(folderPath: String): FaceDbStats {
        val (eq, lo, hi) = range(folderPath)
        fun count(sql: String): Int = readableDatabase.rawQuery(sql, arrayOf(eq, lo, hi)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return FaceDbStats(
            count("SELECT COUNT(*) FROM indexed_files WHERE path = ? OR (path >= ? AND path < ?)"),
            count("SELECT COUNT(*) FROM faces WHERE file_path = ? OR (file_path >= ? AND file_path < ?)"),
            count("SELECT COUNT(DISTINCT person_id) FROM faces WHERE person_id > 0 AND (file_path = ? OR (file_path >= ? AND file_path < ?))")
        )
    }

    /**
     * Index-friendly "everything under this folder": path == folder OR folder/ <= path < folder0
     * ('0' is the character right after '/'). Unlike LIKE it is case-exact, can use the b-tree index, and
     * doesn't treat '_' or '%' in folder names as wildcards.
     */
    private fun range(folderPath: String): Triple<String, String, String> {
        val root = folderPath.trimEnd(File.separatorChar, '/')
        return Triple(root, "$root/", "${root}0")
    }

    companion object {
        private const val DB_NAME = "nest_faces.db"
        private const val DB_VERSION = 3
        const val MIN_FACES_TO_SHOW = 2

        @Volatile private var INSTANCE: FaceDatabase? = null
        fun getInstance(context: Context): FaceDatabase =
            INSTANCE ?: synchronized(this) { INSTANCE ?: FaceDatabase(context).also { INSTANCE = it } }

        /** int8 + scale: 4-byte little-endian float scale followed by [dim] signed bytes. */
        fun encodeEmbedding(e: FloatArray): ByteArray {
            val q = QVec.of(e)
            val buf = ByteBuffer.allocate(4 + q.q.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.putFloat(q.scale); buf.put(q.q)
            return buf.array()
        }

        fun decodeEmbedding(b: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val scale = buf.getFloat()
            return FloatArray(b.size - 4) { b[4 + it] * scale }
        }

        fun floatsToBytes(f: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN); for (x in f) buf.putFloat(x); return buf.array()
        }

        fun bytesToFloats(b: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(b.size / 4) { buf.getFloat() }
        }
    }
}

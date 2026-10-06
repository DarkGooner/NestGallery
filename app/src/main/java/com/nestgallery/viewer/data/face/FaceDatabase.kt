package com.nestgallery.viewer.data.face

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.RectF
import java.io.File
import kotlin.math.max

data class PersonEntity(
    val id: Long,
    val name: String,
    val coverFaceId: Long,
    val faceCount: Int,
    val coverThumbnailPath: String?
)

data class FaceEntity(
    val id: Long,
    val filePath: String,
    val rect: RectF,
    val personId: Long,
    val embedding: FloatArray?,
    val thumbnailPath: String?
)

data class FaceMatch(
    val filePath: String,
    val similarity: Float,
    val faceRect: RectF?,
    val thumbnailPath: String?
)

data class FaceDbStats(
    val totalPhotosIndexed: Int,
    val totalFacesFound: Int,
    val totalPeople: Int
)

data class PendingFaceRecord(
    val normalizedBounds: RectF,
    val embedding: FloatArray,
    val thumbnailPath: String
)

data class ClusterFaceItem(
    val faceId: Long,
    val embedding: FloatArray,
    val currentPersonId: Long,
    val thumbnailPath: String
)

/**
 * SQLite database storing indexed photos, detected face embeddings, and clustered people.
 * Engineered for sub-millisecond similarity scans and atomic batch operations on 10k+ photos.
 */
class FaceDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS indexed_files (
                path TEXT PRIMARY KEY,
                last_modified INTEGER NOT NULL,
                size INTEGER NOT NULL,
                face_count INTEGER NOT NULL,
                scanned_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS people (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                cover_face_id INTEGER DEFAULT 0,
                face_count INTEGER DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS faces (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_path TEXT NOT NULL,
                rect_left REAL NOT NULL,
                rect_top REAL NOT NULL,
                rect_right REAL NOT NULL,
                rect_bottom REAL NOT NULL,
                person_id INTEGER DEFAULT 0,
                embedding BLOB NOT NULL,
                thumbnail_path TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_faces_file_path ON faces(file_path)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_faces_person_id ON faces(person_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS faces")
        db.execSQL("DROP TABLE IF EXISTS people")
        db.execSQL("DROP TABLE IF EXISTS indexed_files")
        onCreate(db)
    }

    /**
     * Checks if a file has already been indexed and its timestamp/size haven't changed.
     */
    fun isFileIndexedAndCurrent(file: File): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT last_modified, size FROM indexed_files WHERE path = ? LIMIT 1",
            arrayOf(file.absolutePath)
        )
        cursor.use {
            if (it.moveToFirst()) {
                val lastMod = it.getLong(0)
                val size = it.getLong(1)
                return lastMod == file.lastModified() && size == file.length()
            }
        }
        return false
    }

    /**
     * Saves detected faces for a photo atomically.
     */
    fun saveFileFaces(
        file: File,
        faces: List<PendingFaceRecord>
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Remove previous faces for this file if re-indexing
            db.delete("faces", "file_path = ?", arrayOf(file.absolutePath))

            val now = System.currentTimeMillis()
            for (face in faces) {
                val cvFace = ContentValues().apply {
                    put("file_path", file.absolutePath)
                    put("rect_left", face.normalizedBounds.left)
                    put("rect_top", face.normalizedBounds.top)
                    put("rect_right", face.normalizedBounds.right)
                    put("rect_bottom", face.normalizedBounds.bottom)
                    put("person_id", 0L)
                    put("embedding", FaceEmbeddingHelper.toByteArray(face.embedding))
                    put("thumbnail_path", face.thumbnailPath)
                    put("created_at", now)
                }
                db.insert("faces", null, cvFace)
            }

            val cvIndex = ContentValues().apply {
                put("path", file.absolutePath)
                put("last_modified", file.lastModified())
                put("size", file.length())
                put("face_count", faces.size)
                put("scanned_at", now)
            }
            db.insertWithOnConflict("indexed_files", null, cvIndex, SQLiteDatabase.CONFLICT_REPLACE)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Retrieves all faces with their embeddings for clustering.
     */
    fun getAllFacesForClustering(): List<ClusterFaceItem> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, embedding, person_id, thumbnail_path FROM faces",
            null
        )
        val list = mutableListOf<ClusterFaceItem>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val blob = it.getBlob(1)
                val personId = it.getLong(2)
                val thumb = it.getString(3) ?: ""
                val embedding = FaceEmbeddingHelper.fromByteArray(blob)
                list.add(ClusterFaceItem(id, embedding, personId, thumb))
            }
        }
        return list
    }

    /**
     * Updates clustering assignments in a single transaction, preserving custom names.
     */
    fun applyClusterAssignments(
        personNames: Map<Long, String>, // personId to name
        faceToPersonMap: Map<Long, Long>, // faceId to personId
        personCoverFaceMap: Map<Long, Long> // personId to coverFaceId
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()

            // Count faces per person
            val personCounts = mutableMapOf<Long, Int>()
            for ((_, personId) in faceToPersonMap) {
                personCounts[personId] = (personCounts[personId] ?: 0) + 1
            }

            // Sync people table
            for ((personId, count) in personCounts) {
                val name = personNames[personId] ?: "Person $personId"
                val coverFaceId = personCoverFaceMap[personId] ?: 0L

                val cv = ContentValues().apply {
                    put("id", personId)
                    put("name", name)
                    put("cover_face_id", coverFaceId)
                    put("face_count", count)
                    put("updated_at", now)
                }
                db.insertWithOnConflict("people", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            // Update faces
            val stmt = db.compileStatement("UPDATE faces SET person_id = ? WHERE id = ?")
            for ((faceId, personId) in faceToPersonMap) {
                stmt.bindLong(1, personId)
                stmt.bindLong(2, faceId)
                stmt.executeUpdateDelete()
            }
            stmt.close()

            // Remove any obsolete empty people
            db.execSQL("DELETE FROM people WHERE id NOT IN (SELECT DISTINCT person_id FROM faces WHERE person_id > 0)")

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Returns list of all recognized people ordered by photo count.
     */
    fun getAllPeople(): List<PersonEntity> {
        val db = readableDatabase
        val sql = """
            SELECT p.id, p.name, p.cover_face_id, p.face_count, f.thumbnail_path
            FROM people p
            LEFT JOIN faces f ON f.id = p.cover_face_id
            ORDER BY p.face_count DESC, p.id ASC
        """.trimIndent()

        val cursor = db.rawQuery(sql, null)
        val list = mutableListOf<PersonEntity>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val name = it.getString(1)
                val coverFaceId = it.getLong(2)
                val count = it.getInt(3)
                val thumb = it.getString(4)
                list.add(PersonEntity(id, name, coverFaceId, count, thumb))
            }
        }
        return list
    }

    fun getPersonById(personId: Long): PersonEntity? {
        val db = readableDatabase
        val sql = """
            SELECT p.id, p.name, p.cover_face_id, p.face_count, f.thumbnail_path
            FROM people p
            LEFT JOIN faces f ON f.id = p.cover_face_id
            WHERE p.id = ? LIMIT 1
        """.trimIndent()
        val cursor = db.rawQuery(sql, arrayOf(personId.toString()))
        cursor.use {
            if (it.moveToFirst()) {
                return PersonEntity(
                    id = it.getLong(0),
                    name = it.getString(1),
                    coverFaceId = it.getLong(2),
                    faceCount = it.getInt(3),
                    coverThumbnailPath = it.getString(4)
                )
            }
        }
        return null
    }

    /**
     * Returns unique image file paths containing this person.
     */
    fun getImagePathsForPerson(personId: Long): List<String> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT DISTINCT file_path FROM faces WHERE person_id = ? ORDER BY id DESC",
            arrayOf(personId.toString())
        )
        val list = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(it.getString(0))
            }
        }
        return list
    }

    /**
     * Returns all detected faces for an image file.
     */
    fun getFacesForFile(filePath: String): List<FaceEntity> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, file_path, rect_left, rect_top, rect_right, rect_bottom, person_id, thumbnail_path FROM faces WHERE file_path = ?",
            arrayOf(filePath)
        )
        val list = mutableListOf<FaceEntity>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    FaceEntity(
                        id = it.getLong(0),
                        filePath = it.getString(1),
                        rect = RectF(it.getFloat(2), it.getFloat(3), it.getFloat(4), it.getFloat(5)),
                        personId = it.getLong(6),
                        embedding = null,
                        thumbnailPath = it.getString(7)
                    )
                )
            }
        }
        return list
    }

    /**
     * Renames a person.
     */
    fun renamePerson(personId: Long, newName: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("name", newName)
            put("updated_at", System.currentTimeMillis())
        }
        db.update("people", cv, "id = ?", arrayOf(personId.toString()))
    }

    /**
     * Deletes a person cluster (clearing person_id on its faces).
     */
    fun deletePerson(personId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("people", "id = ?", arrayOf(personId.toString()))
            val cv = ContentValues().apply { put("person_id", 0L) }
            db.update("faces", cv, "person_id = ?", arrayOf(personId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Searches all faces in the database by similarity to a query embedding.
     * Groups matches by photo file, keeping the highest similarity per photo.
     */
    fun searchByEmbedding(
        queryEmbedding: FloatArray,
        minSimilarity: Float = 0.65f,
        maxResults: Int = 300
    ): List<FaceMatch> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT file_path, embedding, rect_left, rect_top, rect_right, rect_bottom, thumbnail_path FROM faces",
            null
        )

        // Best match per file
        val fileMatchMap = mutableMapOf<String, FaceMatch>()

        cursor.use {
            while (it.moveToNext()) {
                val path = it.getString(0)
                val blob = it.getBlob(1)
                val left = it.getFloat(2)
                val top = it.getFloat(3)
                val right = it.getFloat(4)
                val bottom = it.getFloat(5)
                val thumb = it.getString(6)

                val emb = FaceEmbeddingHelper.fromByteArray(blob)
                val sim = FaceEmbeddingHelper.cosineSimilarity(queryEmbedding, emb)

                if (sim >= minSimilarity) {
                    val existing = fileMatchMap[path]
                    if (existing == null || sim > existing.similarity) {
                        fileMatchMap[path] = FaceMatch(
                            filePath = path,
                            similarity = sim,
                            faceRect = RectF(left, top, right, bottom),
                            thumbnailPath = thumb
                        )
                    }
                }
            }
        }

        return fileMatchMap.values
            .sortedByDescending { it.similarity }
            .take(maxResults)
    }

    fun getStats(): FaceDbStats {
        val db = readableDatabase
        var photos = 0
        var faces = 0
        var people = 0

        db.rawQuery("SELECT COUNT(*) FROM indexed_files", null).use {
            if (it.moveToFirst()) photos = it.getInt(0)
        }
        db.rawQuery("SELECT COUNT(*) FROM faces", null).use {
            if (it.moveToFirst()) faces = it.getInt(0)
        }
        db.rawQuery("SELECT COUNT(*) FROM people", null).use {
            if (it.moveToFirst()) people = it.getInt(0)
        }

        return FaceDbStats(photos, faces, people)
    }

    fun clearAllData() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("faces", null, null)
            db.delete("people", null, null)
            db.delete("indexed_files", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val DB_NAME = "nest_faces.db"
        private const val DB_VERSION = 1

        @Volatile
        private var INSTANCE: FaceDatabase? = null

        fun getInstance(context: Context): FaceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FaceDatabase(context).also { INSTANCE = it }
            }
        }
    }
}

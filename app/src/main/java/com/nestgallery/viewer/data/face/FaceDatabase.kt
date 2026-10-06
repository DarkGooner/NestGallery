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
    val thumbnailPath: String,
    val filePath: String
)

/**
 * SQLite database storing indexed photos, detected face embeddings, and clustered people.
 * Supports folder-scoped queries so face recognition runs within the explored recursive tree.
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

    fun saveFileFaces(
        file: File,
        faces: List<PendingFaceRecord>
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
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
     * Retrieves faces for clustering, optionally scoped to a folder tree.
     */
    fun getFacesForClustering(folderPath: String? = null): List<ClusterFaceItem> {
        val db = readableDatabase
        val sql: String
        val args: Array<String>?

        if (folderPath != null) {
            val normalized = folderPath.trimEnd(File.separatorChar, '/')
            sql = "SELECT id, embedding, person_id, thumbnail_path, file_path FROM faces WHERE file_path = ? OR file_path LIKE ?"
            args = arrayOf(normalized, "$normalized${File.separator}%")
        } else {
            sql = "SELECT id, embedding, person_id, thumbnail_path, file_path FROM faces"
            args = null
        }

        val cursor = db.rawQuery(sql, args)
        val list = mutableListOf<ClusterFaceItem>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val blob = it.getBlob(1)
                val personId = it.getLong(2)
                val thumb = it.getString(3) ?: ""
                val path = it.getString(4)
                if (blob != null && blob.isNotEmpty()) {
                    val embedding = FaceEmbeddingHelper.fromByteArray(blob)
                    list.add(ClusterFaceItem(id, embedding, personId, thumb, path))
                }
            }
        }
        return list
    }

    /**
     * Updates clustering assignments in a single transaction, preserving custom names.
     */
    fun applyClusterAssignments(
        personNames: Map<Long, String>,
        faceToPersonMap: Map<Long, Long>,
        personCoverFaceMap: Map<Long, Long>
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()

            val personCounts = mutableMapOf<Long, Int>()
            for ((_, personId) in faceToPersonMap) {
                personCounts[personId] = (personCounts[personId] ?: 0) + 1
            }

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

            val stmt = db.compileStatement("UPDATE faces SET person_id = ? WHERE id = ?")
            for ((faceId, personId) in faceToPersonMap) {
                stmt.bindLong(1, personId)
                stmt.bindLong(2, faceId)
                stmt.executeUpdateDelete()
            }
            stmt.close()

            db.execSQL("DELETE FROM people WHERE id NOT IN (SELECT DISTINCT person_id FROM faces WHERE person_id > 0)")

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

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

    /**
     * Returns recognized people present in a specific folder tree.
     */
    fun getPeopleInFolder(folderPath: String): List<PersonEntity> {
        val db = readableDatabase
        val normalized = folderPath.trimEnd(File.separatorChar, '/')
        val sql = """
            SELECT p.id, p.name, p.cover_face_id, COUNT(f.id) as folder_count, fcover.thumbnail_path
            FROM faces f
            JOIN people p ON p.id = f.person_id
            LEFT JOIN faces fcover ON fcover.id = p.cover_face_id
            WHERE f.person_id > 0 AND (f.file_path = ? OR f.file_path LIKE ?)
            GROUP BY p.id
            ORDER BY folder_count DESC, p.id ASC
        """.trimIndent()

        val cursor = db.rawQuery(sql, arrayOf(normalized, "$normalized${File.separator}%"))
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
     * Returns unique image file paths containing this person, optionally scoped to a folder.
     */
    fun getImagePathsForPerson(personId: Long, folderPath: String? = null): List<String> {
        val db = readableDatabase
        val sql: String
        val args: Array<String>

        if (folderPath != null) {
            val normalized = folderPath.trimEnd(File.separatorChar, '/')
            sql = "SELECT DISTINCT file_path FROM faces WHERE person_id = ? AND (file_path = ? OR file_path LIKE ?) ORDER BY id DESC"
            args = arrayOf(personId.toString(), normalized, "$normalized${File.separator}%")
        } else {
            sql = "SELECT DISTINCT file_path FROM faces WHERE person_id = ? ORDER BY id DESC"
            args = arrayOf(personId.toString())
        }

        val cursor = db.rawQuery(sql, args)
        val list = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(it.getString(0))
            }
        }
        return list
    }

    fun renamePerson(personId: Long, newName: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("name", newName)
            put("updated_at", System.currentTimeMillis())
        }
        db.update("people", cv, "id = ?", arrayOf(personId.toString()))
    }

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
     * Reverse face search strictly scoped to a folder tree.
     */
    fun searchByEmbeddingInFolder(
        queryEmbedding: FloatArray,
        folderPath: String,
        minSimilarity: Float = 0.58f,
        maxResults: Int = 300
    ): List<FaceMatch> {
        val db = readableDatabase
        val normalized = folderPath.trimEnd(File.separatorChar, '/')
        val sql = "SELECT file_path, embedding, rect_left, rect_top, rect_right, rect_bottom, thumbnail_path FROM faces WHERE file_path = ? OR file_path LIKE ?"
        val cursor = db.rawQuery(sql, arrayOf(normalized, "$normalized${File.separator}%"))

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

                if (blob != null && blob.isNotEmpty()) {
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
        }

        return fileMatchMap.values
            .sortedByDescending { it.similarity }
            .take(maxResults)
    }

    fun getFolderStats(folderPath: String): FaceDbStats {
        val db = readableDatabase
        val normalized = folderPath.trimEnd(File.separatorChar, '/')
        var photos = 0
        var faces = 0
        var people = 0

        db.rawQuery(
            "SELECT COUNT(*) FROM indexed_files WHERE path = ? OR path LIKE ?",
            arrayOf(normalized, "$normalized${File.separator}%")
        ).use {
            if (it.moveToFirst()) photos = it.getInt(0)
        }

        db.rawQuery(
            "SELECT COUNT(*) FROM faces WHERE file_path = ? OR file_path LIKE ?",
            arrayOf(normalized, "$normalized${File.separator}%")
        ).use {
            if (it.moveToFirst()) faces = it.getInt(0)
        }

        db.rawQuery(
            "SELECT COUNT(DISTINCT person_id) FROM faces WHERE person_id > 0 AND (file_path = ? OR file_path LIKE ?)",
            arrayOf(normalized, "$normalized${File.separator}%")
        ).use {
            if (it.moveToFirst()) people = it.getInt(0)
        }

        return FaceDbStats(photos, faces, people)
    }

    companion object {
        private const val DB_NAME = "nest_faces.db"
        private const val DB_VERSION = 2 // Updated for FaceNet-512 embeddings

        @Volatile
        private var INSTANCE: FaceDatabase? = null

        fun getInstance(context: Context): FaceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FaceDatabase(context).also { INSTANCE = it }
            }
        }
    }
}

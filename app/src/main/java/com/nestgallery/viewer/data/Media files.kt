package com.nestgallery.viewer.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/** A mounted storage volume, shown as a row on the explorer's root screen. */
data class StorageRoot(val file: File, val label: String)

val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "3gp", "m4v", "avi")

fun File.extension(): String = name.substringAfterLast('.', "").lowercase()
fun File.isImageFile(): Boolean = extension() in imageExtensions
fun File.isVideoFile(): Boolean = extension() in videoExtensions
fun File.isMediaFile(): Boolean = isImageFile() || isVideoFile()

/**
 * Human-friendly folder name. The primary shared storage is reported by the
 * OS as a path ending in "0", which reads terribly in titles and breadcrumbs.
 */
fun File.displayName(): String =
    when {
        absolutePath.startsWith("/storage/emulated") -> "Internal storage"
        parent == "/storage" -> "SD card ($name)"
        else -> name
    }

/**
 * Every storage volume the explorer can start from: the primary shared
 * storage plus any mounted SD cards / USB volumes, mirroring the root screen
 * of file managers like ZArchiver. Purely a system-service lookup - no disk
 * listing - so it is fast enough to skip caching.
 */
fun storageRoots(context: Context): List<StorageRoot> {
    val storageManager =
        context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
    val volumes = storageManager?.storageVolumes.orEmpty()

    val dirs = LinkedHashSet<File>()
    volumes.forEach { volume ->
        volumeDir(volume)?.takeIf { it.exists() }?.let { dirs.add(it) }
    }
    try {
        Environment.getExternalStorageDirectory()
            ?.takeIf { it.exists() }
            ?.let { dirs.add(it) }
    } catch (_: Exception) {
    }

    return dirs.map { dir ->
        val volume = volumes.firstOrNull { volumeDir(it) == dir }
        StorageRoot(dir, volume?.getDescription(context) ?: dir.displayName())
    }
}

private fun volumeDir(volume: StorageVolume): File? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        volume.directory
    } else {
        // StorageVolume.getDirectory() is API 30+; on 26-29 read the path field.
        try {
            val field = StorageVolume::class.java.getDeclaredField("mPath")
            field.isAccessible = true
            field.get(volume) as? File
        } catch (_: Exception) {
            null
        }
    }

/**
 * Lists [dir]'s children, folders first then files, both alphabetically.
 * Dot-hidden entries are skipped so system clutter like .thumbnails and
 * .nomedia never shows. When [hideAux] is true, files whose name contains
 * "_thumb" or "_locked" are skipped too.
 */
fun listFolder(dir: File, hideAux: Boolean): List<File> {
    val children = dir.listFiles() ?: return emptyList()
    return children
        .asSequence()
        .filter { !it.name.startsWith(".") }
        .filter { it.isDirectory || !hideAux || !isAuxName(it.name) }
        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        .toList()
}

private fun isAuxName(name: String): Boolean {
    val n = name.lowercase()
    return n.contains("_thumb") || n.contains("_locked")
}

/** Number of browsable children directly inside [dir] (single fast listing). */
fun countChildren(dir: File, hideAux: Boolean): Int = listFolder(dir, hideAux).size

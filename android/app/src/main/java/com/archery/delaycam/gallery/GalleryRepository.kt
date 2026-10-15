package com.archery.delaycam.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lists/deletes saved clips from Movies/Archery via MediaStore (spec 3.4 / 6). */
class GalleryRepository(private val context: Context) {

    suspend fun listClips(): List<ClipEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<ClipEntry>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("Movies/Archery/%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                entries.add(
                    ClipEntry(
                        uri = uri,
                        displayName = cursor.getString(nameCol) ?: "archery_clip.mp4",
                        addedAtMs = cursor.getLong(dateCol) * 1000L,
                        durationMs = cursor.getLong(durationCol),
                    )
                )
            }
        } ?: throw java.io.IOException("Cannot read gallery")
        entries
    }

    suspend fun clipExists(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        // MediaStore denies direct item queries once the item has been deleted.
        // Query its collection by ID so absence is represented by an empty cursor.
        val id = ContentUris.parseId(uri)
        val parentPath = requireNotNull(uri.path).substringBeforeLast('/')
        val collection = uri.buildUpon().path(parentPath).build()
        context.contentResolver.query(collection, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media._ID} = ?", arrayOf(id.toString()), null)?.use { it.moveToFirst() }
            ?: throw java.io.IOException("Cannot verify the video")
    }

    suspend fun deleteClip(uri: Uri) = withContext(Dispatchers.IO) {
        val deleted = context.contentResolver.delete(uri, null, null)
        if (deleted == 0 && clipExists(uri)) throw java.io.IOException("MediaStore did not delete the video")
        ClipPresentationStore(context).remove(uri)
        Unit
    }
}


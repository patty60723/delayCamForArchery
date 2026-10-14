package com.archery.delaycam.gallery

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class PictureRange(val endMs: Long, val aspect: Float?)

/** Private presentation metadata; videos remain standard MP4 files in MediaStore. */
class ClipPresentationStore(context: Context) {
    private val directory = File(context.filesDir, "clip-presentation")
    private fun file(uri: Uri): File {
        val key = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }
    fun write(uri: Uri, ranges: List<PictureRange>) {
        check(directory.exists() || directory.mkdirs())
        val array = JSONArray()
        ranges.forEach { range -> array.put(JSONObject().put("endMs", range.endMs)
            .put("aspect", range.aspect ?: JSONObject.NULL)) }
        val atomic = AtomicFile(file(uri))
        val stream = atomic.startWrite()
        try { stream.write(array.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }
    fun read(uri: Uri): List<PictureRange> = try {
        val array = JSONArray(file(uri).readText())
        (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            PictureRange(obj.getLong("endMs"), if (obj.isNull("aspect")) null else obj.getDouble("aspect").toFloat())
        }
    } catch (_: Exception) { emptyList() }
    fun remove(uri: Uri) { file(uri).delete() }
    companion object {
        fun aspectAt(ranges: List<PictureRange>, positionMs: Long): Float? =
            (ranges.firstOrNull { positionMs < it.endMs } ?: ranges.lastOrNull())?.aspect
    }
}

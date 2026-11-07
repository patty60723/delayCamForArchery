package com.archery.delaycam.camera

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.archery.delaycam.ui.playback.CONTENT_ASPECT_KEY

fun SegmentInfo.playbackItem(mediaId: String = file.absolutePath): MediaItem = MediaItem.Builder()
    .setUri(file.toUri()).setMediaId(mediaId)
    .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
        contentAspectRatio?.let { putFloat(CONTENT_ASPECT_KEY, it) }
    }).build()).build()

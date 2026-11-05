package com.hazbu.xcam.utils

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer

@OptIn(UnstableApi::class)
object MediaConverter {
    // MediaConverter.kt
    fun convertImageToMp4(
        context: Context,
        imageUri: Uri,
        outputPath: String,
        durationMs: Long = 3000L,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        Handler(Looper.getMainLooper()).post {
//            val mediaItem = MediaItem.fromUri(imageUri)
//            val editedItem = EditedMediaItem.Builder(mediaItem)
//                .setDurationUs(durationMs * 1000)   // required for image input
//                .setFrameRate(30)
//                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(imageUri)
                .setImageDurationMs(durationMs)   // this is what image inputs need
                .build()

            val editedItem = EditedMediaItem.Builder(mediaItem)
                .setFrameRate(30)
                .build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        onSuccess(outputPath)
                    }
                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        onError(exception)
                    }
                })
                .build()

            transformer.start(editedItem, outputPath)
        }
    }
}

package com.hazbu.xcam.core.capture

import android.graphics.BitmapFactory
import android.media.Image
import androidx.core.graphics.scale

/** Direct YUV copier/scaler. Bitmap/JPEG work remains only for legacy still-image boundaries. */
class YuvFrameProcessor {
    private var cachedSource: ByteArray? = null
    private var cachedYuv: YuvData? = null

    private class YuvData(val width: Int, val height: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray)

    fun frameToNv21(
        frame: MediaCodecYuvDecoder.YuvFrame,
        targetWidth: Int,
        targetHeight: Int,
        rotationAngle: Int = 0,
        mirrorHorizontal: Boolean = false,
        mirrorVertical: Boolean = false,
    ): ByteArray {
        val ySize = targetWidth * targetHeight
        val result = ByteArray(ySize + ((targetWidth + 1) / 2) * ((targetHeight + 1) / 2) * 2)
        writeTransformedBytes(
            result, 0, targetWidth, targetHeight, targetWidth,
            frame.yPlane, frame.width, frame.height, rotationAngle, mirrorHorizontal, mirrorVertical,
        )
        val targetChromaW = (targetWidth + 1) / 2
        val targetChromaH = (targetHeight + 1) / 2
        val sourceChromaW = (frame.width + 1) / 2
        val sourceChromaH = (frame.height + 1) / 2
        var destination = ySize
        for (y in 0 until targetChromaH) {
            for (x in 0 until targetChromaW) {
                result[destination++] = sample(frame.vPlane, sourceChromaW, sourceChromaH, targetChromaW, targetChromaH, x, y, rotationAngle, mirrorHorizontal, mirrorVertical)
                result[destination++] = sample(frame.uPlane, sourceChromaW, sourceChromaH, targetChromaW, targetChromaH, x, y, rotationAngle, mirrorHorizontal, mirrorVertical)
            }
        }
        return result
    }

    /**
     * Called once per ImageReader acquisition (potentially several times per decoded frame,
     * once per active stream resolution). Three paths, cheapest first:
     *  1. identity (no rotation/mirror, same dimensions) -> bulk row copy.
     *  2. rotation == 0 (the default/common case; only scaling and/or mirroring) -> the
     *     per-column source index only depends on x, not y, so it is computed once per plane
     *     instead of recomputed (with a division and modulo) for every one of the ~10M pixels
     *     across the Y/U/V planes of a high-resolution reader.
     *  3. an active rotation (90/180/270) -> falls back to the original per-pixel sample(),
     *     since the source index then depends jointly on x and y and this is a rare,
     *     user-triggered case rather than the steady-state hot path.
     */
    fun injectYuvFrame(
        image: Image,
        frame: MediaCodecYuvDecoder.YuvFrame,
        rotationAngle: Int = 0,
        mirrorHorizontal: Boolean = false,
        mirrorVertical: Boolean = false,
    ) {
        val planes = image.planes
        if (planes.size < 3 || image.width <= 0 || image.height <= 0) return
        val rotation = ((rotationAngle % 360) + 360) % 360
        val identity = rotation == 0 && !mirrorHorizontal && !mirrorVertical

        writeTransformedPlane(planes[0], frame.yPlane, frame.width, frame.height, image.width, image.height, rotation, mirrorHorizontal, mirrorVertical, identity)
        val dstChromaW = (image.width + 1) / 2
        val dstChromaH = (image.height + 1) / 2
        val sourceChromaW = (frame.width + 1) / 2
        val sourceChromaH = (frame.height + 1) / 2
        writeTransformedPlane(planes[1], frame.uPlane, sourceChromaW, sourceChromaH, dstChromaW, dstChromaH, rotation, mirrorHorizontal, mirrorVertical, identity)
        writeTransformedPlane(planes[2], frame.vPlane, sourceChromaW, sourceChromaH, dstChromaW, dstChromaH, rotation, mirrorHorizontal, mirrorVertical, identity)
    }

    fun rgbToNv21(bitmap: android.graphics.Bitmap, width: Int, height: Int): ByteArray {
        val scaled = if (bitmap.width == width && bitmap.height == height) bitmap else android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = ByteArray(width * height + ((width + 1) / 2) * ((height + 1) / 2) * 2)
        for (y in 0 until height) for (x in 0 until width) {
            val color = pixels[y * width + x]
            output[y * width + x] = ((77 * ((color shr 16) and 0xff) + 150 * ((color shr 8) and 0xff) + 29 * (color and 0xff)) shr 8).toByte()
        }
        var index = width * height
        for (y in 0 until (height + 1) / 2) for (x in 0 until (width + 1) / 2) {
            val color = pixels[(y * 2).coerceAtMost(height - 1) * width + (x * 2).coerceAtMost(width - 1)]
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            output[index++] = (((128 * r - 107 * g - 21 * b) shr 8) + 128).coerceIn(0, 255).toByte()
            output[index++] = (((-43 * r - 85 * g + 128 * b) shr 8) + 128).coerceIn(0, 255).toByte()
        }
        if (scaled !== bitmap) scaled.recycle()
        return output
    }

    /** Legacy image-only path; cached to avoid repeated Bitmap/JPEG conversion of an unchanged still. */
    fun injectToImage(image: Image, jpeg: ByteArray) {
        val width = image.width
        val height = image.height
        val yuv = if (cachedSource === jpeg && cachedYuv?.width == width && cachedYuv?.height == height) cachedYuv!! else process(jpeg, width, height).also {
            cachedSource = jpeg
            cachedYuv = it
        }
        val planes = image.planes
        if (planes.size < 3) return
        writePlane(planes[0], yuv.y, width, height)
        writePlane(planes[1], yuv.u, (width + 1) / 2, (height + 1) / 2)
        writePlane(planes[2], yuv.v, (width + 1) / 2, (height + 1) / 2)
    }

    private fun writeTransformedPlane(
        plane: Image.Plane,
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        destinationWidth: Int,
        destinationHeight: Int,
        rotationAngle: Int,
        mirrorHorizontal: Boolean,
        mirrorVertical: Boolean,
        identity: Boolean,
    ) {
        val buffer = plane.buffer
        val start = buffer.position()
        val limit = buffer.limit()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        try {
            if (identity && sourceWidth == destinationWidth && sourceHeight == destinationHeight) {
                if (pixelStride == 1) {
                    for (y in 0 until destinationHeight) {
                        val rowStart = start + y * rowStride
                        if (rowStart >= limit) break
                        val count = (limit - rowStart).coerceAtMost(destinationWidth)
                        if (count <= 0) continue
                        buffer.position(rowStart)
                        buffer.put(source, y * sourceWidth, count)
                    }
                } else {
                    for (y in 0 until destinationHeight) {
                        val rowStart = start + y * rowStride
                        val srcRow = y * sourceWidth
                        for (x in 0 until destinationWidth) {
                            val destination = rowStart + x * pixelStride
                            if (destination < limit) buffer.put(destination, source[srcRow + x])
                        }
                    }
                }
                return
            }

            if (rotationAngle == 0) {
                // Column source-index only depends on x: compute once per plane, not per pixel.
                val colIndex = IntArray(destinationWidth) { x ->
                    val sourceX = (x * sourceWidth / destinationWidth).coerceIn(0, sourceWidth - 1)
                    if (mirrorHorizontal) sourceWidth - 1 - sourceX else sourceX
                }
                for (y in 0 until destinationHeight) {
                    var sourceY = (y * sourceHeight / destinationHeight).coerceIn(0, sourceHeight - 1)
                    if (mirrorVertical) sourceY = sourceHeight - 1 - sourceY
                    val srcRow = sourceY * sourceWidth
                    val rowStart = start + y * rowStride
                    if (rowStart >= limit) break
                    for (x in 0 until destinationWidth) {
                        val destination = rowStart + x * pixelStride
                        if (destination < limit) buffer.put(destination, source[srcRow + colIndex[x]])
                    }
                }
                return
            }

            // Active rotation: rare (user-triggered), keep the general per-pixel path.
            for (y in 0 until destinationHeight) for (x in 0 until destinationWidth) {
                val destination = start + y * rowStride + x * pixelStride
                if (destination < limit) {
                    buffer.put(destination, sample(source, sourceWidth, sourceHeight, destinationWidth, destinationHeight, x, y, rotationAngle, mirrorHorizontal, mirrorVertical))
                }
            }
        } finally {
            // Bulk put()/relative get() above move the buffer's position; restore it so the
            // plane's buffer is left exactly as callers of the original absolute-put
            // implementation would have found it (position unchanged at `start`).
            buffer.position(start)
        }
    }

    private fun writeTransformedBytes(
        destination: ByteArray,
        offset: Int,
        destinationWidth: Int,
        destinationHeight: Int,
        rowStride: Int,
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationAngle: Int,
        mirrorHorizontal: Boolean,
        mirrorVertical: Boolean,
    ) {
        for (y in 0 until destinationHeight) for (x in 0 until destinationWidth) {
            destination[offset + y * rowStride + x] = sample(source, sourceWidth, sourceHeight, destinationWidth, destinationHeight, x, y, rotationAngle, mirrorHorizontal, mirrorVertical)
        }
    }

    private fun sample(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        destinationWidth: Int,
        destinationHeight: Int,
        x: Int,
        y: Int,
        rawRotation: Int,
        mirrorHorizontal: Boolean,
        mirrorVertical: Boolean,
    ): Byte {
        val rotation = ((rawRotation % 360) + 360) % 360
        val rotatedWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
        val rotatedHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
        var rotatedX = (x * rotatedWidth / destinationWidth).coerceIn(0, rotatedWidth - 1)
        var rotatedY = (y * rotatedHeight / destinationHeight).coerceIn(0, rotatedHeight - 1)
        if (mirrorHorizontal) rotatedX = rotatedWidth - 1 - rotatedX
        if (mirrorVertical) rotatedY = rotatedHeight - 1 - rotatedY
        val sourceX: Int
        val sourceY: Int
        when (rotation) {
            90 -> {
                sourceX = rotatedY
                sourceY = sourceHeight - 1 - rotatedX
            }
            180 -> {
                sourceX = sourceWidth - 1 - rotatedX
                sourceY = sourceHeight - 1 - rotatedY
            }
            270 -> {
                sourceX = sourceWidth - 1 - rotatedY
                sourceY = rotatedX
            }
            else -> {
                sourceX = rotatedX
                sourceY = rotatedY
            }
        }
        return source.getOrElse(sourceY * sourceWidth + sourceX) { 0 }
    }

    private fun process(jpeg: ByteArray, width: Int, height: Int): YuvData {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: throw IllegalStateException("Failed to decode still frame")
        val bitmap = if (decoded.width == width && decoded.height == height) decoded else decoded.scale(width, height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val y = ByteArray(width * height)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val u = ByteArray(chromaWidth * chromaHeight)
        val v = ByteArray(chromaWidth * chromaHeight)
        for (row in 0 until height) for (column in 0 until width) {
            val color = pixels[row * width + column]
            y[row * width + column] = ((77 * ((color shr 16) and 0xff) + 150 * ((color shr 8) and 0xff) + 29 * (color and 0xff)) shr 8).toByte()
        }
        for (row in 0 until chromaHeight) for (column in 0 until chromaWidth) {
            val color = pixels[(row * 2).coerceAtMost(height - 1) * width + (column * 2).coerceAtMost(width - 1)]
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            val index = row * chromaWidth + column
            u[index] = (((-43 * r - 85 * g + 128 * b) shr 8) + 128).coerceIn(0, 255).toByte()
            v[index] = (((128 * r - 107 * g - 21 * b) shr 8) + 128).coerceIn(0, 255).toByte()
        }
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        return YuvData(width, height, y, u, v)
    }

    private fun writePlane(plane: Image.Plane, values: ByteArray, width: Int, height: Int) {
        val buffer = plane.buffer
        val start = buffer.position()
        for (row in 0 until height) for (column in 0 until width) {
            val destination = start + row * plane.rowStride + column * plane.pixelStride
            val source = row * width + column
            if (destination < buffer.limit() && source < values.size) buffer.put(destination, values[source])
        }
    }
}
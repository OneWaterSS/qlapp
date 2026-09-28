package com.example.qlapp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object ImageCompress {

    /** 把用户选中的图片压缩成 JPEG 缓存文件，长边不超过 maxDim */
    fun toCacheFile(
        context: Context,
        uri: Uri,
        maxDim: Int = 1600,
        quality: Int = 86,
    ): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxDim) sample *= 2

        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("无法解码这张图片")

        val rotated = applyExifRotation(context, uri, decoded)

        val out = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { fos ->
            rotated.compress(Bitmap.CompressFormat.JPEG, quality, fos)
        }
        if (rotated !== decoded) rotated.recycle()
        decoded.recycle()
        return out
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val stream = context.contentResolver.openInputStream(uri) ?: return bitmap
        val orientation = stream.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

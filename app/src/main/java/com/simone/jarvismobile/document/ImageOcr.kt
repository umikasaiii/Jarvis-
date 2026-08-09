package com.simone.jarvismobile.document

import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Opt-in, on-device OCR for imported images (ML Kit, bundled Latin model). It is
 * never run automatically — only when the user turns it on — because recognition
 * is slower than a text import. Everything stays on the device; nothing is sent
 * to a network service.
 */
object ImageOcr {
    private const val TAG = "JarvisOcr"

    /** Recognised text of [bytes], or an empty string on any failure. */
    fun recognize(bytes: ByteArray): String = runCatching {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        // Blocking await is fine: the caller is already on a background dispatcher.
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        result.text.trim()
    }.getOrElse {
        Log.w(TAG, "ocr_failed ${it.javaClass.simpleName}")
        ""
    }
}

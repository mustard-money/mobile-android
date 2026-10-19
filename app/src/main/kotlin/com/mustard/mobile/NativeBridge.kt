package com.mustard.mobile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** A failed bridge call, as the page sees it: `error.code` and `error.message`. */
class BridgeError(val code: String, message: String) : Exception(message)

/**
 * The native features offered to the PWA. The page reaches them through `window.mustardNative`
 * (assets/bridge.js), which only exists on the PWA's own origin. Same API as the iOS app
 * (../mobile-ios), plus a few Android-only options and `saveFile`.
 *
 * Has to be created before the activity is started: it registers activity result handlers.
 */
class NativeBridge(
    private val activity: ComponentActivity,
    private val onColors: (top: String, bottom: String) -> Unit,
) {
    private val documentScannerAvailable =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity) == ConnectionResult.SUCCESS

    /** The scan, or null if the person cancelled. */
    private var scanCallback: ((Result<GmsDocumentScanningResult?>) -> Unit)? = null
    private val scanLauncher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val callback = scanCallback ?: return@registerForActivityResult
        scanCallback = null
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        callback(
            when {
                result.resultCode == Activity.RESULT_OK && scan != null -> Result.success(scan)
                result.resultCode == Activity.RESULT_CANCELED -> Result.success(null)
                else -> Result.failure(BridgeError("failed", "The document scanner didn't return a result."))
            },
        )
    }

    private var saveCallback: ((Uri?) -> Unit)? = null
    private val saveLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = saveCallback ?: return@registerForActivityResult
        saveCallback = null
        callback(result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK })
    }

    /** Offers the bridge to pages from [origin] in [webView]. Returns false if this WebView is too old. */
    fun attach(webView: WebView, origin: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        WebViewCompat.addWebMessageListener(webView, "mustardNativeChannel", setOf(origin)) { _, message, _, isMainFrame, reply ->
            if (isMainFrame) handle(message, reply)
        }
        return true
    }

    /** assets/bridge.js, configured for this device. */
    fun script(): String {
        val config = JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("documentScanner", documentScannerAvailable)
        return activity.assets.open("bridge.js").bufferedReader().use { it.readText() }
            .replace("__CONFIG__", config.toString())
    }

    private fun handle(message: WebMessageCompat, reply: JavaScriptReplyProxy) {
        val request = runCatching { JSONObject(message.data ?: return) }.getOrNull() ?: return
        val method = request.optString("method")
        val args = request.optJSONObject("args") ?: JSONObject()
        // Notifications from the page, no answer expected.
        if (!request.has("id")) {
            if (method == "colors") onColors(args.optString("top"), args.optString("bottom"))
            return
        }
        val id = request.get("id")
        val respond = { result: Result<Any?> ->
            val response = JSONObject().put("id", id)
            result.fold(
                onSuccess = { response.put("result", it ?: JSONObject.NULL) },
                onFailure = {
                    if (it !is BridgeError) Log.e(TAG, "$method failed", it)
                    val code = (it as? BridgeError)?.code ?: "failed"
                    response.put("error", JSONObject().put("code", code).put("message", it.message ?: code))
                },
            )
            reply.postMessage(response.toString())
        }
        when (method) {
            "scanDocument" -> scanDocument(args, respond)
            "saveFile" -> saveFile(args, respond)
            else -> respond(Result.failure(BridgeError("unknown-action", "Unknown method: $method")))
        }
    }

    // MARK: Document scanning

    private fun scanDocument(args: JSONObject, respond: (Result<Any?>) -> Unit) {
        if (!documentScannerAvailable) {
            return respond(Result.failure(BridgeError("unsupported", "Document scanning needs Google Play services.")))
        }
        if (scanCallback != null) return respond(Result.failure(BridgeError("busy", "A scan is already open.")))

        val encoding = ScanEncoding(
            includePages = args.optBoolean("pages", true),
            includePdf = args.optBoolean("pdf", false),
            maxDimension = args.optInt("maxDimension", 2000).coerceIn(200, 6000),
            quality = (args.optDouble("quality", 0.8).takeUnless { it.isNaN() } ?: 0.8).coerceIn(0.1, 1.0),
        )
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(args.optBoolean("galleryImport", true))
            .setScannerMode(
                when (args.optString("mode")) {
                    "base" -> GmsDocumentScannerOptions.SCANNER_MODE_BASE
                    "filter" -> GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER
                    else -> GmsDocumentScannerOptions.SCANNER_MODE_FULL
                },
            )
            .apply {
                if (encoding.includePdf) {
                    setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                } else {
                    setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                }
                val pageLimit = args.optInt("pageLimit", 0)
                if (pageLimit > 0) setPageLimit(pageLimit)
            }
            .build()

        scanCallback = { result ->
            result.fold(
                onSuccess = { scan ->
                    if (scan == null) {
                        respond(Result.success(null)) // → null in JavaScript, as on iOS
                    } else {
                        // Reading and encoding the images can take a moment; keep it off the UI thread.
                        activity.lifecycleScope.launch {
                            respond(runCatching { withContext(Dispatchers.Default) { encode(scan, encoding) } })
                        }
                    }
                },
                onFailure = { respond(Result.failure(it)) },
            )
        }
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { error ->
                scanCallback = null
                // The scanner is a Play services module; the first use may still be downloading it.
                val code = if ((error as? MlKitException)?.errorCode == MlKitException.UNAVAILABLE) "unavailable" else "failed"
                respond(Result.failure(BridgeError(code, error.message ?: "The document scanner couldn't start.")))
            }
    }

    private class ScanEncoding(val includePages: Boolean, val includePdf: Boolean, val maxDimension: Int, val quality: Double)

    /** The result the page gets, in the iOS app's shape: base64 JPEG pages and/or one PDF. */
    private fun encode(scan: GmsDocumentScanningResult, encoding: ScanEncoding): JSONObject {
        val pages = scan.pages.orEmpty()
        val result = JSONObject().put("title", JSONObject.NULL).put("pageCount", pages.size)
        if (encoding.includePages) {
            result.put("pages", JSONArray(pages.map { encodePage(it.imageUri, encoding) }))
        }
        if (encoding.includePdf) {
            val pdf = scan.pdf ?: throw BridgeError("encoding-failed", "The scanner didn't make a PDF.")
            result.put("pdf", JSONObject().put("mimeType", "application/pdf").put("base64", base64(read(pdf.uri))))
        }
        return result
    }

    /** One page as a JPEG no larger than `maxDimension` on its longest side. */
    private fun encodePage(uri: Uri, encoding: ScanEncoding): JSONObject {
        val bytes = read(uri)
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw BridgeError("encoding-failed", "Couldn't decode a scanned page.")
        val scale = minOf(1.0, encoding.maxDimension.toDouble() / maxOf(original.width, original.height))
        val page = if (scale < 1) {
            Bitmap.createScaledBitmap(original, (original.width * scale).roundToInt(), (original.height * scale).roundToInt(), true)
        } else {
            original
        }
        val jpeg = ByteArrayOutputStream().use {
            page.compress(Bitmap.CompressFormat.JPEG, (encoding.quality * 100).roundToInt(), it)
            it.toByteArray()
        }
        return JSONObject()
            .put("mimeType", "image/jpeg")
            .put("base64", base64(jpeg))
            .put("width", page.width)
            .put("height", page.height)
    }

    private fun read(uri: Uri): ByteArray =
        activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw BridgeError("encoding-failed", "Couldn't read the scanned file.")

    private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    // MARK: Saving files

    private fun saveFile(args: JSONObject, respond: (Result<Any?>) -> Unit) {
        if (saveCallback != null) return respond(Result.failure(BridgeError("busy", "A save is already in progress.")))
        val bytes = runCatching { Base64.decode(args.optString("base64"), Base64.DEFAULT) }.getOrNull()
            ?: return respond(Result.failure(BridgeError("failed", "The file's contents weren't valid base64.")))
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(args.optString("mimeType").ifEmpty { "application/octet-stream" })
            .putExtra(Intent.EXTRA_TITLE, args.optString("name").ifEmpty { "download" })
        saveCallback = { uri ->
            if (uri == null) {
                respond(Result.success(false))
            } else {
                activity.lifecycleScope.launch {
                    respond(
                        runCatching {
                            withContext(Dispatchers.IO) {
                                activity.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                                    ?: throw BridgeError("failed", "Couldn't write the file.")
                            }
                            true
                        },
                    )
                }
            }
        }
        try {
            saveLauncher.launch(intent)
        } catch (error: ActivityNotFoundException) {
            saveCallback = null
            respond(Result.failure(BridgeError("unavailable", "There's no app to save files with.")))
        }
    }

    private companion object {
        const val TAG = "NativeBridge"
    }
}

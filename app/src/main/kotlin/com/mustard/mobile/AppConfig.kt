package com.mustard.mobile

import android.content.Intent
import android.net.Uri

/** Where the PWA lives, and which URLs belong to it. */
class AppConfig(val startUrl: Uri) {
    /** "https://host[:port]": the only origin the native bridge is offered to. */
    val origin: String = buildString {
        append(startUrl.scheme).append("://").append(startUrl.host)
        if (startUrl.port != -1) append(':').append(startUrl.port)
    }

    /** The folder the start URL is in ("/website/"), like `"scope": "./"` in the PWA's manifest. */
    private val scopePath: String = startUrl.path.orEmpty().let { it.substring(0, it.lastIndexOf('/') + 1).ifEmpty { "/" } }

    /** Whether a URL is part of the PWA. Anything else opens outside the web view. */
    fun isInScope(url: Uri): Boolean {
        if (url.scheme != startUrl.scheme || url.host != startUrl.host || url.port != startUrl.port) return false
        val path = url.path.orEmpty()
        return path.startsWith(scopePath) || "$path/" == scopePath
    }

    companion object {
        /**
         * The URL from gradle.properties (`pwaUrl`). Debug builds take another one from the
         * launch intent, e.g. the website's dev server: `./run.sh --url http://localhost:5173/`.
         */
        fun from(intent: Intent?): AppConfig {
            val override = intent?.getStringExtra("url")?.let(Uri::parse)
            if (BuildConfig.DEBUG && override?.host != null && override.scheme in setOf("http", "https")) {
                return AppConfig(override)
            }
            return AppConfig(Uri.parse(BuildConfig.PWA_URL))
        }
    }
}

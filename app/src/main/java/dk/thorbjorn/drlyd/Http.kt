package dk.thorbjorn.drlyd

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Lille HTTP-hjælper der også følger redirects mellem http og https. */
object Http {
    private const val USER_AGENT = "DRLydAutomotive/0.1"

    fun get(url: String, timeoutMs: Int = 15_000): ByteArray {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val location = c.getHeaderField("Location") ?: throw IOException("Omdirigering uden adresse")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code != 200) throw IOException("HTTP $code")
                return c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("For mange omdirigeringer")
    }

    fun text(url: String): String = String(get(url), Charsets.UTF_8)
}

package dk.thorbjorn.drlyd

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Bilens medieafspiller kan ikke selv hente billeder fra nettet,
 * så den beder os om dem her. Vi henter og gemmer covers og tegner kanal-logoer.
 */
class ArtProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "dk.thorbjorn.drlyd.art"
        private const val SIZE = 512

        fun imageUri(url: String?): Uri? = url?.takeIf { it.startsWith("http") }?.let {
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("img").appendQueryParameter("u", it).build()
        }

        fun channelUri(channel: Channel): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("ch").appendPath(channel.id)
                .appendQueryParameter("v", (channel.color + channel.short).hashCode().toString())
                .build()
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("Ingen kontekst")
        val dir = File(ctx.cacheDir, "art").apply { mkdirs() }
        val file = try {
            when (uri.pathSegments.firstOrNull()) {
                "img" -> {
                    val url = uri.getQueryParameter("u") ?: throw FileNotFoundException("Mangler adresse")
                    val f = File(dir, "img_" + sha1(url) + ".png")
                    if (!f.exists()) saveScaled(Http.get(url), f)
                    f
                }
                "ch" -> {
                    val id = uri.pathSegments.getOrNull(1) ?: throw FileNotFoundException("Mangler kanal")
                    val ch = ConfigRepo.get(ctx).channel(id) ?: throw FileNotFoundException("Ukendt kanal")
                    val f = File(dir, "ch_${id}_${(ch.color + ch.short).hashCode()}.png")
                    if (!f.exists()) drawChannel(ch, f)
                    f
                }
                else -> throw FileNotFoundException("Ukendt billede")
            }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw FileNotFoundException(e.message)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Skalerer store covers ned, så bilens skærm ikke skal håndtere 3000px-billeder. */
    private fun saveScaled(bytes: ByteArray, out: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw FileNotFoundException("Kunne ikke læse billedet")
        writePng(bmp, out)
    }

    private fun drawChannel(ch: Channel, out: File) {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(runCatching { Color.parseColor(ch.color) }.getOrDefault(Color.DKGRAY))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val lines = ch.short.split("\n")
        val cx = SIZE / 2f
        if (lines.size == 1) {
            paint.textSize = 200f
            canvas.drawText(lines[0], cx, SIZE / 2f + 70f, paint)
        } else {
            paint.textSize = 180f
            canvas.drawText(lines[0], cx, SIZE / 2f + 10f, paint)
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 64f
            while (paint.measureText(lines[1]) > SIZE - 48 && paint.textSize > 30f) paint.textSize -= 4f
            canvas.drawText(lines[1], cx, SIZE / 2f + 110f, paint)
        }
        writePng(bmp, out)
    }

    private fun writePng(bmp: Bitmap, out: File) {
        val tmp = File(out.parentFile, out.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(out)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

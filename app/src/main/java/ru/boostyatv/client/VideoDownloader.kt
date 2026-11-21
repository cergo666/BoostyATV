package ru.boostyatv.client

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log

/** Resolves direct MP4 streams and hands downloads off to Android's DownloadManager. */
internal object VideoDownloader {

    internal data class Option(
        val label: String,
        val qualityTag: String,
        val url: String,
        val height: Int,
    )

    fun availableOptions(sources: List<Post.Source>): List<Option> {
        val byHeight = LinkedHashMap<Int, Option>()
        for (source in sources) {
            val type = source.type()
            val url = source.url()
            if (url.isBlank() || type.contains("hls") || type.contains("dash")) continue
            val option = toOption(type, url) ?: continue
            byHeight.putIfAbsent(option.height, option)
        }
        return byHeight.values.sortedByDescending { it.height }
    }

    fun sanitizeFileName(rawTitle: String, qualityTag: String): String {
        val cleaned = rawTitle
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .trimEnd('.', ' ')
        val base = cleaned.ifEmpty { "boosty_video" }
        val suffix = qualityTag.ifEmpty { "mp4" }
        return "${base}_${suffix}.mp4"
    }

    fun enqueue(context: Context, title: String, option: Option): Boolean {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return false
        val fileName = sanitizeFileName(title, option.qualityTag)
        val request = DownloadManager.Request(Uri.parse(option.url))
            .setTitle(title.ifBlank { fileName })
            .setDescription(option.label)
            .setMimeType("video/mp4")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("Origin", BoostyApi.ORIGIN)
            .addRequestHeader("Referer", BoostyApi.ORIGIN + "/")
            .addRequestHeader("User-Agent", "BoostyATV/" + BuildConfig.VERSION_NAME)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "Boosty ATV/$fileName",
            )
        } else {
            request.setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName,
            )
        }

        return try {
            manager.enqueue(request) > 0L
        } catch (e: Exception) {
            Log.w("BoostyATV", "Video download enqueue failed", e)
            false
        }
    }

    private fun toOption(type: String, url: String): Option? = when (type) {
        "ultra_hd" -> Option(label = "2160p (4K)", qualityTag = "2160p", url = url, height = 2160)
        "quad_hd" -> Option(label = "1440p (2K)", qualityTag = "1440p", url = url, height = 1440)
        "full_hd" -> Option(label = "1080p (Full HD)", qualityTag = "1080p", url = url, height = 1080)
        "high" -> Option(label = "720p (HD)", qualityTag = "720p", url = url, height = 720)
        "medium" -> Option(label = "480p", qualityTag = "480p", url = url, height = 480)
        "low" -> Option(label = "360p", qualityTag = "360p", url = url, height = 360)
        "lowest" -> Option(label = "240p", qualityTag = "240p", url = url, height = 240)
        "tiny" -> Option(label = "144p", qualityTag = "144p", url = url, height = 144)
        "mp4" -> Option(label = "MP4", qualityTag = "mp4", url = url, height = 1)
        else -> null
    }
}

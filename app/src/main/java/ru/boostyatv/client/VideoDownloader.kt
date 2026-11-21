package ru.boostyatv.client

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import java.util.UUID
import org.json.JSONArray

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

    data class Reference(
        val slug: String,
        val postId: String,
        val videoId: String,
        val blockIndex: Int,
        val qualityTag: String,
    ) {
        fun resolve(post: Post): Option? {
            val block = if (videoId.isNotEmpty()) {
                post.blocks().firstOrNull { it.videoId() == videoId }
            } else {
                post.blocks().getOrNull(blockIndex)
            } ?: return null
            return availableOptions(block.sources()).firstOrNull { it.qualityTag == qualityTag }
        }
    }

    data class Entry(
        val id: Long,
        val title: String,
        val quality: String,
        val status: Int,
        val reason: Int,
        val bytes: Long,
        val total: Long,
        val originalUrl: String,
        val reference: Reference?,
    )

    fun enqueue(context: Context, title: String, option: Option, post: Post, block: Post.Block): Boolean =
        enqueue(context, title, option, Reference(post.slug(), post.id(), block.videoId(), post.blocks().indexOf(block), option.qualityTag))

    private fun enqueue(context: Context, title: String, option: Option, reference: Reference?): Boolean {
        val manager = context.getSystemService(DownloadManager::class.java) ?: return false
        val fileName = sanitizeFileName(title, option.qualityTag).removeSuffix(".mp4") +
            "_${UUID.randomUUID().toString().take(8)}.mp4"
        return try {
            val request = createRequest(context, title, option, fileName)
            val id = manager.enqueue(request)
            if (id <= 0L) return false
            if (reference != null) {
                val fields = JSONArray(listOf(reference.slug, reference.postId, reference.videoId, reference.blockIndex, reference.qualityTag))
                context.getSharedPreferences("video_downloads", Context.MODE_PRIVATE)
                    .edit { putString(id.toString(), fields.toString()) }
            }
            true
        } catch (e: Exception) {
            Log.w("BoostyATV", "Video download enqueue failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun createRequest(context: Context, title: String, option: Option, fileName: String): DownloadManager.Request {
        val request = DownloadManager.Request(option.url.toUri())
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

        return request
    }

    fun list(context: Context): List<Entry> {
        val manager = context.getSystemService(DownloadManager::class.java) ?: return emptyList()
        val preferences = context.getSharedPreferences("video_downloads", Context.MODE_PRIVATE)
        return manager.query(DownloadManager.Query())?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val qualityIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)
            val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val reasonIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val bytesIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val urlIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val reference = preferences.getString(id.toString(), null)?.let { raw ->
                        runCatching {
                            val fields = JSONArray(raw)
                            Reference(fields.getString(0), fields.getString(1), fields.getString(2), fields.getInt(3), fields.getString(4))
                        }.getOrNull()
                    }
                    add(
                        Entry(
                            id = id,
                            title = cursor.getString(titleIndex).orEmpty(),
                            quality = cursor.getString(qualityIndex).orEmpty(),
                            status = cursor.getInt(statusIndex),
                            reason = cursor.getInt(reasonIndex),
                            bytes = cursor.getLong(bytesIndex),
                            total = cursor.getLong(totalIndex),
                            originalUrl = cursor.getString(urlIndex).orEmpty(),
                            reference = reference,
                        ),
                    )
                }
            }
        }?.sortedByDescending { it.id } ?: emptyList()
    }

    fun retry(context: Context, entry: Entry, loadPost: (String, String) -> Post): Boolean {
        val manager = context.getSystemService(DownloadManager::class.java) ?: return false
        val stillFailed = manager.query(
            DownloadManager.Query().setFilterById(entry.id).setFilterByStatus(DownloadManager.STATUS_FAILED),
        )?.use { it.moveToFirst() } == true
        if (!stillFailed) return false
        val reference = entry.reference
        val option = if (reference != null) {
            reference.resolve(loadPost(reference.slug, reference.postId)) ?: return false
        } else {
            val uri = entry.originalUrl.toUri()
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty()) return false
            val qualityTag = entry.quality.substringBefore(' ').takeIf { it.matches(Regex("[0-9]+p")) }.orEmpty()
            Option(entry.quality, qualityTag, entry.originalUrl, 0)
        }
        if (!enqueue(context, entry.title, option, reference)) return false
        if (runCatching { manager.remove(entry.id) }.isSuccess) {
            context.getSharedPreferences("video_downloads", Context.MODE_PRIVATE)
                .edit { remove(entry.id.toString()) }
        }
        return true
    }

    fun open(context: Context, id: Long): Boolean = runCatching {
        val manager = context.getSystemService(DownloadManager::class.java) ?: return false
        val uri = manager.getUriForDownloadedFile(id) ?: return false
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        true
    }.getOrDefault(false)

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

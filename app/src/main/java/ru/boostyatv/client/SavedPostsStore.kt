package ru.boostyatv.client

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Local "Watch later" bookmarks stored in SharedPreferences. */
internal class SavedPostsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("saved_posts", Context.MODE_PRIVATE)

    data class Item(
        val slug: String,
        val id: String,
        val title: String,
        val author: String,
        val cover: String,
        val videoId: String,
        val durationMs: Long,
    )

    fun isSaved(slug: String, id: String): Boolean {
        if (slug.isEmpty() || id.isEmpty()) return false
        return list().any { it.slug == slug && it.id == id }
    }

    fun toggle(post: Post): Boolean {
        val slug = post.slug()
        val id = post.id()
        if (slug.isEmpty() || id.isEmpty()) return false
        val items = list().toMutableList()
        val removed = items.removeAll { it.slug == slug && it.id == id }
        if (!removed) {
            items.add(
                0,
                Item(
                    slug = slug,
                    id = id,
                    title = post.title(),
                    author = post.author(),
                    cover = post.cover(),
                    videoId = post.primaryVideoId(),
                    durationMs = post.primaryDurationMs(),
                ),
            )
            if (items.size > MAX_SAVED) {
                items.subList(MAX_SAVED, items.size).clear()
            }
        }
        save(items)
        return !removed
    }

    fun remove(slug: String, id: String) {
        val items = list().toMutableList()
        if (items.removeAll { it.slug == slug && it.id == id }) {
            save(items)
        }
    }

    fun list(): List<Item> {
        val raw = prefs.getString(KEY_ITEMS, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<Item>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val slug = obj.optString("slug")
                val id = obj.optString("id")
                if (slug.isEmpty() || id.isEmpty()) continue
                result.add(
                    Item(
                        slug = slug,
                        id = id,
                        title = obj.optString("title"),
                        author = obj.optString("author"),
                        cover = obj.optString("cover"),
                        videoId = obj.optString("videoId"),
                        durationMs = obj.optLong("durationMs", 0L),
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(items: List<Item>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("slug", item.slug)
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("author", item.author)
                    .put("cover", item.cover)
                    .put("videoId", item.videoId)
                    .put("durationMs", item.durationMs)
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    companion object {
        private const val KEY_ITEMS = "items"
        private const val MAX_SAVED = 100
    }
}

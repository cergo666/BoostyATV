package ru.boostyatv.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

record LiveStream(String slug, String title, String author, String cover, boolean allowed,
                  boolean online, boolean ended, long publishTime, List<Post.Source> sources) {
    static LiveStream parse(JSONObject stream, String fallbackSlug, boolean includeSources) {
        if (stream == null || stream.length() == 0) return null;
        JSONObject user = stream.optJSONObject("user");
        String slug = Post.string(user, "blogUrl");
        if (slug.isEmpty()) slug = fallbackSlug;
        if (slug.isEmpty()) return null;
        boolean online = stream.optBoolean("isOnline");
        boolean ended = stream.optBoolean("isEnded");
        boolean allowed = stream.optBoolean("hasAccess");
        JSONArray data = stream.optJSONArray("data");
        JSONObject video = data == null ? null : data.optJSONObject(0);
        String title = Post.string(stream, "title");
        if (title.isBlank()) title = Post.string(video, "title");
        String cover = Post.https(Post.string(video, "preview"));
        if (cover.isEmpty()) cover = Post.https(Post.string(video, "defaultPreview"));
        JSONArray teaser = stream.optJSONArray("teaser");
        if (cover.isEmpty() && teaser != null) {
            for (int i = 0; i < teaser.length(); i++) {
                JSONObject image = teaser.optJSONObject(i);
                if (!"image".equals(Post.string(image, "type"))) continue;
                cover = Post.https(Post.string(image, "url"));
                if (!cover.isEmpty()) break;
            }
        }
        String author = Post.string(user, "name");
        if (author.isBlank()) author = slug;
        JSONArray playerUrls = video == null ? null : video.optJSONArray("playerUrls");
        List<Post.Source> sources = new ArrayList<>();
        if (includeSources && allowed && online && !ended) {
            for (Post.Source source : Post.sources(playerUrls)) {
                if (source.type().contains("hls") || source.type().contains("dash")) sources.add(source);
            }
        }
        return new LiveStream(slug, title, author, cover, allowed, online, ended,
            stream.optLong("publishTime"), Collections.unmodifiableList(sources));
    }

    boolean visible(long now) {
        return !ended && (online || publishTime > now);
    }
}

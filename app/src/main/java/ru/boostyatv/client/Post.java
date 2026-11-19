package ru.boostyatv.client;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

record Post(String id, String title, String author, String slug, String authorAvatar, long published, boolean allowed,
            String cover, List<Block> blocks, boolean commentsDenied, boolean reactionsDisabled, Reactions reactions,
            List<BoostyApi.Tag> tags, long views) {
    Post(String id, String title, String author, String slug, String authorAvatar, long published, boolean allowed,
         String cover, List<Block> blocks, boolean commentsDenied, boolean reactionsDisabled, Reactions reactions) {
        this(id, title, author, slug, authorAvatar, published, allowed, cover, blocks, commentsDenied,
            reactionsDisabled, reactions, Collections.emptyList(), -1);
    }
    static final String UNTITLED = "";
    record Block(String kind, String text, String image, List<Source> sources,
                 String videoId, long positionMs, long durationMs) {
        Block(String kind, String text, String image, List<Source> sources) {
            this(kind, text, image, sources, "", 0, 0);
        }
    }
    record Source(String type, String url) {}

    boolean hasVideo() {
        return blocks.stream().anyMatch(block -> !block.sources().isEmpty());
    }

    String displayTitle(Context context) {
        return title.isEmpty() ? context.getString(R.string.post_untitled) : title;
    }

    String displayMetadata(Context context) {
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        List<String> parts = new ArrayList<>(2);
        if (published > 0) {
            parts.add(DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(new Date(published * 1000L)));
        }
        if (views >= 0) {
            parts.add(context.getString(R.string.post_view_count, NumberFormat.getIntegerInstance(locale).format(views)));
        }
        return String.join("  ·  ", parts);
    }

    static List<Post> parseList(JSONArray items) {
        return parseList(items, Map.of());
    }

    static List<Post> parseList(JSONArray items, Map<String, String> avatars) {
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && !string(item, "id").trim().isEmpty()) posts.add(parse(item, avatars));
        }
        return posts;
    }

    static Post parse(JSONObject item, Map<String, String> avatars) {
        boolean allowed = item.optBoolean("hasAccess", true);
        JSONArray media = item.optJSONArray(allowed ? "data" : "teaser");
        List<Block> blocks = blocks(media, allowed);
        List<Block> teaser = blocks(item.optJSONArray("teaser"), false);
        String cover = "";
        for (Block block : blocks) {
            if (!block.image().trim().isEmpty()) {
                cover = block.image();
                if (block.kind().equals("video")) break;
            }
        }
        if (cover.trim().isEmpty()) {
            cover = teaser.stream().map(Block::image).filter(image -> !image.trim().isEmpty()).findFirst().orElse("");
        }

        JSONObject author = item.optJSONObject("user");
        String title = string(item, "title").trim();
        if (title.trim().isEmpty()) {
            title = blocks.stream().filter(block -> block.kind().equals("text")).map(Block::text)
                .filter(value -> !value.trim().isEmpty()).findFirst().orElse(UNTITLED);
            if (title.codePointCount(0, title.length()) > 95) {
                title = title.substring(0, title.offsetByCodePoints(0, 92)).trim() + "…";
            }
        }
        JSONObject blog = item.optJSONObject("blog");
        String slug = string(author, "blogUrl");
        if (slug.isEmpty()) slug = string(blog, "blogUrl");
        String avatar = avatar(author);
        if (avatar.isEmpty() && blog != null) avatar = avatar(blog.optJSONObject("owner"));
        if (avatar.isEmpty()) avatar = avatars.getOrDefault(slug, "");
        return new Post(string(item, "id"), title, string(author, "name"), slug, avatar,
            item.optLong("publishTime", item.optLong("createdAt")), allowed, cover, blocks,
            item.optBoolean("isCommentsDenied"), item.optBoolean("reactionsDisabled"), Reactions.parse(item),
            BoostyApi.parseTags(item.optJSONArray("tags")), viewCount(item, media, allowed));
    }

    private static long viewCount(JSONObject item, JSONArray media, boolean allowed) {
        if (allowed && media != null) {
            for (int i = 0; i < media.length(); i++) {
                JSONObject block = media.optJSONObject(i);
                String type = string(block, "type");
                if (!type.equals("ok_video") && !type.equals("video")) continue;
                if (block.optBoolean("showViewsCounter")) {
                    long views = block.optLong("viewsCounter", -1);
                    if (views >= 0) return views;
                }
                break;
            }
        }
        if (!item.optBoolean("showViewsCounter", true)) return -1;
        JSONObject count = item.optJSONObject("count");
        long views = count == null ? -1 : count.optLong("views", -1);
        return views >= 0 ? views : item.optLong("viewsCounter", -1);
    }

    static String avatar(JSONObject owner) {
        return https(string(owner, "avatarUrl").trim());
    }

    static List<Block> blocks(JSONArray data, boolean allowed) {
        List<Block> blocks = new ArrayList<>();
        if (data == null) return blocks;
        StringBuilder paragraph = new StringBuilder();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String kind = string(item, "type");
            if (kind.equals("text")) {
                String content = text(string(item, "content"));
                paragraph.append(content);
                String modifier = string(item, "modificator");
                if (modifier.equals("BLOCK_END")) paragraph.append("\n\n");
                else if (modifier.equals("LINE_BREAK")) paragraph.append('\n');
            } else if (kind.equals("link")) {
                String label = text(string(item, "content"));
                String url = https(string(item, "url"));
                paragraph.append(label.trim().isEmpty() ? url : label);
            } else {
                flush(blocks, paragraph);
                if (kind.equals("image")) {
                    String url = https(string(item, "url"));
                    if (!url.trim().isEmpty()) blocks.add(new Block("image", "", url, java.util.Collections.emptyList()));
                } else if (kind.equals("ok_video") || kind.equals("video")) {
                    String preview = https(string(item, "preview"));
                    if (preview.trim().isEmpty()) preview = https(string(item, "defaultPreview"));
                    blocks.add(new Block("video", string(item, "title"), preview,
                        allowed ? sources(item.optJSONArray("playerUrls")) : java.util.Collections.emptyList(),
                        string(item, "id"), millis(item, "timeCode"), millis(item, "duration")));
                }
            }
        }
        flush(blocks, paragraph);
        return blocks;
    }

    private static long millis(JSONObject item, String key) {
        return Math.max(0, Math.round(item.optDouble(key, 0) * 1000));
    }

    private static void flush(List<Block> blocks, StringBuilder paragraph) {
        String content = paragraph.toString().trim();
        if (!content.isEmpty()) blocks.add(new Block("text", content, "", java.util.Collections.emptyList()));
        paragraph.setLength(0);
    }

    private static String text(String content) {
        if (!content.startsWith("[")) return content;
        try {
            JSONArray rich = new JSONArray(content);
            return rich.length() >= 2 && rich.opt(0) instanceof String ? rich.getString(0) : content;
        } catch (JSONException ignored) {
            return content;
        }
    }

    private static List<Source> sources(JSONArray items) {
        List<Source> sources = new ArrayList<>();
        if (items == null) return sources;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            String url = https(string(item, "url"));
            String type = string(item, "type").toLowerCase(Locale.ROOT);
            if (!url.trim().isEmpty() && priority(type) < 100) sources.add(new Source(type, url));
        }
        sources.sort(Comparator.comparingInt(source -> priority(source.type())));
        return sources;
    }

    private static int priority(String type) {
        if (type.contains("hls")) return 0;
        if (type.contains("dash")) return 1;
        return switch (type) {
            case "full_hd" -> 2;
            case "high" -> 3;
            case "medium", "mp4" -> 4;
            case "low", "tiny", "lowest" -> 5;
            case "quad_hd", "ultra_hd" -> 6;
            default -> 100;
        };
    }

    static String string(JSONObject value, String key) {
        return value == null || value.isNull(key) ? "" : value.optString(key);
    }

    static String https(String value) {
        if (value.isEmpty()) return "";
        try {
            URI uri = new URI(value);
            boolean valid = "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                && (uri.getHost() != null || lenientHttps(value));
            return valid ? value : "";
        } catch (URISyntaxException e) {
            // Android's Uri.parse accepts international host names and paths; keep those
            // URLs, but still reject credentials and whitespace in the authority.
            return lenientHttps(value) ? value : "";
        }
    }

    private static boolean lenientHttps(String value) {
        return value.matches("(?i)^https://[^/@\\s]+([/?#]\\S*)?$");
    }
}

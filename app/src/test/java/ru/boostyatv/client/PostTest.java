package ru.boostyatv.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PostTest {
    @Test public void parsesRichTextAndOrdersVideoSources() throws Exception {
        JSONObject data = new JSONObject("""
            {"id":"post-1","title":"Заголовок","hasAccess":true,"publishTime":1700000000,
             "user":{"name":"Автор","blogUrl":"author"},
             "data":[
               {"type":"text","content":"Привет","modificator":"LINE_BREAK"},
               {"type":"text","content":"мир","modificator":"BLOCK_END"},
               {"type":"link","content":"Сайт","url":"https://example.com"},
               {"type":"video","id":"video-1","title":"Ролик","preview":"https://cdn.example.com/p.jpg",
                "timeCode":12.5,"duration":90,
                "playerUrls":[
                  {"type":"low","url":"https://cdn.example.com/low.mp4"},
                  {"type":"hls","url":"https://cdn.example.com/master.m3u8"},
                  {"type":"full_hd","url":"https://cdn.example.com/1080.mp4"},
                  {"type":"unknown","url":"https://cdn.example.com/unknown.mp4"}
                ]}
             ]}
            """);
        List<Post> posts = Post.parseList(new JSONArray().put(data));
        assertEquals(1, posts.size());
        Post post = posts.get(0);
        assertEquals("post-1", post.id());
        assertEquals("Заголовок", post.title());
        assertEquals("Автор", post.author());
        assertEquals("author", post.slug());
        assertEquals("https://cdn.example.com/p.jpg", post.cover());
        assertTrue(post.hasVideo());
        assertEquals(2, post.blocks().size());
        assertEquals("Привет\nмир\n\nСайт", post.blocks().get(0).text());
        assertEquals("video-1", post.blocks().get(1).videoId());
        assertEquals(12500, post.blocks().get(1).positionMs());
        assertEquals(90000, post.blocks().get(1).durationMs());
        assertEquals(List.of("hls", "full_hd", "low"), post.blocks().get(1).sources()
            .stream().map(Post.Source::type).toList());
    }

    @Test public void lockedPostKeepsTeaserTextAndDropsPlayableSources() throws Exception {
        JSONObject data = new JSONObject("""
            {"id":"locked","title":"Закрытый","hasAccess":false,
             "user":{"name":"Автор","blogUrl":"author"},
             "teaser":[
               {"type":"text","content":"Тизер"},
               {"type":"video","id":"v","preview":"https://cdn.example.com/t.jpg",
                "playerUrls":[{"type":"hls","url":"https://cdn.example.com/master.m3u8"}]}
             ],
             "data":[{"type":"text","content":"Скрытый текст"}]}
            """);
        Post post = Post.parseList(new JSONArray().put(data)).get(0);
        assertFalse(post.allowed());
        assertEquals("Тизер", post.blocks().get(0).text());
        assertEquals("https://cdn.example.com/t.jpg", post.cover());
        assertFalse(post.hasVideo());
        assertTrue(post.blocks().get(1).sources().isEmpty());
        assertFalse(post.blocks().stream().anyMatch(block -> block.text().contains("Скрытый")));
    }

    @Test public void titleFallsBackToTextAndIsTruncated() throws Exception {
        String longText = "а".repeat(120);
        JSONObject data = new JSONObject()
            .put("id", "p").put("user", new JSONObject().put("blogUrl", "a"))
            .put("data", new JSONArray().put(new JSONObject().put("type", "text").put("content", longText)));
        Post post = Post.parseList(new JSONArray().put(data)).get(0);
        assertEquals(93, post.title().codePointCount(0, post.title().length()));
        assertTrue(post.title().endsWith("…"));
    }

    @Test public void acceptsOnlyHttpsUrlsWithoutCredentials() {
        assertEquals("https://example.com/a", Post.https("https://example.com/a"));
        assertEquals("", Post.https("http://example.com/a"));
        assertEquals("", Post.https("https://user:pass@example.com/a"));
        assertEquals("", Post.https("not a url"));
        assertEquals("", Post.https(""));
        assertEquals("https://cdn.example.com/фото.jpg", Post.https("https://cdn.example.com/фото.jpg"));
        assertEquals("https://cdn.example.com/a_b.jpg", Post.https("https://cdn.example.com/a_b.jpg"));
        assertEquals("", Post.https("https://cdn.example.com/a b.jpg"));
    }

    @Test public void skipsPostsWithoutIdAndUnknownBlockTypes() throws Exception {
        JSONArray items = new JSONArray()
            .put(new JSONObject().put("title", "Без id"))
            .put(new JSONObject().put("id", "ok").put("data", new JSONArray()
                .put(new JSONObject().put("type", "poll"))));
        List<Post> posts = Post.parseList(items);
        assertEquals(1, posts.size());
        assertEquals("ok", posts.get(0).id());
        assertTrue(posts.get(0).blocks().isEmpty());
    }
}

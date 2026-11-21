package ru.boostyatv.client;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LiveStreamTest {
    @Test
    public void readsAdaptiveSourcesOnlyForAnAuthorizedLiveViewer() throws Exception {
        JSONObject payload = new JSONObject("""
            {
              "title":"Studio live", "isOnline":true, "hasAccess":true,
              "user":{"name":"Studio","blogUrl":"studio"},
              "data":[{"type":"ok_stream","defaultPreview":"https://images.example.test/live.jpg","playerUrls":[
                {"type":"live_dash","url":"https://cdn.example.test/live.mpd"},
                {"type":"live_hls","url":"https://cdn.example.test/live.m3u8"},
                {"type":"live_hls","url":"http://cdn.example.test/insecure.m3u8"},
                {"type":"high","url":"https://cdn.example.test/recording.mp4"}
              ]}]
            }
            """);
        LiveStream stream = LiveStream.parse(payload, "", true);
        assertEquals("studio", stream.slug());
        assertEquals("Studio live", stream.title());
        assertEquals("https://images.example.test/live.jpg", stream.cover());
        assertEquals(2, stream.sources().size());
        assertEquals("live_hls", stream.sources().get(0).type());
        assertTrue(LiveStream.parse(payload, "", false).sources().isEmpty());
        payload.put("hasAccess", false);
        assertTrue(LiveStream.parse(payload, "", true).sources().isEmpty());
        payload.put("hasAccess", true).put("isEnded", true);
        assertTrue(LiveStream.parse(payload, "", true).sources().isEmpty());
    }

    @Test
    public void upcomingAndEndedStreamsHaveDistinctVisibility() throws Exception {
        assertNull(LiveStream.parse(new JSONObject(), "studio", true));
        JSONObject payload = new JSONObject("""
            {"publishTime":200, "hasAccess":true, "isOnline":false,
             "teaser":[{"type":"image","url":"https://images.example.test/teaser.jpg"}]}
            """);
        LiveStream upcoming = LiveStream.parse(payload, "studio", false);
        assertTrue(upcoming.visible(100));
        assertFalse(upcoming.visible(300));
        assertEquals("https://images.example.test/teaser.jpg", upcoming.cover());
        assertFalse(LiveStream.parse(payload.put("isEnded", true), "studio", false).visible(100));
    }
}

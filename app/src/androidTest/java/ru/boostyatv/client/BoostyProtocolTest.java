package ru.boostyatv.client;

import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.app.PendingIntent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class BoostyProtocolTest {
    @Test public void reactionLabelsCoverEveryReactionType() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] labels = context.getResources().getStringArray(R.array.reaction_labels);
        assertEquals(Reactions.Type.values().length, labels.length);
        for (Reactions.Type type : Reactions.Type.values()) {
            assertEquals(type, Reactions.allowed(new JSONObject()
                .put("allowedReactionList", new JSONArray().put(type.code))).get(0));
            assertFalse(type.label(context).isEmpty());
        }
    }

    @Test public void phoneChallengeCreatesSessionAndRefreshesItForTheFeed() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SessionStore store = new SessionStore(context.getSharedPreferences("protocol-test", Context.MODE_PRIVATE));
        store.clear();
        try {
            AtomicInteger calls = new AtomicInteger();
            BoostyApi.Exchange exchange = (method, path, token, body) -> {
                switch (calls.getAndIncrement()) {
                    case 0:
                        assertEquals("POST", method);
                        assertEquals("/auth/phone/verification_code/send", path);
                        assertNull(token);
                        assertTrue(body.contains("phone=%2B12025550101"));
                        assertTrue(body.contains("device_os=web"));
                        assertTrue(body.contains("device_id=" + store.deviceId()));
                        return new JSONObject("""
                            {"data":{"phoneCode":{"code":"synthetic-challenge","sentTransport":"gate"}}}
                            """);
                    case 1:
                        assertEquals("PUT", method);
                        assertEquals("/auth/phone/verification_code/confirm", path);
                        assertNull(token);
                        assertTrue(body.contains("phone=%2B12025550101"));
                        assertTrue(body.contains("code=synthetic-challenge"));
                        assertTrue(body.contains("sms_code=012345"));
                        assertTrue(body.contains("device_id=" + store.deviceId()));
                        return new JSONObject("""
                            {"access_token":"first-access","refresh_token":"first-refresh","expires_in":1}
                            """);
                    case 2:
                        assertEquals("POST", method);
                        assertEquals("/oauth/token/", path);
                        assertNull(token);
                        assertTrue(body.contains("grant_type=refresh_token"));
                        assertTrue(body.contains("refresh_token=first-refresh"));
                        return new JSONObject("""
                            {"access_token":"second-access","refresh_token":"second-refresh","expires_in":3600}
                            """);
                    case 3:
                        assertEquals("GET", method);
                        assertTrue(path.startsWith("/v1/feed/post/?"));
                        assertTrue(path.contains("only_allowed=true"));
                        assertEquals("second-access", token);
                        assertNull(body);
                        return new JSONObject("""
                            {"data":{"posts":[]},"extra":{"isLast":false,"offset":"next+cursor"}}
                            """);
                    case 4:
                        assertTrue(path.contains("offset=next%2Bcursor"));
                        assertEquals("second-access", token);
                        return new JSONObject("""
                            {"data":{"posts":[]},"extra":{"isLast":true}}
                            """);
                    default:
                        throw new AssertionError("Unexpected API call");
                }
            };

            BoostyApi api = new BoostyApi(store, exchange);
            assertFalse(api.hasSession());
            BoostyApi.Challenge challenge = api.sendCode("+12025550101");
            assertEquals("gate", challenge.transport());
            api.confirm(challenge, "012345");

            BoostyApi restarted = new BoostyApi(store, exchange);
            assertTrue(restarted.hasSession());
            BoostyApi.Page page = restarted.posts(null, "");
            assertEquals("next+cursor", page.next());
            assertTrue(restarted.posts(null, page.next()).next().isEmpty());
            assertEquals(5, calls.get());
            assertEquals("second-refresh", store.read().getString("refresh"));
            restarted.signOut();
            assertFalse(new BoostyApi(store, exchange).hasSession());
        } finally {
            store.clear();
        }
    }

    @Test public void httpHeadersUseTheSameDeviceIdAsTheForm() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SessionStore store = new SessionStore(context.getSharedPreferences("protocol-test", Context.MODE_PRIVATE));
        String id = store.deviceId();
        assertEquals(4, UUID.fromString(id).version());
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.boosty.to").openConnection();
        try {
            BoostyApi.setRequestHeaders(connection, id);
            assertEquals(id, connection.getRequestProperty("X-From-Id"));
            assertEquals("_clientId=" + id, connection.getRequestProperty("Cookie"));
            assertEquals("en_US", connection.getRequestProperty("X-Locale"));
            assertEquals(id, new SessionStore(context.getSharedPreferences("protocol-test", Context.MODE_PRIVATE)).deviceId());
        } finally {
            connection.disconnect();
        }
    }

    @Test public void countryPickerUsesNationalAndInternationalNumbers() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var countries = PhoneCountry.load(context);
        assertTrue(countries.size() > 200);
        var russia = countries.stream().filter(country -> country.region().equals("RU")).findFirst().get();
        var kazakhstan = countries.stream().filter(country -> country.region().equals("KZ")).findFirst().get();
        var belarus = countries.stream().filter(country -> country.region().equals("BY")).findFirst().get();
        var cayman = countries.stream().filter(country -> country.region().equals("KY")).findFirst().get();
        assertEquals("🇷🇺", russia.flag());
        assertEquals("+79161234567", russia.normalize("916 123-45-67"));
        assertEquals("+79161234567", russia.normalize("8 (916) 123-45-67"));
        assertEquals("+79161234567", russia.normalize("79161234567"));
        assertEquals("+375291234567", belarus.normalize("29 123-45-67"));
        assertEquals("+375291234567", russia.normalize("+375 29 123-45-67"));
        assertEquals(belarus, PhoneCountry.fromInternational("+375 29 123-45-67", russia, countries));
        assertEquals(kazakhstan, PhoneCountry.fromInternational("+7 701 123-45-67", kazakhstan, countries));
        assertEquals(cayman, PhoneCountry.fromInternational("+1 345 555 0123", russia, countries));
        assertEquals("", russia.normalize(""));
    }

    @Test public void parsesRichTextAndUsesOnlyPermittedVideoSources() throws Exception {
        JSONObject text = new JSONObject().put("type", "text")
            .put("content", new JSONArray().put("Текст публикации").put("format").put(new JSONArray()).toString());
        JSONObject video = new JSONObject().put("type", "ok_video").put("playerUrls", new JSONArray()
            .put(new JSONObject().put("type", "full_hd").put("url", "https://media.example.com/video.mp4"))
            .put(new JSONObject().put("type", "hls").put("url", "http://media.example.com/video.m3u8"))
            .put(new JSONObject().put("type", "hls").put("url", "https://media.example.com/video.m3u8")));
        JSONArray data = new JSONArray().put(text).put(video);
        JSONArray rows = new JSONArray()
            .put(new JSONObject().put("id", "open").put("hasAccess", true).put("data", data))
            .put(new JSONObject().put("id", "locked").put("hasAccess", false).put("data", data).put("teaser", data));

        var posts = Post.parseList(rows);
        assertEquals("Текст публикации", posts.get(0).title());
        assertTrue(posts.get(0).hasVideo());
        var sources = posts.get(0).blocks().get(1).sources();
        assertEquals(2, sources.size());
        assertEquals("hls", sources.get(0).type());
        assertFalse(posts.get(1).hasVideo());
    }

    @Test public void channelSearchCommentsAndProgressUseBoostyContracts() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SessionStore store = new SessionStore(context.getSharedPreferences("reader-protocol-test", Context.MODE_PRIVATE));
        store.save(new JSONObject().put("access", "test-access").put("refresh", "test-refresh")
            .put("expiry", System.currentTimeMillis() / 1000 + 3600));
        try {
            AtomicInteger calls = new AtomicInteger();
            BoostyApi api = new BoostyApi(store, (method, path, token, form) -> {
                calls.incrementAndGet();
                assertEquals("test-access", token);
                if (path.startsWith("/v1/search/blog/post/")) {
                    assertEquals("GET", method);
                    assertTrue(path.contains("blog_url=author"));
                    assertTrue(path.contains("search_query=video%20title"));
                    assertTrue(path.contains("tags_ids=1%2C2"));
                    return new JSONObject("""
                        {"data":{"searchPosts":[{"post":{"id":"test-post","title":"Video title","hasAccess":true,
                          "user":{"blogUrl":"author"},"data":[{"type":"ok_video","id":"video-id","timeCode":341,
                          "duration":600,"playerUrls":[{"type":"high","url":"https://media.example.com/video.mp4"}]}]}}]},
                          "extra":{"isLast":true}}
                        """);
                }
                if (path.startsWith("/v1/blog/author/post/tag/")) {
                    return new JSONObject("{\"data\":[{\"id\":1,\"title\":\"First tag\"}]}");
                }
                if (path.startsWith("/v1/blog/author/post/test-post/comment/")) {
                    if (path.endsWith("/reaction?from_page=post")) {
                        assertEquals("DELETE", method);
                        assertEquals("/v1/blog/author/post/test-post/comment/reply/reaction?from_page=post", path);
                        assertNull(form);
                        return new JSONObject();
                    }
                    assertTrue(path.contains("parent_id=123"));
                    assertTrue(path.contains("offset=cursor%2B1"));
                    return new JSONObject("""
                        {"data":[{"id":"reply","intId":456,"author":{"name":"Reader"},
                        "data":[{"type":"text","content":"A reply"}]}],"extra":{"isLast":true}}
                        """);
                }
                if (path.equals("/v1/blog/author/post/test-post/reaction?from_page=post")) {
                    assertEquals("POST", method);
                    assertEquals("reaction=fire", form);
                    return new JSONObject();
                }
                if (path.equals("/v1/blog/author")) {
                    return new JSONObject("{\"allowedReactionList\":[\"fire\",\"rocket\"]}");
                }
                assertEquals("PUT", method);
                assertEquals("/v1/viewstat/video/video-id/timecode/", path);
                assertEquals("time_code=342", form);
                return new JSONObject();
            });
            Post post = api.posts("author", "", "video title", "1,2").posts().get(0);
            Post.Block video = post.blocks().get(0);
            assertEquals(341_000L, video.positionMs());
            assertEquals(600_000L, video.durationMs());
            assertEquals("1", api.tags("author").get(0).id());
            var reply = api.comments(post, 123, "cursor+1").items().get(0);
            assertEquals("A reply", reply.blocks().get(0).text());
            assertEquals(456, reply.id());
            assertEquals("reply", reply.reactionId());
            assertEquals(java.util.Arrays.asList(Reactions.Type.FIRE, Reactions.Type.ROCKET), api.reactionTypes("author"));
            api.setReaction(post, "", "fire");
            api.setReaction(post, reply.reactionId(), "");
            api.updateTimecode(video.videoId(), 342);
            assertEquals(7, calls.get());
        } finally {
            store.clear();
        }
    }

    @Test public void sharingAnExistingLoginSendsAccessWithoutTheRefreshToken() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SessionStore phone = new SessionStore(context.getSharedPreferences("shared-login-test", Context.MODE_PRIVATE));
        long expiry = System.currentTimeMillis() / 1000 + 3600;
        phone.save(new JSONObject().put("access", "temporary-access").put("expiry", expiry)
            .put("refresh", "phone-refresh"));
        InetAddress address = java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
            .flatMap(device -> java.util.Collections.list(device.getInetAddresses()).stream())
            .filter(ip -> ip instanceof java.net.Inet4Address && ip.isSiteLocalAddress()).findFirst().orElse(null);
        try {
            var senderApi = new BoostyApi(phone, (method, path, token, form) -> {
                throw new AssertionError("A valid phone session does not need a refresh");
            });
            var session = senderApi.shareAccess();
            assertEquals(expiry, session.expiry());
            org.junit.Assume.assumeNotNull(address);
            try (TvPairing.Receiver receiver = new TvPairing.Receiver(address, (action, data) -> {
                assertEquals("access", action);
                assertEquals("temporary-access", data.getString("access"));
                assertEquals(phone.deviceId(), data.getString("device"));
                assertFalse(data.has("refresh"));
                return new JSONObject();
            })) {
                FutureTask<Boolean> finished = new FutureTask<>(receiver::await);
                new Thread(finished).start();
                TvPairing.Sender.parse(receiver.invitation()).transferAccess(session);
                assertTrue(finished.get(5, TimeUnit.SECONDS));
            }
            assertEquals("phone-refresh", phone.read().getString("refresh"));
        } finally {
            phone.clear();
        }
    }

    @Test public void reactionsUseTheActorAndAdjustCountsOnChange() throws Exception {
        Reactions state = Reactions.parse(new JSONObject("""
            {"reacted":{"actor":"heart","author":"fire"},
             "reactionCounters":[{"type":"heart","count":2},{"type":"fire","count":4}]}
            """));
        assertEquals("heart", state.selected());
        Reactions changed = state.withSelected("fire");
        assertEquals(1, changed.count(Reactions.Type.HEART));
        assertEquals(5, changed.count(Reactions.Type.FIRE));
        assertEquals(4, changed.withSelected("").count(Reactions.Type.FIRE));
    }

    @Test public void localPairingEncryptsCodeRequestsAndExpiresAfterConfirmation() throws Exception {
        InetAddress address = java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
            .flatMap(device -> java.util.Collections.list(device.getInetAddresses()).stream())
            .filter(ip -> ip instanceof java.net.Inet4Address && ip.isSiteLocalAddress()).findFirst().orElse(null);
        org.junit.Assume.assumeNotNull(address);
        try (TvPairing.Receiver receiver = new TvPairing.Receiver(address, (action, body) -> {
            switch (action) {
                case "send" -> {
                    assertEquals("+12025550101", body.getString("phone"));
                    return new JSONObject().put("transport", "sms");
                }
                case "confirm" -> {
                    assertEquals("012345", body.getString("code"));
                    return new JSONObject();
                }
                default -> throw new AssertionError("Unexpected pairing action");
            }
        })) {
            FutureTask<Boolean> finished = new FutureTask<>(receiver::await);
            new Thread(finished).start();
            TvPairing.Sender phone = TvPairing.Sender.parse(receiver.invitation());
            assertEquals("sms", phone.sendCode("+12025550101").transport());
            phone.confirm("012345");
            assertTrue(finished.get(5, TimeUnit.SECONDS));
        }
    }

    @Test public void playbackTimestampAndWebSessionAreParsedWithoutStartingPlayback() throws Exception {
        assertEquals(341_000L, TimeFormat.parse("5:41"));
        assertEquals(3_661_000L, TimeFormat.parse("1:01:01"));
        assertEquals(-1L, TimeFormat.parse("12:60"));
        assertEquals(50, new WatchHistory.Progress(30_000, 60_000).percentage());
        assertEquals(0L, new WatchHistory.Progress(60_000, 60_000).resume());
        Post.Source source = new Post.Source("high", "https://media.example.com/video.mp4");
        assertEquals(341_000L, PlaybackOptions.App.VLC.intent(source, "Video", 341_000)
            .getLongExtra("position", -1));
        assertEquals(341_000, PlaybackOptions.App.MX_FREE.intent(source, "Video", 341_000)
            .getIntExtra("position", -1));
        assertEquals(342_000L, PlaybackOptions.App.VLC.result(Activity.RESULT_OK, new Intent()
            .putExtra("extra_position", 342_000L).putExtra("extra_duration", 600_000L), 0).position());
        assertEquals(600_000L, PlaybackOptions.App.MX_FREE.result(Activity.RESULT_OK, new Intent()
            .putExtra("end_by", "playback_completion").putExtra("duration", 600_000), 0).position());
        WebSession web = WebSession.parse("{\"accessToken\":\"example\",\"refreshToken\":\"refresh\",\"expiresAt\":1800000000000}",
            UUID.randomUUID().toString());
        assertEquals(1_800_000_000L, web.expiry());
        assertEquals("example", web.access());
    }

    @Test public void externalPlayersKeepTheirOwnPlaybackResultContracts() {
        Post.Source hls = new Post.Source("hls", "https://media.example.com/video.m3u8");
        Post.Source mp4 = new Post.Source("high", "https://media.example.com/video.mp4");
        Intent just = PlaybackOptions.App.JUST_PLUS.intent(hls, "Video", 341_000L);
        assertEquals("com.justplus.player", just.getPackage());
        var qualities = PlaybackOptions.qualityVariants(java.util.Arrays.asList(hls, mp4));
        assertEquals(hls.url(), qualities.get("auto"));
        assertEquals(mp4.url(), qualities.get("720p"));
        assertEquals(342_123L, JustPlusPlayback.progress(new Intent().putExtra("end_by", "user")
            .putExtra("index", 0).putExtra("position_ms", 342_123L), 600_000).position());
        assertNull(JustPlusPlayback.progress(new Intent().putExtra("end_by", "cancelled")
            .putExtra("index", 0).putExtra("position_ms", 0), 600_000));

        Intent vimu = PlaybackOptions.App.VIMU.intent(mp4, "Video", 341_000);
        assertEquals(341_000, vimu.getIntExtra("startfrom", -1));
        assertEquals("Video", vimu.getStringExtra("forcename"));
        assertEquals(342_000L, PlaybackOptions.App.VIMU.result(0,
            new Intent().putExtra("position", 342_000).putExtra("duration", 599_000), 600_000).position());
        assertEquals(600_000L, PlaybackOptions.App.VIMU.result(1, null, 600_000).position());
        assertNull(PlaybackOptions.App.VIMU.result(4, new Intent().putExtra("position", 342_000), 600_000));
    }

    @Test public void webLoginOnlyRecognizesBoostysHttpsReturnWithCodeAndState() {
        assertTrue(BoostyWebLogin.isOAuthReturn(Uri.parse("https://boosty.to/app/oauth_redirect?code=test&state=test")));
        assertFalse(BoostyWebLogin.isOAuthReturn(Uri.parse("https://id.vk.ru/app/oauth_redirect?code=test&state=test")));
        assertFalse(BoostyWebLogin.isOAuthReturn(Uri.parse("https://boosty.to/app/oauth_redirect?code=test")));
        assertFalse(BoostyWebLogin.isOAuthReturn(Uri.parse("https://boosty.to:8443/app/oauth_redirect?code=test&state=test")));
    }

    @Test public void justPlusCallbackStoresProgressAndIgnoresAnOlderSession() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        WatchHistory history = new WatchHistory(context);
        String id = UUID.randomUUID().toString();
        Post.Source source = new Post.Source("high", "https://media.example.com/video.mp4");
        Post.Block video = new Post.Block("video", "Video", "", java.util.Collections.singletonList(source), id, 0, 600_000);
        Intent first = PlaybackOptions.App.JUST_PLUS.intent(source, "Video", 0);
        Intent current = PlaybackOptions.App.JUST_PLUS.intent(source, "Video", 30_000);
        JustPlusPlayback.configure(context, history, first, video, 0);
        JustPlusPlayback.configure(context, history, current, video, 30_000);
        Bundle playlist = current.getBundleExtra("playlist");
        assertEquals(30_000, ((Bundle) playlist.getParcelableArray("items")[0]).getLong("position_ms"));
        PendingIntent oldCallback = first.getBundleExtra("playlist").getParcelable("result_callback");
        PendingIntent callback = playlist.getParcelable("result_callback");
        try {
            callback.send(context, 0, new Intent().putExtra("end_by", "user").putExtra("index", 0)
                .putExtra("position_ms", 31_250L).putExtra("duration_ms", 600_000L));
            oldCallback.send(context, 0, new Intent().putExtra("end_by", "user").putExtra("index", 0)
                .putExtra("position_ms", 1000L).putExtra("duration_ms", 600_000L));
            long deadline = SystemClock.elapsedRealtime() + 3000;
            while (history.get(video).position() != 31_250L && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25);
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(31_250L, history.get(video).position());
        } finally {
            oldCallback.cancel();
            callback.cancel();
            context.getSharedPreferences("watch_progress", Context.MODE_PRIVATE).edit()
                .remove(id + ".position").remove(id + ".duration").remove(id + ".pending").remove(id + ".external").apply();
        }
    }
    @Test public void appUpdateChecksTheVersionAndUsesOnlyTheOfficialApk() throws Exception {
        String url = AppRelease.PROJECT + "/releases/download/v1.10.0/BoostyATV.apk";
        JSONObject asset = new JSONObject().put("name", "BoostyATV.apk").put("browser_download_url", url)
            .put("digest", "sha256:" + "d".repeat(64));
        JSONObject release = new JSONObject().put("tag_name", "v1.10.0").put("body", "- Исправления")
            .put("assets", new JSONArray().put(asset));
        AppRelease update = AppRelease.parse(release);
        assertEquals("- Исправления", update.notes());
        assertEquals(url, update.apkUrl());
        assertTrue(update.newerThan("1.9.0"));
        assertFalse(update.newerThan("1.10.0"));
        assertFalse(update.newerThan("2.0.0"));

        asset.put("browser_download_url", "https://example.com/BoostyATV.apk");
        try {
            AppRelease.parse(release);
            fail("Accepted an APK from outside the project");
        } catch (java.io.IOException expected) {
            assertEquals("Release APK is missing", expected.getMessage());
        }
    }
}

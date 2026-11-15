package ru.boostyatv.client;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.media3.session.MediaController;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ScreenRegressionTest {
    @Test public void returningToHomeRestoresItsFeedAndTabsReuseIt() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SessionStore store = new SessionStore(context.getSharedPreferences("screen-regression", Context.MODE_PRIVATE));
        store.save(new JSONObject().put("access", "fixture").put("expiry", System.currentTimeMillis() / 1000 + 300));
        CountDownLatch homeRefreshed = new CountDownLatch(2);
        BoostyApi fixture = new BoostyApi(store, (method, path, token, form) -> {
            JSONArray posts = new JSONArray();
            if (path.startsWith("/v1/feed/post/")) {
                homeRefreshed.countDown();
                return new JSONObject().put("data", new JSONObject().put("posts", posts));
            }
            return new JSONObject().put("data", posts);
        });
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                set(activity, "api", fixture);
                activity.showFeed(null, null, false);
                View home = activity.currentScreen();
                Object homeFeed = home.getTag(R.id.feed_screen);
                activity.showFeed("test-channel", "Test channel", true);
                activity.back();
                assertSame(home, activity.currentScreen());
                assertSame(homeFeed, get(activity, "currentFeed"));
                activity.refreshFeed();
                activity.showSettings();
                String homeLabel = activity.getString(R.string.nav_home);
                View homeTab = findTab(activity.currentScreen(), homeLabel);
                if (!activity.isTv()) {
                    assertNotNull(homeTab);
                    homeTab.performClick();
                    assertSame(home, activity.currentScreen());
                }
            });
            assertTrue("Home must reload rather than the hidden channel", homeRefreshed.await(5, TimeUnit.SECONDS));
        } finally {
            store.clear();
        }
    }

    @Test public void restoringADeepFeedPositionLoadsBeyondTheFirstPage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SessionStore store = new SessionStore(context.getSharedPreferences("scroll-regression", Context.MODE_PRIVATE));
        store.save(new JSONObject().put("access", "fixture").put("expiry", System.currentTimeMillis() / 1000 + 300));
        CountDownLatch secondPage = new CountDownLatch(1);
        BoostyApi fixture = new BoostyApi(store, (method, path, token, form) -> {
            boolean last = path.contains("offset=second");
            JSONArray posts = new JSONArray();
            for (int i = 0; i < 18; i++) {
                posts.put(new JSONObject().put("id", "post-" + (last ? i + 18 : i))
                    .put("title", "Fixture post").put("hasAccess", true));
            }
            if (last) secondPage.countDown();
            return new JSONObject().put("data", new JSONObject().put("posts", posts))
                .put("extra", new JSONObject().put("offset", last ? "" : "second").put("isLast", last));
        });
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                set(activity, "api", fixture);
                set(activity, "pendingFeedScroll", 24);
                activity.showFeed(null, null, false);
            });
            assertTrue("Restoring a deep position must fetch the second page", secondPage.await(5, TimeUnit.SECONDS));
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            java.util.concurrent.atomic.AtomicInteger position = new java.util.concurrent.atomic.AtomicInteger();
            do {
                scenario.onActivity(activity -> {
                    FeedScreen feed = (FeedScreen) activity.currentScreen().getTag(R.id.feed_screen);
                    position.set(feed.scrollPosition());
                });
                if (position.get() >= 19) return;
                android.os.SystemClock.sleep(50);
            } while (android.os.SystemClock.elapsedRealtime() < deadline);
            fail("Feed did not reach the second page: " + position.get());
        } finally {
            store.clear();
        }
    }

    @Test public void restoredPlayerJoinsTheExistingSessionAtItsCurrentPosition() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File wav = File.createTempFile("player-regression-", ".wav", context.getCacheDir());
        byte[] data = new byte[8000 * 60];
        ByteBuffer header = ByteBuffer.allocate(44 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x46464952).putInt(36 + data.length).putInt(0x45564157)
            .putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(8000).putInt(8000).putShort((short) 1).putShort((short) 8)
            .putInt(0x61746164).putInt(data.length).put(data);
        Files.write(wav.toPath(), header.array());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Bundle state = new Bundle();
            scenario.onActivity(activity -> {
                PlayerScreen screen = (PlayerScreen) get(activity, "playerScreen");
                state.putString("builtin_media_id", "fixture-media");
                state.putStringArrayList("builtin_urls", new java.util.ArrayList<>(List.of(wav.toURI().toString())));
                state.putStringArrayList("builtin_types", new java.util.ArrayList<>(List.of("mp4")));
                state.putLong("builtin_position", 10_000L);
                state.putBoolean("builtin_playing", true);
                screen.restoreState(state);
                screen.start();
            });
            awaitPosition(scenario, 10_000L);
            scenario.onActivity(activity -> {
                PlayerScreen screen = (PlayerScreen) get(activity, "playerScreen");
                screen.saveState(state);
                state.putLong("builtin_position", 1000L);
                invoke(screen, "releaseController", false);
                screen.restoreState(state);
                screen.start();
            });
            awaitPosition(scenario, 10_000L);
        } finally {
            Files.deleteIfExists(wav.toPath());
        }
    }

    private static void awaitPosition(ActivityScenario<MainActivity> scenario, long minimum) {
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = android.os.SystemClock.elapsedRealtime() + 8000;
        do {
            scenario.onActivity(activity -> {
                PlayerScreen screen = (PlayerScreen) get(activity, "playerScreen");
                MediaController player = (MediaController) get(screen, "player");
                ready.set(player != null && player.getPlaybackState() == androidx.media3.common.Player.STATE_READY
                    && player.isPlaying() && player.getCurrentPosition() >= minimum);
            });
            if (ready.get()) return;
            android.os.SystemClock.sleep(50);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        fail("Player did not resume at " + minimum);
    }

    private static View findTab(View view, String description) {
        if (view instanceof android.widget.TextView label && description.contentEquals(label.getText())
            && view.getParent() instanceof View tab && tab.isClickable()) return tab;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findTab(group.getChildAt(i), description);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static Object get(Object target, String name) {
        try { return field(target, name).get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void set(Object target, String name, Object value) {
        try { field(target, name).set(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void invoke(Object target, String name, boolean value) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, boolean.class);
            method.setAccessible(true);
            method.invoke(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}

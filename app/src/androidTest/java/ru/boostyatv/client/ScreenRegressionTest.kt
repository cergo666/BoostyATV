package ru.boostyatv.client

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ScreenRegressionTest {
    @Test
    fun returningToHomeRestoresItsFeedAndTabsReuseIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionStore(context.getSharedPreferences("screen-regression", Context.MODE_PRIVATE))
        store.save(JSONObject().put("access", "fixture").put("expiry", System.currentTimeMillis() / 1000 + 300))
        val homeRefreshed = CountDownLatch(2)
        val fixture = BoostyApi(store) { _, path, _, _ ->
            val posts = JSONArray()
            if (path.startsWith("/v1/feed/post/")) {
                homeRefreshed.countDown()
                JSONObject().put("data", JSONObject().put("posts", posts))
            } else {
                JSONObject().put("data", posts)
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.api = fixture
                    activity.showFeed(null, null, false)
                    val home = activity.homeFeed
                    assertNotNull(home)
                    activity.showFeed("test-channel", "Test channel", true)
                    activity.back()
                    assertSame(home, activity.currentFeed)
                    activity.refreshFeed()
                    activity.showSettings()
                    activity.selectTab(AppTab.HOME)
                    assertSame(home, activity.currentFeed)
                }
                assertTrue("Home must reload rather than the hidden channel", homeRefreshed.await(5, TimeUnit.SECONDS))
            }
        } finally {
            store.clear()
        }
    }

    @Test
    fun restoringADeepFeedPositionLoadsBeyondTheFirstPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionStore(context.getSharedPreferences("scroll-regression", Context.MODE_PRIVATE))
        store.save(JSONObject().put("access", "fixture").put("expiry", System.currentTimeMillis() / 1000 + 300))
        val secondPage = CountDownLatch(1)
        val fixture = BoostyApi(store) { _, path, _, _ ->
            val last = path.contains("offset=second")
            val posts = JSONArray()
            for (i in 0 until 18) {
                posts.put(
                    JSONObject().put("id", "post-" + (if (last) i + 18 else i))
                        .put("title", "Fixture post").put("hasAccess", true)
                )
            }
            if (last) secondPage.countDown()
            JSONObject().put("data", JSONObject().put("posts", posts))
                .put("extra", JSONObject().put("offset", if (last) "" else "second").put("isLast", last))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.api = fixture
                    activity.pendingFeedScroll = 24
                    activity.showFeed(null, null, false)
                }
                assertTrue("Restoring a deep position must fetch the second page", secondPage.await(5, TimeUnit.SECONDS))
                val deadline = SystemClock.elapsedRealtime() + 5000
                val position = AtomicInteger()
                do {
                    scenario.onActivity { activity ->
                        val feed = activity.currentFeed
                        position.set(feed?.scrollPosition() ?: 0)
                    }
                    if (position.get() >= 19) return
                    SystemClock.sleep(50)
                } while (SystemClock.elapsedRealtime() < deadline)
                fail("Feed did not reach the second page: " + position.get())
            }
        } finally {
            store.clear()
        }
    }

    @Test
    fun restoredPlayerJoinsTheExistingSessionAtItsCurrentPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File.createTempFile("player-regression-", ".wav", context.cacheDir)
        val data = ByteArray(8000 * 60)
        val header = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x46464952).putInt(36 + data.size).putInt(0x45564157)
            .putInt(0x20746d66).putInt(16).putShort(1).putShort(1)
            .putInt(8000).putInt(8000).putShort(1).putShort(8)
            .putInt(0x61746164).putInt(data.size).put(data)
        Files.write(wav.toPath(), header.array())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val state = Bundle()
                scenario.onActivity { activity ->
                    val screen = activity.playerController
                    state.putString("builtin_media_id", "fixture-media")
                    state.putStringArrayList("builtin_urls", ArrayList(listOf(wav.toURI().toString())))
                    state.putStringArrayList("builtin_types", ArrayList(listOf("mp4")))
                    state.putLong("builtin_position", 10_000L)
                    state.putBoolean("builtin_playing", true)
                    screen.restoreState(state)
                    screen.start()
                }
                awaitPosition(scenario, 10_000L)
                scenario.onActivity { activity ->
                    val screen = activity.playerController
                    screen.saveState(state)
                    state.putLong("builtin_position", 1000L)
                    screen.releaseController(false)
                    screen.restoreState(state)
                    screen.start()
                }
                awaitPosition(scenario, 10_000L)
            }
        } finally {
            Files.deleteIfExists(wav.toPath())
        }
    }

    private fun awaitPosition(scenario: ActivityScenario<MainActivity>, minimum: Long) {
        val ready = AtomicBoolean()
        val deadline = SystemClock.elapsedRealtime() + 8000
        do {
            scenario.onActivity { activity ->
                val player = activity.playerController.player
                ready.set(
                    player != null &&
                        player.playbackState == Player.STATE_READY &&
                        player.isPlaying &&
                        player.currentPosition >= minimum
                )
            }
            if (ready.get()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Player did not resume at $minimum")
    }
}

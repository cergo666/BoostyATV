package ru.boostyatv.client;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;


import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static ru.boostyatv.client.Ui.refreshRegistered;
import static ru.boostyatv.client.Ui.showError;

public final class MainActivity extends ComponentActivity
        implements FeedScreen.Host, PlayerScreen.Host, SettingsScreen.Host, PostScreen.Host,
        LoginScreen.Host, AuthorsScreen.Host, AboutScreen.Host, AccountMenu.Host {
    @Override public Context context() { return this; }
    @Override public Ui ui() { return ui; }
    @Override public BoostyApi api() { return api; }
    @Override public WatchHistory history() { return watchHistory; }
    @Override public PlaybackOptions options() { return playbackOptions; }
    @Override public boolean isTv() { return tv; }
    @Override public boolean busy() { return busy; }
    @Override public void back() { goBack(); }
    @Override public void refreshLabels() { refreshRegistered(host); }
    @Override public void openPlayer(Post.Block block) { playerScreen.open(block); }
    @Override public boolean updating() { return updates.isUpdating(); }
    @Override public UpdateController updates() { return updates; }
    @Override public void refreshFeed() { showFeed(null, null, false); }
    @Override public void clearSession() { clearSessionScreen(); }
    @Override public void reopenSettings() { reopenSettings = true; recreate(); }
    @Override public View currentScreen() { return host.getChildAt(0); }
    @Override public void onSignedIn() { watchHistory.clear(); navigator.clear(); }
    private static final int MATCH = Ui.MATCH;
    private static final int WRAP = Ui.WRAP;
    private Ui ui;

    private final ExecutorService network = AppExecutors.network("main", 2);
    private UpdateController updates;
    private boolean reopenSettings;
    private boolean foreground;
    private final Artwork artwork = new Artwork();
    private final Navigator navigator = new Navigator();
    private int sessionEpoch;
    private BoostyApi api;
    private FrameLayout host;
    private boolean tv;
    private boolean busy;
    private LoginScreen loginScreen;
    private WatchHistory watchHistory;
    private PlaybackOptions playbackOptions;
    private PlayerScreen playerScreen;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean syncingProgress = new AtomicBoolean();
    private final SharedPreferences.OnSharedPreferenceChangeListener progressChanged = (preferences, key) -> {
        if (key != null && key.endsWith(".position") && host != null && !isFinishing()) {
            refreshRegistered(host);
            syncWatchProgress();
        }
    };
    private final OnBackPressedCallback back = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() {
            if (!busy) goBack();
        }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        UiModeManager modes = getSystemService(UiModeManager.class);
        tv = modes.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        api = new BoostyApi(new SessionStore(this));
        watchHistory = new WatchHistory(this);
        watchHistory.listen(progressChanged);
        playbackOptions = new PlaybackOptions(this);
        ui = new Ui(this, artwork, playbackOptions.oled(), tv, () -> !busy);
        playerScreen = new PlayerScreen(this, this);
        playerScreen.restoreState(savedState);
        updates = new UpdateController(this, () -> foreground && !busy && !playerScreen.hasVideo());
        loginScreen = new LoginScreen(this, this);
        if (playbackOptions.oled()) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }
        host = new FrameLayout(this);
        host.setBackgroundColor(ui.background);
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        host.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            }
            return insets;
        });
        setContentView(host);
        getOnBackPressedDispatcher().addCallback(this, back);
        if (api.hasSession()) {
            if (savedState != null && savedState.getBoolean("feed_route")) {
                pendingFeedScroll = savedState.getInt("feed_scroll", -1);
                showFeed(savedState.getString("feed_slug"), savedState.getString("feed_name"), false);
            } else showFeed(null, null, false);
        } else showLogin();
        if (savedState != null && savedState.getBoolean("reopen_settings") && api.hasSession()) {
            reopenSettings = false;
            showSettings();
        }
        if (!BuildConfig.DEBUG) updates.check(null);
    }

    private void showLogin() {
        loginScreen.show();
    }

    @Override public void scanTvCode() {
        loginScreen.scan();
    }

    @Override public void showFeed(String authorSlug, String authorName, boolean push) {
        boolean home = authorSlug == null;
        LinearLayout screen = ui.column();
        LinearLayout toolbar = ui.toolbar(home ? getString(R.string.feed_title) : authorName, !home,
            () -> { if (!busy) goBack(); },
            heading -> { if (home) heading.setPadding(ui.dp(12), 0, 0, 0); });
        if (home) {
            TextView subscriptions = ui.button(tv ? getString(R.string.feed_subscriptions) : "☰", false, this::showAuthors);
            subscriptions.setContentDescription(getString(R.string.feed_subscriptions));
            toolbar.addView(subscriptions, 0, new LinearLayout.LayoutParams(tv ? WRAP : ui.dp(48), ui.dp(46)));
            TextView refresh = ui.button("⟳", false, () -> {
                if (currentFeed != null) currentFeed.reload();
            });
            refresh.setContentDescription(getString(R.string.account_refresh_feed));
            toolbar.addView(refresh, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));
            if (!tv) {
                TextView qr = ui.button(getString(R.string.feed_qr), false, this::scanTvCode);
                qr.setContentDescription(getString(R.string.feed_qr_description));
                toolbar.addView(qr);
            }
            TextView menu = ui.button("⋯", false, this::showAccountMenu);
            menu.setContentDescription(getString(R.string.feed_menu));
            toolbar.addView(menu, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));
        }
        screen.addView(toolbar);
        FeedScreen feed = new FeedScreen(this, authorSlug, authorName);
        if (pendingFeedScroll >= 0) {
            feed.scrollTo(pendingFeedScroll);
            pendingFeedScroll = -1;
        }
        screen.addView(feed.list(), new LinearLayout.LayoutParams(MATCH, 0, 1));
        currentFeed = feed;
        replace(screen, push);
        feed.load("");
        if (home) syncWatchProgress();
    }

    private FeedScreen currentFeed;
    private int pendingFeedScroll = -1;

    private void showAuthors() {
        AuthorsScreen screen = new AuthorsScreen(this);
        replace(screen.view(), true);
    }

    @Override public void showPost(Post post) {
        PostScreen screen = new PostScreen(this, post);
        replace(screen.view(), true);
        screen.focus();
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    @Override public void syncWatchProgress() {
        if (!api.hasSession() || network.isShutdown() || !syncingProgress.compareAndSet(false, true)) return;
        network.execute(() -> {
            boolean sentAll = false;
            try {
                for (WatchHistory.Pending item : watchHistory.pending()) {
                    api.updateTimecode(item.id(), item.position() / 1000);
                    watchHistory.confirmSync(item);
                }
                sentAll = true;
            } catch (Exception e) {
                // Keep unsent bookmarks for the next playback update or feed refresh.
                Log.d("BoostyATV", "Watch progress sync failed; will retry later", e);
            } finally {
                syncingProgress.set(false);
                if (sentAll && !watchHistory.pending().isEmpty()) handler.post(this::syncWatchProgress);
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        playerScreen.start();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        updates.showStartupUpdate();
        refreshRegistered(host);
        syncWatchProgress();
    }

    @Override protected void onStop() {
        loginScreen.stop();
        playerScreen.stop();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("reopen_settings", reopenSettings);
        playerScreen.saveState(out);
        if (currentFeed != null) {
            out.putBoolean("feed_route", true);
            out.putString("feed_slug", currentFeed.author());
            out.putString("feed_name", currentFeed.title());
            if (currentFeed.unfiltered()) {
                out.putInt("feed_scroll", currentFeed.scrollPosition());
            }
        }
    }

    @Override protected void onPause() {
        foreground = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        updates.destroy();
        loginScreen.destroy();
        playerScreen.destroy();
        watchHistory.stopListening(progressChanged);
        network.shutdown();
        artwork.close();
        super.onDestroy();
    }

    private boolean goBack() {
        if (loginScreen != null && loginScreen.handleBack()) return true;
        if (playerScreen != null && playerScreen.hasVideo()) {
            playerScreen.close();
        }
        if (!navigator.isEmpty()) {
            View previous = navigator.pop();
            replace(previous, false);
            if (tv && previous.getTag() instanceof View focus) focus.requestFocus();
            return true;
        }
        return false;
    }

    private void showAccountMenu() {
        AccountMenu.show(this);
    }

    @Override public void showAbout() {
        AboutScreen screen = new AboutScreen(this);
        replace(screen.view(), true);
        screen.focus();
    }

    @Override public void showSettings() {
        SettingsScreen settings = new SettingsScreen(this);
        replace(settings.view(), true);
        if (tv) settings.focus();
    }

    @Override public <T> void perform(Callable<T> request, TextView error, Consumer<T> done) {
        perform(request, error, done, () -> {});
    }

    @Override public <T> void readInBackground(Callable<T> request, Consumer<T> done, Consumer<Exception> failed) {
        if (isDestroyed() || isFinishing() || network.isShutdown()) return;
        int epoch = sessionEpoch;
        network.execute(() -> {
            try {
                T value = request.call();
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing() && epoch == sessionEpoch) done.accept(value);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || epoch != sessionEpoch) return;
                    if (failure instanceof BoostyApi.SignedOut && !api.hasSession()) {
                        clearSessionScreen();
                        Toast.makeText(this, errorText(failure), Toast.LENGTH_LONG).show();
                    } else failed.accept(failure);
                });
            }
        });
    }

    private void clearSessionScreen() {
        if (playerScreen != null && playerScreen.hasVideo()) playerScreen.close();
        loginScreen.reset();
        sessionEpoch++;
        busy = false;
        navigator.clear();
        watchHistory.clear();
        currentFeed = null;
        pendingFeedScroll = -1;
        BoostyWebLogin.clearCredentials();
        showLogin();
    }

    private String errorText(Exception failure) {
        return failure instanceof BoostyApi.Problem problem
            ? problem.resolve(this) : getString(R.string.error_connection);
    }

    @Override public <T> void perform(Callable<T> request, TextView error, Consumer<T> done, Runnable failed) {
        if (busy) return;
        int epoch = sessionEpoch;
        busy = true;
        error.setVisibility(View.GONE);
        ProgressBar progress = new ProgressBar(this);
        FrameLayout.LayoutParams indicator = new FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.TOP | Gravity.END);
        indicator.setMargins(0, ui.dp(8), ui.dp(8), 0);
        host.addView(progress, indicator);
        network.execute(() -> {
            try {
                T value = request.call();
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || epoch != sessionEpoch) return;
                    host.removeView(progress);
                    busy = false;
                    done.accept(value);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || epoch != sessionEpoch) return;
                    host.removeView(progress);
                    busy = false;
                    failed.run();
                    if (failure instanceof BoostyApi.SignedOut) {
                        clearSessionScreen();
                        Toast.makeText(this, errorText(failure), Toast.LENGTH_LONG).show();
                    } else {
                        showError(error, errorText(failure));
                    }
                });
            }
        });
    }

    @Override public void replace(View view, boolean push) {
        host.post(updates::showStartupUpdate);
        if (push && host.getChildCount() != 0) {
            navigator.push(host.getChildAt(0));
        }
        host.removeAllViews();
        host.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        back.setEnabled(!navigator.isEmpty() || (loginScreen != null && loginScreen.hasState()));
    }
}

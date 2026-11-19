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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
        LoginScreen.Host, AuthorsScreen.Host, AboutScreen.Host, AccountMenu.Host, AccountScreen.Host {
    @Override public Context context() { return this; }
    @Override public Ui ui() { return ui; }
    @Override public BoostyApi api() { return api; }
    @Override public WatchHistory history() { return watchHistory; }
    @Override public PlaybackOptions options() { return playbackOptions; }
    @Override public boolean isTv() { return tv; }
    @Override public boolean busy() { return busy; }
    @Override public void back() { goBack(); }
    @Override public void refreshLabels() { refreshRegistered(host); }
    @Override public void openPlayer(Post post, Post.Block block, boolean fromStart) {
        watchHistory.remember(post);
        playerScreen.open(block, fromStart);
    }
    @Override public boolean updating() { return updates.isUpdating(); }
    @Override public UpdateController updates() { return updates; }
    @Override public void refreshFeed() { if (currentFeed != null) currentFeed.reload(); }
    @Override public void clearSession() { clearSessionScreen(); }
    @Override public void reopenSettings() { reopenSettings = true; recreate(); }
    @Override public View currentScreen() { return host.getChildAt(0); }
    @Override public void onSignedIn() { watchHistory.clear(); navigator.clear(); java.util.Arrays.fill(tabs, null); }
    private static final int MATCH = Ui.MATCH;
    private static final int WRAP = Ui.WRAP;
    private Ui ui;

    private final ExecutorService network = AppExecutors.network("main", 2);
    private UpdateController updates;
    private boolean reopenSettings;
    private boolean foreground;
    private Artwork artwork;
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
        artwork = new Artwork(this);
        UiModeManager modes = getSystemService(UiModeManager.class);
        tv = modes.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        api = new BoostyApi(new SessionStore(this));
        watchHistory = new WatchHistory(this);
        watchHistory.listen(progressChanged);
        playbackOptions = new PlaybackOptions(this);
        ui = new Ui(this, artwork, playbackOptions.oled(), tv, () -> !busy);
        playerScreen = new PlayerScreen(this, this);
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
        if (api.hasSession()) playerScreen.restoreState(savedState);
        if (!BuildConfig.DEBUG) updates.check(null);
    }

    private void showLogin() {
        loginScreen.show();
    }

    @Override public void scanTvCode() {
        loginScreen.scan();
    }

    @Override public void showFeed(String authorSlug, String authorName, boolean push) {
        showFeed(authorSlug, authorName, push, null);
    }

    @Override public void showTag(Post post, BoostyApi.Tag tag) {
        showFeed(post.slug(), post.author().isEmpty() ? post.slug() : post.author(), true, tag);
    }

    private void showFeed(String authorSlug, String authorName, boolean push, BoostyApi.Tag tag) {
        boolean home = authorSlug == null;
        LinearLayout screen = ui.column();
        LinearLayout toolbar = ui.toolbar(home ? getString(tv ? R.string.feed_subscription_feed : R.string.app_name)
            : authorName, !home,
            () -> { if (!busy) goBack(); });
        if (home) {
            ImageView refresh = ui.iconButton(R.drawable.ic_refresh,
                getString(R.string.account_refresh_feed), () -> {
                    if (currentFeed != null) currentFeed.reload();
                });
            toolbar.addView(refresh, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
            if (!tv) {
                ImageView qr = ui.iconButton(R.drawable.ic_qr,
                    getString(R.string.feed_qr_description), this::scanTvCode);
                LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
                qrParams.leftMargin = ui.dp(8);
                toolbar.addView(qr, qrParams);
                ImageView menu = ui.iconButton(R.drawable.ic_more,
                    getString(R.string.feed_menu), this::showAccountMenu);
                LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
                menuParams.leftMargin = ui.dp(8);
                toolbar.addView(menu, menuParams);
            }
        }
        screen.addView(toolbar);
        FeedScreen feed = new FeedScreen(this, authorSlug, authorName);
        if (pendingFeedScroll >= 0) {
            feed.scrollTo(pendingFeedScroll);
            pendingFeedScroll = -1;
        }
        screen.addView(feed.container(), new LinearLayout.LayoutParams(MATCH, 0, 1));
        if (home && !tv) {
            screen.setTag(R.id.nav_tab, 0);
            screen.addView(ui.bottomNav(0, tab(0, () -> showFeed(null, null, true)),
                tab(1, this::showAuthors), tab(2, this::showSettings), tab(3, this::showAccount)));
        }
        View content = tv ? tvSection(screen, home ? 0 : 1) : screen;
        if (tv && !home) content.setTag(R.id.nav_tab, null);
        content.setTag(R.id.feed_screen, feed);
        replace(content, push);
        if (tag == null) feed.load("");
        else feed.openTag(tag);
        if (home) syncWatchProgress();
    }

    private FeedScreen currentFeed;
    private FeedScreen lastFeed;
    private static final int HISTORY_TAB = 4;
    private final View[] tabs = new View[5];
    private boolean selectingTab;
    private int pendingFeedScroll = -1;
    private int activeTab = -1;

    private void showAuthors() {
        AuthorsScreen authors = new AuthorsScreen(this);
        LinearLayout view = (LinearLayout) authors.view();
        if (!tv) {
            view.setTag(R.id.nav_tab, 1);
            view.addView(ui.bottomNav(1, tab(0, () -> showFeed(null, null, true)),
                tab(1, this::showAuthors), tab(2, this::showSettings), tab(3, this::showAccount)));
        }
        replace(tv ? tvSection(view, 1) : view, true);
    }

    private void showAccount() {
        AccountScreen account = new AccountScreen(this);
        View content = account.view();
        if (!tv) {
            LinearLayout wrap = ui.column();
            wrap.setTag(R.id.nav_tab, 3);
            wrap.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1));
            wrap.addView(ui.bottomNav(3, tab(0, () -> showFeed(null, null, true)),
                tab(1, this::showAuthors), tab(2, this::showSettings), tab(3, this::showAccount)));
            content = wrap;
        }
        replace(tv ? tvSection(content, 3) : content, true);
        if (tv && !selectingTab) account.focus();
    }

    @Override public void showPost(Post post) {
        PostScreen screen = new PostScreen(this, post);
        replace(screen.view(), true);
        screen.focus();
        if (tv) ui.reveal(screen.view());
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
        if (lastFeed != null) {
            out.putBoolean("feed_route", true);
            out.putString("feed_slug", lastFeed.author());
            out.putString("feed_name", lastFeed.title());
            if (lastFeed.unfiltered()) {
                out.putInt("feed_scroll", lastFeed.scrollPosition());
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
        if (artwork != null) artwork.close();
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
        View content = settings.view();
        if (!tv) {
            LinearLayout wrap = ui.column();
            wrap.setTag(R.id.nav_tab, 2);
            wrap.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1));
            wrap.addView(ui.bottomNav(2, tab(0, () -> showFeed(null, null, true)),
                tab(1, this::showAuthors), tab(2, this::showSettings), tab(3, this::showAccount)));
            content = wrap;
        }
        replace(tv ? tvSection(content, 2) : content, true);
        if (tv && !selectingTab) settings.focus();
    }

    private void showHistory() {
        replace(tvSection(HistoryScreen.build(this), HISTORY_TAB), true);
    }

    private View tvSection(View content, int index) {
        LinearLayout section = ui.row();
        section.setTag(R.id.nav_tab, index);
        ScrollView menu = new ScrollView(this);
        menu.setFillViewport(true);
        menu.setVerticalScrollBarEnabled(false);
        menu.setBackgroundColor(ui.surface);
        LinearLayout rail = ui.column();
        rail.setPadding(ui.dp(32), ui.dp(27), ui.dp(10), ui.dp(27));
        menu.addView(rail, new ScrollView.LayoutParams(MATCH, WRAP));
        section.addView(menu, new LinearLayout.LayoutParams(ui.dp(180), MATCH));
        ui.weighted(section, content, 0, MATCH);
        View[] entries = {
            ui.tvNavItem(R.drawable.ic_subscriptions, R.string.feed_subscriptions, index == 0, tab(0, () -> showFeed(null, null, true))),
            ui.tvNavItem(R.drawable.ic_tv, R.string.nav_channels, index == 1, tab(1, this::showAuthors)),
            ui.tvNavItem(R.drawable.ic_history, R.string.nav_history, index == HISTORY_TAB, tab(HISTORY_TAB, this::showHistory)),
            ui.tvNavItem(R.drawable.ic_settings, R.string.account_settings, index == 2, tab(2, this::showSettings)),
            ui.tvNavItem(R.drawable.ic_person, R.string.nav_account, index == 3, tab(3, this::showAccount))
        };
        for (View entry : entries) ui.margin(rail, entry, MATCH, ui.dp(48), 5);
        return section;
    }

    /** Top-level navigation preserves the home and subscriptions screens when switching tabs. */
    private Runnable tab(int index, Runnable action) {
        return () -> {
            if (activeTab == index) return;
            navigator.clear();
            if (index != 0 && tabs[0] != null) navigator.push(tabs[0]);
            selectingTab = true;
            try {
                if (tabs[index] != null && index != HISTORY_TAB) replace(tabs[index], false);
                else action.run();
            } finally {
                selectingTab = false;
            }
        };
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
        lastFeed = null;
        java.util.Arrays.fill(tabs, null);
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
        if (push && !selectingTab && host.getChildCount() != 0) {
            navigator.push(host.getChildAt(0));
        }
        host.removeAllViews();
        host.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        Object tab = view.getTag(R.id.nav_tab);
        activeTab = tab instanceof Integer value ? value : -1;
        if (activeTab >= 0 && activeTab != HISTORY_TAB) tabs[activeTab] = view;
        if (tv && selectingTab && activeTab >= 0 && view instanceof LinearLayout frame) {
            frame.findViewById(R.id.tv_nav_selected).requestFocus();
            ui.reveal(frame.getChildAt(1));
        }
        currentFeed = view.getTag(R.id.feed_screen) instanceof FeedScreen feed ? feed : null;
        if (currentFeed != null) lastFeed = currentFeed;
        back.setEnabled(!navigator.isEmpty() || (loginScreen != null && loginScreen.hasState()));
    }
}

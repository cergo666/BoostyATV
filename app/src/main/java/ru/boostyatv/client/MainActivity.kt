package ru.boostyatv.client

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface AppRoute {
    data object Login : AppRoute
    data class WebLogin(val target: TvPairing.Sender?) : AppRoute
    data class PairPhone(val target: TvPairing.Sender) : AppRoute
    data object Home : AppRoute
    data class ChannelFeed(val state: FeedState) : AppRoute
    data object Channels : AppRoute
    data object History : AppRoute
    data object Settings : AppRoute
    data object Account : AppRoute
    data object About : AppRoute
    data class PostDetail(val post: Post) : AppRoute
}

class MainActivity : ComponentActivity() {
    private val network = AppExecutors.network("main", 2)
    private val handler = Handler(Looper.getMainLooper())
    private val syncingProgress = AtomicBoolean()

    internal lateinit var api: BoostyApi
    internal lateinit var watchHistory: WatchHistory
    internal lateinit var playbackOptions: PlaybackOptions
    internal lateinit var artwork: Artwork
    internal lateinit var updates: UpdateController
    internal lateinit var playerController: PlayerSessionController

    private var tvMode = false
    private var foreground = false
    private var sessionEpoch = 0
    internal var pendingFeedScroll = -1

    internal var currentRoute by mutableStateOf<AppRoute>(AppRoute.Login)
        private set
    private val backStack = mutableStateListOf<AppRoute>()
    internal var homeFeed by mutableStateOf<FeedState?>(null)
        private set
    internal var currentFeed by mutableStateOf<FeedState?>(null)
        private set
    private var lastFeed: FeedState? = null
    private var authorsState by mutableStateOf<AuthorsState?>(null)

    private var oledEnabled by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var progressRevision by mutableIntStateOf(0)
    private var profile by mutableStateOf<BoostyApi.Profile?>(null)
    private var loadingProfile by mutableStateOf(false)
    private var historyError by mutableStateOf<String?>(null)
    private var pendingInvitation by mutableStateOf<TvPairing.Sender?>(null)
    private var showLogoutDialog by mutableStateOf(false)
    private var updateUiState by mutableStateOf(UpdateController.UiState(false, false, -1, "", null))

    private val scanTv = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@registerForActivityResult
        try {
            pendingInvitation = TvPairing.Sender.parse(raw, resources)
        } catch (e: Exception) {
            Log.w("BoostyATV", "Pairing invitation parsing failed", e)
            Toast.makeText(this, getString(R.string.login_qr_not_pairing), Toast.LENGTH_LONG).show()
        }
    }

    private val progressChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.endsWith(".position") && !isFinishing) {
            progressRevision++
            syncWatchProgress()
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!busy) back()
        }
    }

    private val feedBackend = object : FeedBackend {
        override fun loadPosts(
            author: String?,
            offset: String,
            query: String,
            tags: String,
            onSuccess: (BoostyApi.Page) -> Unit,
            onError: (Exception) -> Unit,
        ) {
            readInBackground(
                request = { api.posts(author, offset, query, tags) },
                done = onSuccess,
                failed = onError,
            )
        }

        override fun loadTags(
            author: String,
            onSuccess: (List<BoostyApi.Tag>) -> Unit,
            onError: (String) -> Unit,
        ) {
            perform(
                request = { api.tags(author) },
                done = onSuccess,
                failed = onError,
            )
        }
    }

    private val authorsBackend = object : AuthorsBackend {
        override fun loadAuthors(
            offset: Int,
            onSuccess: (BoostyApi.Authors) -> Unit,
            onError: (String) -> Unit,
        ) {
            readInBackground(
                request = { api.authors(offset) },
                done = onSuccess,
                failed = { onError(errorText(it)) },
            )
        }
    }

    private val postBackend = object : PostBackend {
        override fun loadComments(
            post: Post,
            parent: Long,
            offset: String,
            onSuccess: (BoostyApi.Comments) -> Unit,
            onError: () -> Unit,
        ) {
            readInBackground(
                request = { api.comments(post, parent, offset) },
                done = onSuccess,
                failed = { onError() },
            )
        }

        override fun loadReactionTypes(
            slug: String,
            onSuccess: (List<Reactions.Type>) -> Unit,
            onError: () -> Unit,
        ) {
            readInBackground(
                request = { api.reactionTypes(slug) },
                done = onSuccess,
                failed = { onError() },
            )
        }

        override fun setReaction(
            post: Post,
            commentId: String,
            choice: String,
            onSuccess: () -> Unit,
            onError: () -> Unit,
        ) {
            readInBackground(
                request = {
                    api.setReaction(post, commentId, choice)
                    true
                },
                done = { onSuccess() },
                failed = { onError() },
            )
        }
    }

    private val loginBackend = object : LoginBackend {
        override val api: BoostyApi get() = this@MainActivity.api
        override val busy: Boolean get() = this@MainActivity.busy

        override fun sendCode(
            phone: String,
            pairSender: TvPairing.Sender?,
            onSuccess: (BoostyApi.Challenge) -> Unit,
            onError: (String) -> Unit,
        ) {
            perform(
                request = { if (pairSender == null) api.sendCode(phone) else pairSender.sendCode(phone) },
                done = onSuccess,
                failed = onError,
            )
        }

        override fun confirmCode(
            challenge: BoostyApi.Challenge,
            code: String,
            pairSender: TvPairing.Sender?,
            onSuccess: () -> Unit,
            onError: (String) -> Unit,
        ) {
            perform(
                request = {
                    if (pairSender == null) api.confirm(challenge, code)
                    else pairSender.confirm(code)
                    true
                },
                done = { onSuccess() },
                failed = onError,
            )
        }

        override fun finishWebLogin(
            session: WebSession,
            pairSender: TvPairing.Sender?,
            onSuccess: () -> Unit,
            onError: (String) -> Unit,
        ) {
            perform(
                request = {
                    if (pairSender == null) api.importWebSession(session)
                    else pairSender.transfer(session)
                    true
                },
                done = { onSuccess() },
                failed = onError,
            )
        }

        override fun transferCurrentAccess(
            invitation: TvPairing.Sender,
            onSuccess: () -> Unit,
            onError: (String) -> Unit,
        ) {
            perform(
                request = {
                    invitation.transferAccess(api.shareAccess())
                    true
                },
                done = { onSuccess() },
                failed = onError,
            )
        }
    }

    internal fun isTv(): Boolean = tvMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        artwork = Artwork(this)
        val modes = getSystemService(UiModeManager::class.java)
        tvMode = modes.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        api = BoostyApi(SessionStore(this))
        watchHistory = WatchHistory(this)
        watchHistory.listen(progressChanged)
        playbackOptions = PlaybackOptions(this)
        oledEnabled = playbackOptions.oled()
        applyWindowChrome(oledEnabled)

        playerController = PlayerSessionController(
            activity = this,
            callbacks = object : PlayerHostCallbacks {
                override val isTv: Boolean get() = tvMode
                override val watchHistory: WatchHistory get() = this@MainActivity.watchHistory
                override val playbackOptions: PlaybackOptions get() = this@MainActivity.playbackOptions
                override fun onSyncWatchProgress() = syncWatchProgress()
                override fun onProgressUpdated() {
                    progressRevision++
                }
                override fun onPlayerOpened() = updateBackEnabled()
                override fun onPlayerClosed() = updateBackEnabled()
            },
        )

        updates = UpdateController(this) { foreground && !busy && !playerController.hasVideo() }
        updates.setListener { state -> updateUiState = state }

        onBackPressedDispatcher.addCallback(this, backCallback)

        if (api.hasSession()) {
            if (savedInstanceState != null && savedInstanceState.getBoolean("feed_route")) {
                pendingFeedScroll = savedInstanceState.getInt("feed_scroll", -1)
                showFeed(savedInstanceState.getString("feed_slug"), savedInstanceState.getString("feed_name"), false)
            } else {
                showFeed(null, null, false)
            }
            if (savedInstanceState != null && savedInstanceState.getBoolean("reopen_settings")) {
                showSettings()
            }
            playerController.restoreState(savedInstanceState)
        } else {
            currentRoute = AppRoute.Login
            updateBackEnabled()
        }

        if (!BuildConfig.DEBUG) updates.checkBackground()

        setContent {
            BoostyAppTheme(
                oled = oledEnabled,
                isTv = tvMode,
                artwork = artwork,
            ) {
                BoostyRootContent()
            }
        }
    }

    @Composable
    private fun BoostyRootContent() {
        val route = currentRoute
        val selectedTab = when (route) {
            AppRoute.Home -> AppTab.HOME
            AppRoute.Channels, is AppRoute.ChannelFeed -> if (tvMode || route == AppRoute.Channels) AppTab.CHANNELS else null
            AppRoute.History -> AppTab.HISTORY
            AppRoute.Settings -> AppTab.SETTINGS
            AppRoute.Account -> AppTab.ACCOUNT
            else -> null
        }
        val showNav = when (route) {
            AppRoute.Home, AppRoute.Channels, AppRoute.History, AppRoute.Settings, AppRoute.Account -> true
            is AppRoute.ChannelFeed -> tvMode
            else -> false
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (playerController.active) {
                PlayerScreenView(
                    controller = playerController,
                    options = playbackOptions,
                    onBack = ::back,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (!tvMode) Modifier.safeDrawingPadding() else Modifier),
                ) {
                    AppNavigationShell(
                        selectedTab = selectedTab,
                        showNavigation = showNav,
                        oled = oledEnabled,
                        onSelectTab = ::selectTab,
                    ) {
                        when (route) {
                            AppRoute.Login -> LoginScreenView(
                                backend = loginBackend,
                                pairSender = null,
                                onSignedIn = ::onSignedIn,
                                onPairFinished = {},
                                onOpenWebLogin = { target -> navigateTo(AppRoute.WebLogin(target), push = true) },
                            )

                            is AppRoute.PairPhone -> LoginScreenView(
                                backend = loginBackend,
                                pairSender = route.target,
                                onSignedIn = ::back,
                                onPairFinished = ::back,
                                onOpenWebLogin = { target -> navigateTo(AppRoute.WebLogin(target), push = true) },
                            )

                            is AppRoute.WebLogin -> WebLoginScreenView(
                                target = route.target,
                                backend = loginBackend,
                                onBack = ::back,
                                onFinished = {
                                    if (route.target == null) onSignedIn() else back()
                                },
                            )

                            AppRoute.Home -> {
                                val feed = ensureHomeFeed()
                                FeedScreenView(
                                    state = feed,
                                    watchHistory = watchHistory,
                                    progressRevision = progressRevision,
                                    onBack = ::back,
                                    onOpenPost = ::showPost,
                                    onOpenRecentPost = ::openRecentPost,
                                    onScanQr = ::scanTvCode,
                                    onOpenSettings = { selectTab(AppTab.SETTINGS) },
                                    onOpenAbout = ::showAbout,
                                    onRequestLogout = { showLogoutDialog = true },
                                )
                            }

                            is AppRoute.ChannelFeed -> FeedScreenView(
                                state = route.state,
                                watchHistory = watchHistory,
                                progressRevision = progressRevision,
                                onBack = ::back,
                                onOpenPost = ::showPost,
                                onOpenRecentPost = ::openRecentPost,
                                onScanQr = ::scanTvCode,
                                onOpenSettings = { selectTab(AppTab.SETTINGS) },
                                onOpenAbout = ::showAbout,
                                onRequestLogout = { showLogoutDialog = true },
                            )

                            AppRoute.Channels -> {
                                val state = ensureAuthorsState()
                                AuthorsScreenView(
                                    state = state,
                                    onOpenAuthor = { slug, name -> showFeed(slug, name, push = true) },
                                )
                            }

                            AppRoute.History -> HistoryScreenView(
                                watchHistory = watchHistory,
                                progressRevision = progressRevision,
                                errorText = historyError,
                                onOpenRecent = ::openRecentPost,
                            )

                            AppRoute.Settings -> SettingsScreenView(
                                options = playbackOptions,
                                oledEnabled = oledEnabled,
                                updating = updateUiState.updating(),
                                onToggleOled = { enabled ->
                                    playbackOptions.setOled(enabled)
                                    oledEnabled = enabled
                                    applyWindowChrome(enabled)
                                },
                                onScanTvCode = ::scanTvCode,
                                onOpenAbout = ::showAbout,
                                onCheckUpdates = { updates.checkInteractive() },
                            )

                            AppRoute.Account -> AccountScreenView(
                                profile = profile,
                                loadingProfile = loadingProfile,
                                onRequestProfile = ::loadProfileIfNeeded,
                                onLogout = ::performLogout,
                            )

                            AppRoute.About -> AboutScreenView(
                                updateStatus = updateUiState.statusMessage(),
                                isUpdating = updateUiState.updating(),
                                onBack = ::back,
                                onCheckUpdates = { updates.checkInteractive() },
                            )

                            is AppRoute.PostDetail -> PostScreenView(
                                post = route.post,
                                backend = postBackend,
                                watchHistory = watchHistory,
                                progressRevision = progressRevision,
                                onBack = ::back,
                                onOpenPlayer = ::openPlayer,
                                onOpenTag = ::showTag,
                            )
                        }
                    }

                    if (busy) {
                        CircularProgressIndicator(
                            color = BoostyTheme.palette.accent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(14.dp)
                                .size(24.dp),
                        )
                    }
                }
            }

            pendingInvitation?.let { invitation ->
                PairInvitationDialog(
                    signedIn = api.hasSession(),
                    invitation = invitation,
                    backend = loginBackend,
                    onDismiss = { pendingInvitation = null },
                    onStartPhonePair = { target -> navigateTo(AppRoute.PairPhone(target), push = true) },
                    onStartSocialPair = { target -> navigateTo(AppRoute.WebLogin(target), push = true) },
                )
            }

            if (showLogoutDialog) {
                BoostyConfirmDialog(
                    title = getString(R.string.account_logout),
                    message = getString(R.string.account_logout_confirm),
                    confirmLabel = getString(R.string.account_logout_button),
                    danger = true,
                    onConfirm = {
                        showLogoutDialog = false
                        performLogout()
                    },
                    onDismiss = { showLogoutDialog = false },
                )
            }

            UpdateDialogs(
                state = updateUiState,
                onAccept = { updates.acceptOffer() },
                onDismissOffer = { updates.dismissOffer() },
                onCancelDownload = { updates.cancelDownload() },
            )
        }

        LaunchedEffect(currentRoute) {
            updates.showStartupUpdate()
        }
    }

    private fun ensureHomeFeed(): FeedState {
        val existing = homeFeed
        if (existing != null) return existing
        val created = FeedState(author = null, title = null, backend = feedBackend)
        if (pendingFeedScroll >= 0) {
            created.pendingScroll = pendingFeedScroll
            pendingFeedScroll = -1
        }
        created.ensureLoaded()
        homeFeed = created
        return created
    }

    private fun ensureAuthorsState(): AuthorsState {
        val existing = authorsState
        if (existing != null) return existing
        val created = AuthorsState(authorsBackend)
        authorsState = created
        return created
    }

    internal fun showFeed(authorSlug: String?, authorName: String?, push: Boolean, tag: BoostyApi.Tag? = null) {
        if (authorSlug == null) {
            val feed = ensureHomeFeed()
            if (pendingFeedScroll >= 0) {
                feed.pendingScroll = pendingFeedScroll
                pendingFeedScroll = -1
            }
            if (tag != null) feed.openTag(tag) else feed.ensureLoaded()
            navigateTo(AppRoute.Home, push = push)
            syncWatchProgress()
        } else {
            val feed = FeedState(author = authorSlug, title = authorName, backend = feedBackend)
            if (pendingFeedScroll >= 0) {
                feed.pendingScroll = pendingFeedScroll
                pendingFeedScroll = -1
            }
            if (tag != null) feed.openTag(tag) else feed.ensureLoaded()
            navigateTo(AppRoute.ChannelFeed(feed), push = push)
        }
    }

    internal fun showTag(post: Post, tag: BoostyApi.Tag) {
        val authorName = post.author().ifEmpty { post.slug() }
        showFeed(post.slug(), authorName, push = true, tag = tag)
    }

    internal fun showPost(post: Post) {
        navigateTo(AppRoute.PostDetail(post), push = true)
    }

    internal fun showSettings() {
        selectTab(AppTab.SETTINGS)
    }

    internal fun showAbout() {
        navigateTo(AppRoute.About, push = true)
    }

    internal fun selectTab(tab: AppTab) {
        val targetRoute: AppRoute = when (tab) {
            AppTab.HOME -> AppRoute.Home
            AppTab.CHANNELS -> AppRoute.Channels
            AppTab.HISTORY -> AppRoute.History
            AppTab.SETTINGS -> AppRoute.Settings
            AppTab.ACCOUNT -> AppRoute.Account
        }
        if (currentRoute == targetRoute) return
        backStack.clear()
        if (tab != AppTab.HOME) backStack.add(AppRoute.Home)
        if (tab == AppTab.HOME) ensureHomeFeed()
        navigateTo(targetRoute, push = false)
    }

    internal fun refreshFeed() {
        currentFeed?.reload(userPull = false)
    }

    internal fun back() {
        if (playerController.hasVideo()) {
            playerController.close()
            return
        }
        if (backStack.isNotEmpty()) {
            val previous = backStack.removeAt(backStack.lastIndex)
            navigateTo(previous, push = false)
        }
    }

    private fun navigateTo(route: AppRoute, push: Boolean) {
        if (push && currentRoute != route) {
            backStack.add(currentRoute)
            while (backStack.size > Navigator.LIMIT) backStack.removeAt(0)
        }
        currentRoute = route
        currentFeed = when (route) {
            AppRoute.Home -> homeFeed
            is AppRoute.ChannelFeed -> route.state
            else -> null
        }
        currentFeed?.let { lastFeed = it }
        updateBackEnabled()
    }

    private fun updateBackEnabled() {
        backCallback.isEnabled = playerController.hasVideo() || backStack.isNotEmpty()
    }

    private fun openPlayer(post: Post, block: Post.Block, fromStart: Boolean) {
        watchHistory.remember(post)
        progressRevision++
        playerController.open(post, block, fromStart)
    }

    private fun openRecentPost(recent: WatchHistory.RecentPost) {
        historyError = null
        perform(
            request = { api.post(recent.slug(), recent.id()) },
            done = { post -> showPost(post) },
            failed = { err ->
                historyError = err
                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun scanTvCode() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.login_qr_prompt))
            .setBeepEnabled(false)
            .setCaptureActivity(PortraitQrActivity::class.java)
            .setOrientationLocked(false)
        scanTv.launch(options)
    }

    private fun onSignedIn() {
        watchHistory.clear()
        backStack.clear()
        homeFeed = null
        authorsState = null
        profile = null
        pendingFeedScroll = -1
        showFeed(null, null, push = false)
    }

    private fun loadProfileIfNeeded() {
        if (profile != null || loadingProfile) return
        loadingProfile = true
        readInBackground(
            request = { api.currentUser() },
            done = { loaded ->
                profile = loaded
                loadingProfile = false
            },
            failed = { loadingProfile = false },
        )
    }

    private fun performLogout() {
        readInBackground(
            request = {
                api.signOut()
                true
            },
            done = { clearSessionScreen() },
            failed = { clearSessionScreen() },
        )
    }

    private fun clearSessionScreen() {
        if (playerController.hasVideo()) playerController.close()
        sessionEpoch++
        busy = false
        backStack.clear()
        watchHistory.clear()
        homeFeed = null
        currentFeed = null
        lastFeed = null
        authorsState = null
        profile = null
        loadingProfile = false
        pendingFeedScroll = -1
        pendingInvitation = null
        BoostyWebLogin.clearCredentials()
        navigateTo(AppRoute.Login, push = false)
    }

    private fun syncWatchProgress() {
        if (!api.hasSession() || network.isShutdown || !syncingProgress.compareAndSet(false, true)) return
        network.execute {
            var sentAll = false
            try {
                for (item in watchHistory.pending()) {
                    api.updateTimecode(item.id(), item.position() / 1000L)
                    watchHistory.confirmSync(item)
                }
                sentAll = true
            } catch (e: Exception) {
                Log.d("BoostyATV", "Watch progress sync failed; will retry later", e)
            } finally {
                syncingProgress.set(false)
                if (sentAll && watchHistory.pending().isNotEmpty()) {
                    handler.post { syncWatchProgress() }
                }
            }
        }
    }

    private fun <T> readInBackground(
        request: Callable<T>,
        done: (T) -> Unit,
        failed: (Exception) -> Unit,
    ) {
        if (isDestroyed || isFinishing || network.isShutdown) return
        val epoch = sessionEpoch
        network.execute {
            try {
                val value = request.call()
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && epoch == sessionEpoch) done(value)
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (isDestroyed || isFinishing || epoch != sessionEpoch) return@runOnUiThread
                    if (failure is BoostyApi.SignedOut && !api.hasSession()) {
                        clearSessionScreen()
                        Toast.makeText(this, errorText(failure), Toast.LENGTH_LONG).show()
                    } else {
                        failed(failure)
                    }
                }
            }
        }
    }

    private fun <T> perform(
        request: Callable<T>,
        done: (T) -> Unit,
        failed: (String) -> Unit,
    ) {
        if (busy || isDestroyed || isFinishing || network.isShutdown) return
        val epoch = sessionEpoch
        busy = true
        network.execute {
            try {
                val value = request.call()
                runOnUiThread {
                    if (isDestroyed || isFinishing || epoch != sessionEpoch) return@runOnUiThread
                    busy = false
                    done(value)
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (isDestroyed || isFinishing || epoch != sessionEpoch) return@runOnUiThread
                    busy = false
                    if (failure is BoostyApi.SignedOut) {
                        clearSessionScreen()
                        Toast.makeText(this, errorText(failure), Toast.LENGTH_LONG).show()
                    } else {
                        failed(errorText(failure))
                    }
                }
            }
        }
    }

    private fun errorText(failure: Exception): String {
        return if (failure is BoostyApi.Problem) {
            failure.resolve(this)
        } else {
            getString(R.string.error_connection)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyWindowChrome(oled: Boolean) {
        val barColor = if (oled) AndroidColor.BLACK else 0xFF0C0D12.toInt()
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
        }
    }

    override fun onStart() {
        super.onStart()
        playerController.start()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        updates.showStartupUpdate()
        progressRevision++
        syncWatchProgress()
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    override fun onStop() {
        playerController.stop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("reopen_settings", currentRoute == AppRoute.Settings)
        playerController.saveState(outState)
        val feed = lastFeed
        if (feed != null) {
            outState.putBoolean("feed_route", true)
            outState.putString("feed_slug", feed.author)
            outState.putString("feed_name", feed.title)
            if (feed.unfiltered()) {
                outState.putInt("feed_scroll", feed.scrollPosition())
            }
        }
    }

    override fun onDestroy() {
        updates.destroy()
        playerController.destroy()
        watchHistory.stopListening(progressChanged)
        network.shutdown()
        artwork.close()
        super.onDestroy()
    }
}

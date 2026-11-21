package ru.boostyatv.client

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

internal enum class AppTab {
    HOME,
    CHANNELS,
    HISTORY,
    SETTINGS,
    ACCOUNT,
}

private data class NavEntry(
    val tab: AppTab,
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val labelRes: Int,
)

private val TvNavEntries = listOf(
    NavEntry(AppTab.HOME, R.drawable.ic_subscriptions, R.string.feed_subscriptions),
    NavEntry(AppTab.CHANNELS, R.drawable.ic_tv, R.string.nav_channels),
    NavEntry(AppTab.HISTORY, R.drawable.ic_history, R.string.nav_history),
    NavEntry(AppTab.SETTINGS, R.drawable.ic_settings, R.string.account_settings),
    NavEntry(AppTab.ACCOUNT, R.drawable.ic_person, R.string.nav_account),
)

private val PhoneNavEntries = listOf(
    NavEntry(AppTab.HOME, R.drawable.ic_home, R.string.nav_home),
    NavEntry(AppTab.CHANNELS, R.drawable.ic_subscriptions, R.string.feed_subscriptions),
    NavEntry(AppTab.SETTINGS, R.drawable.ic_settings, R.string.settings_title),
    NavEntry(AppTab.ACCOUNT, R.drawable.ic_person, R.string.nav_account),
)

internal val TvRailCollapsedWidth = 76.dp
private val TvRailExpandedWidth = 228.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AppNavigationShell(
    selectedTab: AppTab?,
    showNavigation: Boolean,
    oled: Boolean,
    onSelectTab: (AppTab) -> Unit,
    backdrop: @Composable () -> Unit = { AmbientBackdrop(oled = oled) },
    content: @Composable () -> Unit,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        backdrop()

        if (isTv) {
            if (showNavigation) {
                var railFocused by remember { mutableStateOf(false) }
                val railWidth by animateDpAsState(
                    targetValue = if (railFocused) TvRailExpandedWidth else TvRailCollapsedWidth,
                    animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
                    label = "railWidth",
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = TvRailCollapsedWidth)
                        .focusRestorer()
                        .focusGroup(),
                ) {
                    content()
                }

                AnimatedVisibility(
                    visible = railFocused,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(160)),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = TvRailCollapsedWidth)
                        .zIndex(5f),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xD006070B),
                                        Color(0x5506070B),
                                        Color.Transparent,
                                    ),
                                )
                            )
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(railWidth)
                        .zIndex(10f)
                        .background(palette.railBackground)
                        .focusRestorer()
                        .focusGroup()
                        .onFocusChanged { railFocused = it.hasFocus }
                        .padding(vertical = 24.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.app_icon),
                            contentDescription = stringResource(R.string.app_name),
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        if (railFocused) {
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    TvNavEntries.forEach { entry ->
                        TvRailItem(
                            entry = entry,
                            selected = selectedTab == entry.tab,
                            expanded = railFocused,
                            onClick = { onSelectTab(entry.tab) },
                        )
                    }
                }
            } else {
                content()
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    content()
                }
                if (showNavigation) {
                    PhoneBottomBar(
                        selectedTab = selectedTab,
                        onSelectTab = onSelectTab,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvRailItem(
    entry: NavEntry,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val palette = BoostyTheme.palette
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = when {
            focused -> palette.surfaceFocused
            selected -> palette.accentSoft
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "railItemBg",
    )
    val tint = when {
        focused || selected -> palette.accent
        else -> palette.muted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(ControlShape)
            .background(bg)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) palette.focusRing else Color.Transparent,
                shape = ControlShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(entry.iconRes),
            contentDescription = stringResource(entry.labelRes),
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = stringResource(entry.labelRes),
                style = MaterialTheme.typography.titleSmall,
                color = if (focused || selected) palette.ink else palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PhoneBottomBar(
    selectedTab: AppTab?,
    onSelectTab: (AppTab) -> Unit,
) {
    val palette = BoostyTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.railBackground)
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.hairline),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoneNavEntries.forEach { entry ->
                val active = selectedTab == entry.tab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ControlShape)
                        .semantics { selected = active }
                        .clickable(role = Role.Tab) { onSelectTab(entry.tab) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 52.dp, height = 28.dp)
                            .clip(PillShape)
                            .background(if (active) palette.accentSoft else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(entry.iconRes),
                            contentDescription = null,
                            tint = if (active) palette.accent else palette.muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = stringResource(entry.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) palette.accent else palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

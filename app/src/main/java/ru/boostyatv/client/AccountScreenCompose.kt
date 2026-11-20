package ru.boostyatv.client

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AccountScreenView(
    profile: BoostyApi.Profile?,
    loadingProfile: Boolean,
    onRequestProfile: () -> Unit,
    onLogout: () -> Unit,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    var showLogoutConfirm by remember { mutableStateOf(false) }
    val logoutFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        onRequestProfile()
        if (isTv) runCatching { logoutFocus.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = stringResource(R.string.nav_account),
            showBack = false,
            onBack = {},
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isTv) 32.dp else 16.dp, vertical = 4.dp),
        ) {
            Column(
                modifier = if (isTv) Modifier.widthIn(max = 640.dp) else Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(palette.surface)
                        .border(1.dp, palette.hairline, CardShape)
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val avatarUrl = Post.https(profile?.avatar().orEmpty())
                    if (avatarUrl.isNotEmpty()) {
                        BoostyAvatar(url = avatarUrl, size = 62.dp)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(palette.surfaceElevated)
                                .border(1.dp, palette.hairline, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.app_icon),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        if (loadingProfile && profile == null) {
                            ShimmerBox(
                                modifier = Modifier.width(140.dp).height(18.dp),
                                shape = RoundedCornerShape(6.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            ShimmerBox(
                                modifier = Modifier.width(190.dp).height(13.dp),
                                shape = RoundedCornerShape(6.dp),
                            )
                        } else {
                            val displayName = profile?.name().orEmpty()
                                .ifEmpty { stringResource(R.string.app_name) }
                            val displaySubtitle = profile?.email().orEmpty()
                                .ifEmpty { stringResource(R.string.account_profile_signed_in) }
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleLarge,
                                color = palette.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = displaySubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                BoostySettingsSection(stringResource(R.string.account_section_session))

                BoostySettingsRow(
                    iconRes = R.drawable.ic_logout,
                    title = stringResource(R.string.account_logout),
                    subtitle = stringResource(R.string.account_logout_hint),
                    danger = true,
                    onClick = { showLogoutConfirm = true },
                    modifier = Modifier.focusRequester(logoutFocus),
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showLogoutConfirm) {
        BoostyConfirmDialog(
            title = stringResource(R.string.account_logout),
            message = stringResource(R.string.account_logout_confirm),
            confirmLabel = stringResource(R.string.account_logout_button),
            danger = true,
            onConfirm = {
                showLogoutConfirm = false
                onLogout()
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }
}

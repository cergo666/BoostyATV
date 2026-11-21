package ru.boostyatv.client

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.util.EnumMap

private const val DONATE_CLOUDTIPS_URL = "https://pay.cloudtips.ru/p/bd3b638a"
private const val DONATE_OZON_URL = "https://finance.ozon.ru/apps/sbp/ozonbankpay/01a1feb1-ce2e-7664-96eb-01f60e97e950"

@Composable
internal fun AboutScreenView(
    updateStatus: String,
    isUpdating: Boolean,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    val primaryFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (isTv) runCatching { primaryFocus.requestFocus() }
    }

    fun openExternal(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, url, Toast.LENGTH_LONG).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = stringResource(R.string.about_title),
            showBack = true,
            onBack = onBack,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isTv) 32.dp else 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = if (isTv) Modifier.widthIn(max = 760.dp) else Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(SheetShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    palette.accent.copy(alpha = 0.22f),
                                    palette.surface,
                                )
                            )
                        )
                        .border(1.dp, palette.hairline, SheetShape)
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.app_icon),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CardShape)
                            .border(1.dp, palette.hairline, CardShape),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge,
                        color = palette.ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(PillShape)
                            .background(palette.accentSoft)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.accent,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.about_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.muted,
                        textAlign = TextAlign.Center,
                    )
                }

                if (!BuildConfig.DEBUG) {
                    BoostyButton(
                        text = stringResource(R.string.about_check_updates),
                        iconRes = R.drawable.ic_refresh,
                        primary = true,
                        enabled = !isUpdating,
                        onClick = onCheckUpdates,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryFocus),
                    )
                    if (updateStatus.isNotEmpty()) {
                        Text(
                            text = updateStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }

                // Support the project section (T-Bank CloudTips + Ozon Bank QR)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(SheetShape)
                        .background(palette.surface.copy(alpha = 0.7f))
                        .border(1.dp, palette.hairline, SheetShape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(18.dp)
                                .clip(PillShape)
                                .background(palette.ink),
                        )
                        Text(
                            text = stringResource(R.string.about_support_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.ink,
                        )
                    }

                    if (isTv) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            DonateBannerCard(
                                title = stringResource(R.string.about_support_tbank),
                                subtitle = stringResource(R.string.about_support_subtitle),
                                actionLabel = stringResource(R.string.about_support_action),
                                url = DONATE_CLOUDTIPS_URL,
                                containerColor = Color(0xFFFFDD2D),
                                titleColor = Color(0xFF1A1A1A),
                                subtitleColor = Color(0xCC1A1A1A),
                                onClick = { openExternal(DONATE_CLOUDTIPS_URL) },
                                modifier = Modifier.weight(1f),
                            )
                            DonateBannerCard(
                                title = stringResource(R.string.about_support_ozon),
                                subtitle = stringResource(R.string.about_support_subtitle),
                                actionLabel = stringResource(R.string.about_support_action),
                                url = DONATE_OZON_URL,
                                containerColor = Color(0xFF005BFF),
                                titleColor = Color.White,
                                subtitleColor = Color(0xDDFFFFFF),
                                onClick = { openExternal(DONATE_OZON_URL) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        DonateBannerCard(
                            title = stringResource(R.string.about_support_tbank),
                            subtitle = stringResource(R.string.about_support_subtitle),
                            actionLabel = stringResource(R.string.about_support_action),
                            url = DONATE_CLOUDTIPS_URL,
                            containerColor = Color(0xFFFFDD2D),
                            titleColor = Color(0xFF1A1A1A),
                            subtitleColor = Color(0xCC1A1A1A),
                            onClick = { openExternal(DONATE_CLOUDTIPS_URL) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        DonateBannerCard(
                            title = stringResource(R.string.about_support_ozon),
                            subtitle = stringResource(R.string.about_support_subtitle),
                            actionLabel = stringResource(R.string.about_support_action),
                            url = DONATE_OZON_URL,
                            containerColor = Color(0xFF005BFF),
                            titleColor = Color.White,
                            subtitleColor = Color(0xDDFFFFFF),
                            onClick = { openExternal(DONATE_OZON_URL) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(palette.surface)
                        .border(1.dp, palette.hairline, CardShape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_author_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                    Text(
                        text = stringResource(R.string.about_author),
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.ink,
                    )
                }

                BoostyButton(
                    text = stringResource(R.string.about_github),
                    primary = false,
                    onClick = { openExternal(AppRelease.PROJECT) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (BuildConfig.DEBUG) Modifier.focusRequester(primaryFocus) else Modifier),
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DonateBannerCard(
    title: String,
    subtitle: String,
    actionLabel: String,
    url: String,
    containerColor: Color,
    titleColor: Color,
    subtitleColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTv = BoostyTheme.isTv
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && isTv) 1.03f else 1f,
        animationSpec = tween(130, easing = FastOutSlowInEasing),
        label = "donateScale",
    )
    val qrBitmap = remember(url) { generateDonateQrBitmap(url, 320) }
    val cardShape = RoundedCornerShape(18.dp)
    val qrPlateShape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .then(
                if (focused && isTv) {
                    Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                } else {
                    Modifier
                }
            )
            .clip(cardShape)
            .background(containerColor)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = cardShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (qrBitmap != null) {
            Box(
                modifier = Modifier
                    .size(if (isTv) 104.dp else 92.dp)
                    .clip(qrPlateShape)
                    .background(Color(0xFFF3F3F3))
                    .padding(7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = titleColor,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = titleColor,
            )
        }
    }
}

private fun generateDonateQrBitmap(content: String, sizePx: Int): Bitmap? {
    return try {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.MARGIN] = 0
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix.get(x, y)) AndroidColor.BLACK else 0xFFF3F3F3.toInt()
            }
        }
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    } catch (_: Exception) {
        null
    }
}

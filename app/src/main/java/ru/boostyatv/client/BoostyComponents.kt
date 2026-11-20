package ru.boostyatv.client

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex

@Composable
internal fun AmbientBackdrop(
    modifier: Modifier = Modifier,
    oled: Boolean = false,
) {
    if (oled) return
    val palette = BoostyTheme.palette
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        palette.accent.copy(alpha = 0.085f),
                        Color(0xFF281F3A).copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                    center = Offset(380f, -60f),
                    radius = 960f,
                )
            )
    )
}

@Composable
internal fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
) {
    val palette = BoostyTheme.palette
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerShift",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        palette.surface,
                        palette.surfaceElevated,
                        palette.surface,
                    ),
                    start = Offset(shift, 0f),
                    end = Offset(shift + 320f, 180f),
                )
            )
    )
}

@Composable
internal fun BoostyArtworkImage(
    url: String,
    size: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    priority: Boolean = false,
    @DrawableRes placeholderIcon: Int? = null,
) {
    val artwork = LocalArtwork.current
    val palette = BoostyTheme.palette
    var bitmap by remember(url, size) { mutableStateOf<Bitmap?>(artwork?.cached(url, size)) }
    var animateIn by remember(url, size) { mutableStateOf(bitmap == null) }

    LaunchedEffect(url, size, artwork) {
        if (url.isEmpty() || artwork == null) {
            bitmap = null
            return@LaunchedEffect
        }
        val cached = artwork.cached(url, size)
        if (cached != null) {
            bitmap = cached
            if (size == Artwork.PREVIEW) {
                artwork.loadBitmap(url, size, priority) { loaded ->
                    bitmap = loaded
                }
            }
            return@LaunchedEffect
        }
        artwork.loadBitmap(url, size, priority) { loaded ->
            bitmap = loaded
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (bitmap != null) 1f else 0f,
        animationSpec = tween(durationMillis = if (animateIn) 180 else 0, easing = FastOutSlowInEasing),
        label = "artworkFade",
    )

    Box(
        modifier = modifier.background(palette.surfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null && placeholderIcon != null) {
            Icon(
                painter = painterResource(placeholderIcon),
                contentDescription = null,
                tint = palette.muted.copy(alpha = 0.45f),
                modifier = Modifier.size(28.dp),
            )
        }
        bitmap?.let { loaded ->
            val imageBitmap = remember(loaded) { loaded.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(alpha),
            )
        }
    }
}

@Composable
internal fun BoostyAvatar(
    url: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = BoostyTheme.palette
    BoostyArtworkImage(
        url = url,
        size = Artwork.AVATAR,
        contentDescription = null,
        placeholderIcon = R.drawable.ic_person,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, palette.hairline, CircleShape),
    )
}

@Composable
internal fun ScreenToolbar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (isTv) 32.dp else 16.dp,
                end = if (isTv) 32.dp else 14.dp,
                top = if (isTv) 18.dp else 12.dp,
                bottom = if (isTv) 12.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            BoostyIconButton(
                iconRes = R.drawable.ic_back,
                contentDescription = stringResource(R.string.nav_back),
                onClick = onBack,
                containerColor = palette.surfaceElevated.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (isTv) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Composable
internal fun BoostyIconButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp,
    containerColor: Color = BoostyTheme.palette.surface,
    contentColor: Color = BoostyTheme.palette.ink,
    enabled: Boolean = true,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && isTv) 1.08f else 1f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "iconBtnScale",
    )
    val bg by animateColorAsState(
        targetValue = if (focused) palette.surfaceFocused else containerColor,
        animationSpec = tween(140),
        label = "iconBtnBg",
    )
    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) palette.focusRing else palette.hairline,
                shape = CircleShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (focused) palette.accent else contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
internal fun BoostyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    @DrawableRes iconRes: Int? = null,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && isTv) 1.035f else 1f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "btnScale",
    )
    val bgColor = when {
        primary && focused -> Color(0xFFFF936B)
        primary -> palette.accent
        focused -> palette.surfaceFocused
        else -> palette.surfaceElevated
    }
    val fgColor = if (primary) palette.accentOnColor else palette.ink
    val borderColor = when {
        focused -> palette.focusRing
        primary -> Color.Transparent
        else -> palette.hairline
    }
    Row(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = if (isTv) 46.dp else 44.dp)
            .clip(PillShape)
            .background(if (enabled) bgColor else bgColor.copy(alpha = 0.5f))
            .border(if (focused) 2.dp else 1.dp, borderColor, PillShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = fgColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = fgColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun BoostyActionChip(
    text: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && isTv) 1.04f else 1f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "chipScale",
    )
    val bgColor = when {
        active -> palette.accentSoft
        focused -> palette.surfaceFocused
        else -> palette.surface
    }
    val fgColor = if (active) palette.accent else palette.ink
    val borderColor = when {
        focused -> palette.focusRing
        active -> palette.accent.copy(alpha = 0.55f)
        else -> palette.hairline
    }
    Row(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(38.dp)
            .clip(PillShape)
            .background(bgColor)
            .border(if (focused) 2.dp else 1.dp, borderColor, PillShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (active || focused) palette.accent else palette.muted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = fgColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun BoostySegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BoostyTheme.palette
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(palette.surface)
            .border(1.dp, palette.hairline, PillShape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            var focused by remember { mutableStateOf(false) }
            val bgColor by animateColorAsState(
                targetValue = when {
                    selected -> palette.accent
                    focused -> palette.surfaceFocused
                    else -> Color.Transparent
                },
                animationSpec = tween(140),
                label = "segBg",
            )
            val textColor = when {
                selected -> palette.accentOnColor
                else -> palette.ink
            }
            val borderColor = when {
                focused && selected -> palette.focusRing
                focused -> palette.accent
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(PillShape)
                    .background(bgColor)
                    .border(if (focused) 2.dp else 0.dp, borderColor, PillShape)
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun BoostySwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = BoostyTheme.palette
    val trackColor by animateColorAsState(
        targetValue = if (checked) palette.accent else palette.surfaceElevated,
        animationSpec = tween(160),
        label = "switchTrack",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "switchThumb",
    )
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(PillShape)
            .background(trackColor)
            .border(1.dp, if (checked) Color.Transparent else palette.hairline, PillShape)
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) palette.accentOnColor else palette.ink),
        )
    }
}

@Composable
internal fun BoostySettingsSection(
    title: String,
    modifier: Modifier = Modifier,
) {
    val palette = BoostyTheme.palette
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = palette.muted,
        modifier = modifier.padding(start = 6.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
internal fun BoostySettingsRow(
    @DrawableRes iconRes: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && isTv) 1.02f else 1f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "settingsRowScale",
    )
    val bg by animateColorAsState(
        targetValue = if (focused) palette.surfaceFocused else palette.surface,
        animationSpec = tween(140),
        label = "settingsRowBg",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = 68.dp)
            .clip(CardShape)
            .background(bg)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) palette.focusRing else palette.hairline,
                shape = CardShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(ControlShape)
                .background(if (danger) palette.danger.copy(alpha = 0.14f) else palette.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = when {
                    danger -> palette.danger
                    focused -> palette.accent
                    else -> palette.ink
                },
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (danger) palette.danger else palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            trailing()
        } else {
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (focused) palette.accent else palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun BoostyOverlaySheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = if (isTv) Alignment.CenterEnd else Alignment.Center,
        ) {
            val containerShape = if (isTv) {
                RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
            } else {
                SheetShape
            }
            Surface(
                shape = containerShape,
                color = palette.surface,
                border = BorderStroke(1.dp, palette.hairline),
                modifier = if (isTv) {
                    Modifier
                        .fillMaxHeight()
                        .width(380.dp)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 32.dp)
                        .widthIn(max = 460.dp)
                }.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = if (isTv) 24.dp else 20.dp,
                        vertical = if (isTv) 28.dp else 20.dp,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = palette.ink,
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.muted,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        if (!isTv) {
                            Spacer(Modifier.width(8.dp))
                            BoostyIconButton(
                                iconRes = R.drawable.ic_close,
                                contentDescription = stringResource(R.string.nav_close),
                                onClick = onDismiss,
                                size = 36.dp,
                                iconSize = 18.dp,
                                containerColor = palette.surfaceElevated,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = isTv)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = content,
                    )
                    if (footer != null) {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                            content = footer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BoostySheetOption(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    subtitle: String? = null,
    @DrawableRes iconRes: Int? = null,
    trailingText: String? = null,
    danger: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val palette = BoostyTheme.palette
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = when {
            focused -> palette.surfaceFocused
            selected -> palette.accentSoft
            else -> palette.surfaceElevated.copy(alpha = 0.72f)
        },
        animationSpec = tween(120),
        label = "sheetOptBg",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .heightIn(min = 48.dp)
            .clip(ControlShape)
            .background(bg)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> palette.focusRing
                    selected -> palette.accent.copy(alpha = 0.45f)
                    else -> Color.Transparent
                },
                shape = ControlShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = when {
                    danger -> palette.danger
                    selected || focused -> palette.accent
                    else -> palette.muted
                },
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = when {
                    danger -> palette.danger
                    selected -> palette.accent
                    else -> palette.ink
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!trailingText.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun BoostyConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = stringResource(R.string.cancel),
    danger: Boolean = false,
    errorText: String? = null,
    busy: Boolean = false,
) {
    val palette = BoostyTheme.palette
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    BoostyOverlaySheet(
        title = title,
        onDismiss = onDismiss,
        footer = {
            if (dismissLabel != null) {
                BoostyButton(
                    text = dismissLabel,
                    onClick = onDismiss,
                    primary = false,
                    enabled = !busy,
                )
            }
            BoostyButton(
                text = confirmLabel,
                onClick = onConfirm,
                primary = !danger,
                enabled = !busy,
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.ink.copy(alpha = 0.9f),
        )
        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = palette.danger,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
internal fun BoostyTextInputDialog(
    title: String,
    initialValue: String,
    placeholder: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onReset: (() -> Unit)? = null,
    errorText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val palette = BoostyTheme.palette
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    BoostyOverlaySheet(
        title = title,
        onDismiss = onDismiss,
        footer = {
            if (onReset != null) {
                BoostyButton(
                    text = stringResource(R.string.feed_tags_reset),
                    onClick = onReset,
                    primary = false,
                )
            }
            BoostyButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
                primary = false,
            )
            BoostyButton(
                text = confirmLabel,
                onClick = { onConfirm(text.trim()) },
                primary = true,
            )
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text(placeholder, color = palette.muted) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.ink),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onConfirm(text.trim()) }),
            shape = ControlShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.accent,
                unfocusedBorderColor = palette.hairline,
                focusedContainerColor = palette.background,
                unfocusedContainerColor = palette.background,
                cursorColor = palette.accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = palette.danger,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

internal fun formatDurationShort(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

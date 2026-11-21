package ru.boostyatv.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun UpdateDialogs(
    state: UpdateController.UiState,
    onAccept: () -> Unit,
    onDismissOffer: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val palette = BoostyTheme.palette
    val offer = state.offer()
    if (offer != null) {
        val rawNotes = offer.notes().ifBlank { stringResource(R.string.about_no_changelog) }
        BoostyOverlaySheet(
            title = stringResource(R.string.about_update_available, offer.version()),
            onDismiss = onDismissOffer,
            footer = {
                BoostyButton(
                    text = stringResource(R.string.update_install_later),
                    primary = false,
                    onClick = onDismissOffer,
                )
                BoostyButton(
                    text = stringResource(R.string.update_install),
                    primary = true,
                    onClick = onAccept,
                )
            },
        ) {
            ChangelogMarkdown(rawNotes)
        }
    } else if (state.downloading()) {
        BoostyOverlaySheet(
            title = stringResource(R.string.update_download_title),
            onDismiss = onCancelDownload,
            footer = {
                BoostyButton(
                    text = stringResource(R.string.cancel),
                    primary = false,
                    onClick = onCancelDownload,
                )
            },
        ) {
            val percent = state.downloadPercent()
            if (percent >= 0) {
                LinearProgressIndicator(
                    progress = { (percent / 100f).coerceIn(0f, 1f) },
                    color = palette.accent,
                    trackColor = palette.surfaceElevated,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.update_download_progress, percent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                )
            } else {
                LinearProgressIndicator(
                    color = palette.accent,
                    trackColor = palette.surfaceElevated,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
        }
    }
}

internal sealed interface ChangelogLine {
    data class Heading(val level: Int, val text: String) : ChangelogLine
    data class Bullet(val nested: Boolean, val text: String) : ChangelogLine
    data class Paragraph(val text: String) : ChangelogLine
}

internal fun parseChangelogLines(markdown: String): List<ChangelogLine> {
    val result = ArrayList<ChangelogLine>()
    for (raw in markdown.replace("\r\n", "\n").lines()) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) continue
        when {
            trimmed.startsWith("### ") -> result.add(ChangelogLine.Heading(3, trimmed.removePrefix("### ").trim()))
            trimmed.startsWith("## ") -> result.add(ChangelogLine.Heading(2, trimmed.removePrefix("## ").trim()))
            trimmed.startsWith("# ") -> result.add(ChangelogLine.Heading(1, trimmed.removePrefix("# ").trim()))
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                val indent = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                result.add(ChangelogLine.Bullet(nested = indent >= 2, text = trimmed.substring(2).trim()))
            }
            else -> result.add(ChangelogLine.Paragraph(trimmed))
        }
    }
    return result
}

@Composable
private fun ChangelogMarkdown(markdown: String) {
    val palette = BoostyTheme.palette
    val lines = remember(markdown) { parseChangelogLines(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { idx, line ->
            when (line) {
                is ChangelogLine.Heading -> {
                    Text(
                        text = formatInlineMarkdown(line.text, palette),
                        style = if (line.level <= 2) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleSmall
                        },
                        color = palette.ink,
                        modifier = Modifier.padding(top = if (idx == 0) 0.dp else 8.dp),
                    )
                }
                is ChangelogLine.Bullet -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = if (line.nested) 16.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (line.nested) "◦" else "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.accent,
                        )
                        Text(
                            text = formatInlineMarkdown(line.text, palette),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.muted,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is ChangelogLine.Paragraph -> {
                    Text(
                        text = formatInlineMarkdown(line.text, palette),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.muted,
                    )
                }
            }
        }
    }
}

private fun formatInlineMarkdown(text: String, palette: BoostyPalette): AnnotatedString {
    val boldStyle = SpanStyle(fontWeight = FontWeight.SemiBold, color = palette.ink)
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        color = palette.ink,
        background = palette.surfaceElevated,
    )
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    pushStyle(boldStyle)
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                    continue
                }
            }
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    pushStyle(codeStyle)
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                    continue
                }
            }
            append(text[i])
            i++
        }
    }
}

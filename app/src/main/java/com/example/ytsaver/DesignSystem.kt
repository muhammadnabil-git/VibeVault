package com.example.ytsaver

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * VibeVault design system: ONE set of spacing, corner radii and components that every screen builds on, so the
 * app looks the same everywhere. Colours always come from MaterialTheme.colorScheme (the active theme).
 */
object Vv {
    // spacing scale
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    // corner radii
    val rSmall = 10.dp
    val rMedium = 16.dp
    val rLarge = 22.dp

    /** Minimum touch target (accessibility guideline: 48 dp). */
    val touch = 48.dp

    /** Widest a screen's content gets on tablets and in landscape; it is centred beyond this. */
    val maxContentWidth = 840.dp
}

/** Type scale. Use these instead of ad-hoc font sizes. */
object VvText {
    val title = 22.sp
    val section = 18.sp
    val body = 14.sp
    val caption = 12.sp
}

/** The VibeVault app mark. */
@Composable
fun VvLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.vv_logo_icon),
        contentDescription = "VibeVault",
        modifier = modifier.size(size),
    )
}

/** A rounded surface. With [onClick] the whole card is tappable (and announced as a button). */
@Composable
fun VvCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = Vv.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Vv.rMedium)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier.clip(shape).then(
            if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
        ),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** "Section title ........ Action" line used above every group of content. */
@Composable
fun VvSectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, fontSize = VvText.section, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.defaultMinSize(minHeight = Vv.touch)) {
                Text(actionLabel, color = MaterialTheme.colorScheme.primary, fontSize = VvText.caption)
            }
        }
    }
}

@Composable
fun VvPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(Vv.rMedium),
        modifier = modifier.defaultMinSize(minHeight = Vv.touch),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun VvSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(Vv.rMedium),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        modifier = modifier.defaultMinSize(minHeight = Vv.touch),
    ) { Text(text) }
}

/** A pill used for filters and categories. */
@Composable
fun VvChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) scheme.primary else scheme.surfaceVariant,
        contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
        modifier = modifier.defaultMinSize(minHeight = 36.dp).clip(RoundedCornerShape(50))
            .clickable(role = Role.Tab, onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = Vv.lg, vertical = Vv.sm), contentAlignment = Alignment.Center) {
            Text(label, fontSize = VvText.caption, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
    }
}

/** A round icon-only button with a spoken label (screen readers) and a 48 dp touch area. */
@Composable
fun VvIconTile(iconRes: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Column(
        modifier.clip(RoundedCornerShape(Vv.rMedium)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.padding(vertical = Vv.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Vv.xs),
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Sym(iconRes, 24.dp, tint = accent) }
        Text(label, fontSize = VvText.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Shown instead of an empty list: says what this place is for and what to do next. */
@Composable
fun VvEmptyState(title: String, message: String, modifier: Modifier = Modifier, iconRes: Int? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(Vv.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Vv.sm),
    ) {
        if (iconRes != null) Sym(iconRes, 40.dp, tint = Muted) else VvLogo(56.dp, Modifier.alpha(0.6f))
        Text(title, fontSize = VvText.section, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(message, color = Muted, fontSize = VvText.body, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Vv.xs))
            VvPrimaryButton(actionLabel, onAction)
        }
    }
}

@Composable
fun VvLoading(modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier.fillMaxWidth().padding(Vv.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Vv.sm)) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        if (label != null) Text(label, color = Muted, fontSize = VvText.caption)
    }
}

@Composable
fun VvErrorState(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(Vv.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Vv.sm)) {
        Text(tr("Something went wrong", "حدث خطأ ما"), fontSize = VvText.section, fontWeight = FontWeight.SemiBold)
        Text(message, color = Muted, fontSize = VvText.body, textAlign = TextAlign.Center)
        if (onRetry != null) VvSecondaryButton(tr("Try again", "حاول مرة أخرى"), onRetry)
    }
}

/** A softly pulsing placeholder for content that is still loading (cheaper than a shimmer gradient). */
@Composable
fun VvSkeleton(modifier: Modifier = Modifier, radius: Dp = Vv.rSmall) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Box(modifier.clip(RoundedCornerShape(radius)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = a)))
}

package app.focal.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Concentric sheet & surface geometry tokens modeled after Lens and YAB:
 * - Outer sheet radius (`32.dp`) = inner card radius (`20.dp`) + sheet padding (`12.dp`..`14.dp`).
 * - Borderless tonal inner cards (`FocalGroupedCard`) with `0.5.dp` inset dividers (`FocalHairlineDivider`)
 *   instead of stacked `1.dp` wireframe outlines.
 */
object FocalGeometry {
    val SheetOuterShape = RoundedCornerShape(32.dp)
    val CardShape = RoundedCornerShape(20.dp)
    val TileShape = RoundedCornerShape(18.dp)
    val InputPillShape = RoundedCornerShape(20.dp)
}

@Composable
fun FocalSheetGrabber(modifier: Modifier = Modifier) {
    val colors = FocalTheme.colors
    Box(
        modifier = modifier
            .size(width = 36.dp, height = 4.dp)
            .clip(CircleShape)
            .background(colors.inkPrimary.copy(alpha = 0.18f)),
    )
}

/**
 * Borderless tonal grouped container for related menu/settings/bookmark rows.
 */
@Composable
fun FocalGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FocalTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(FocalGeometry.CardShape)
            .background(colors.surfacePillSubtle),
        content = content,
    )
}

/**
 * `0.5.dp` optical hairline divider aligned to row labels inside [FocalGroupedCard].
 */
@Composable
fun FocalHairlineDivider(
    insetStart: Dp = 50.dp,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val dividerColor = if (colors.isDarkGlass || colors.isPrivateAperture) {
        Color.White.copy(alpha = 0.08f)
    } else {
        Color.Black.copy(alpha = 0.07f)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = insetStart)
            .height(0.5.dp)
            .background(dividerColor),
    )
}

/**
 * Sleek custom `44.dp x 26.dp` capsule switch replacing chunky Material3 outlined switches.
 */
@Composable
fun FocalSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val inactiveTrack = if (colors.isDarkGlass || colors.isPrivateAperture) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.Black.copy(alpha = 0.11f)
    }
    val trackColorState = animateColorAsState(
        targetValue = if (checked) colors.surfacePillActive else inactiveTrack,
        animationSpec = FocalMotion.smartPillSpring(),
        label = "FocalSwitchTrack",
    )
    val thumbColorState = animateColorAsState(
        targetValue = if (checked) {
            colors.inkOnPillActive
        } else if (colors.isDarkGlass || colors.isPrivateAperture) {
            Color.White.copy(alpha = 0.88f)
        } else {
            Color.White
        },
        animationSpec = FocalMotion.smartPillSpring(),
        label = "FocalSwitchThumb",
    )
    val targetThumbOffsetPx = with(density) { (if (checked) 20.dp else 2.dp).toPx() }
    val thumbOffsetPxState = animateFloatAsState(
        targetValue = targetThumbOffsetPx,
        animationSpec = FocalMotion.smartPillSpring(),
        label = "FocalSwitchOffset",
    )

    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .drawBehind { drawRect(trackColorState.value) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val thumbOffsetPx = thumbOffsetPxState.value
                    translationX = if (isRtl) -thumbOffsetPx else thumbOffsetPx
                }
                .size(22.dp)
                .clip(CircleShape)
                .drawBehind { drawRect(thumbColorState.value) },
        )
    }
}

/**
 * Quiet, tactile single-line (or optional compact subtitle) row inside [FocalGroupedCard].
 */
@Composable
fun FocalMenuRow(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    trailingLabel: String? = null,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val primaryInk = if (enabled) colors.inkPrimary else colors.inkFaint
    val iconTint = if (enabled) colors.inkSecondary else colors.inkFaint

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leadingContent != null -> {
                leadingContent()
                Spacer(Modifier.width(14.dp))
            }
            icon != null -> {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = typography.bodyCompact.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                ),
                color = primaryInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = typography.captionMetadata,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!trailingLabel.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailingLabel,
                style = typography.controlPillLabel,
                color = colors.inkSecondary,
                maxLines = 1,
            )
        }

        if (trailingContent != null) {
            Spacer(Modifier.width(10.dp))
            trailingContent()
        }

        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = FocalGlyphs.ChevronRight,
                contentDescription = null,
                tint = colors.inkFaint,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * Borderless tonal quick-action squircle tile (`Share`, `Pin`, `Bookmark`, `Reader`).
 */
@Composable
fun FocalActionTile(
    label: String,
    icon: ImageVector,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val bg = if (active) colors.surfacePillActive else colors.surfacePillSubtle
    val iconColor = when {
        !enabled -> colors.inkFaint
        active -> colors.inkOnPillActive
        else -> colors.inkPrimary
    }
    val labelColor = when {
        !enabled -> colors.inkFaint
        active -> colors.inkOnPillActive
        else -> colors.inkSecondary
    }

    Column(
        modifier = modifier
            .clip(FocalGeometry.TileShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(21.dp),
        )
        Text(
            text = label,
            style = typography.captionMetadata,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Borderless tonal squircle favicon shortcut tile used in `SearchSheet` and `SpacesSheet`.
 */
@Composable
fun FocalFaviconTile(
    title: String,
    url: String,
    allowNetworkFetch: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val displayLabel = if (title.equals("HackerNews", ignoreCase = true)) "HN" else title

    Column(
        modifier = modifier
            .clip(FocalGeometry.TileShape)
            .background(colors.surfacePillSubtle)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SiteFavicon(
            urlOrDomain = url,
            allowNetworkFetch = allowNetworkFetch,
            size = 28.dp,
            cornerRadius = 8.dp,
        )
        Text(
            text = displayLabel,
            style = typography.captionMetadata,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

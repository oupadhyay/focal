package app.focal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.focal.design.FocalGeometry
import app.focal.design.FocalGlyphs
import app.focal.design.FocalTheme
import app.focal.design.consumeClicks
import app.focal.design.focalSheetEntrance
import app.focal.design.shaders.SmokedGlassBox

/**
 * Smoked Glass site permission confirmation sheet for WebView Camera, Microphone, Location,
 * and Protected Media requests.
 */
@Composable
fun SitePermissionPromptSheet(
    origin: String,
    resources: List<String>,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val sheetTint = rememberUpdatedState(colors.effectiveSheetGlass)
    val permissionSummary = remember(resources) {
        resources.distinct().joinToString(" & ").ifBlank { "Device permission" }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDeny,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SmokedGlassBox(
            shape = FocalGeometry.SheetOuterShape,
            glassTintState = sheetTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
                .focalSheetEntrance(riseDp = 18.dp)
                .shadow(
                    elevation = 24.dp,
                    shape = FocalGeometry.SheetOuterShape,
                    ambientColor = Color.Black.copy(alpha = 0.45f),
                    spotColor = Color.Black.copy(alpha = 0.55f),
                )
                .consumeClicks(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(colors.surfacePillSubtle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = FocalGlyphs.Shield,
                            contentDescription = null,
                            tint = colors.inkPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow $permissionSummary?",
                            style = typography.sheetTitle,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = origin.ifBlank { "This website" },
                            style = typography.captionMetadata,
                            color = colors.inkSecondary,
                        )
                    }
                }

                Text(
                    text = "Only grant access if you trust this site. Permission applies to this session.",
                    style = typography.captionMetadata,
                    color = colors.inkSecondary,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(colors.surfacePillSubtle)
                            .clickable(onClick = onDeny)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Deny",
                            style = typography.controlPillLabel,
                            color = colors.inkPrimary,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(colors.surfacePillActive)
                            .clickable(onClick = onAllow)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Allow",
                            style = typography.controlPillLabel,
                            color = colors.inkOnPillActive,
                        )
                    }
                }
            }
        }
    }
}

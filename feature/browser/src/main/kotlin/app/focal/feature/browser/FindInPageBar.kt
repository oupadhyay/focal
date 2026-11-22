package app.focal.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focal.design.FocalGlyphs
import app.focal.design.FocalTheme
import app.focal.design.consumeClicks
import app.focal.design.focalSheetEntrance
import app.focal.design.shaders.SmokedGlassBox
import app.focal.engine.api.FindInPageState

/**
 * Floating Achromatic Smoked Glass capsule for in-page text search, docked cleanly above the
 * software keyboard (`navigationBarsPadding().imePadding()`).
 *
 * Displays live tabular match counters (`tnum` via [FocalTheme.typography.tabBadgeNumeral]) and
 * crisp circular navigation controls without resizing the underlying `WebEngineSurface`.
 */
@Composable
fun FindInPageBar(
    findState: FindInPageState,
    onQueryChange: (String) -> Unit,
    onFindPrevious: () -> Unit,
    onFindNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val barTintState = rememberUpdatedState(colors.effectiveSheetGlass)

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = findState.query,
                selection = TextRange(findState.query.length),
            ),
        )
    }
    var lastNonEmptyExternalQuery by remember { mutableStateOf(findState.query) }

    // Only clear local input when the engine explicitly resets a previously non-empty find query
    // (e.g., on page navigation), never clobbering fast in-flight user keystrokes.
    LaunchedEffect(findState.query) {
        if (findState.query.isNotEmpty()) {
            lastNonEmptyExternalQuery = findState.query
        } else if (lastNonEmptyExternalQuery.isNotEmpty()) {
            lastNonEmptyExternalQuery = ""
            if (fieldValue.text.isNotEmpty()) {
                fieldValue = TextFieldValue("")
            }
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    val hasMatches = findState.numberOfMatches > 0
    val matchCounterText = when {
        hasMatches -> {
            val activeOrdinal = (findState.activeMatchOrdinal + 1).coerceIn(1, findState.numberOfMatches)
            "$activeOrdinal/${findState.numberOfMatches}"
        }
        fieldValue.text.isNotBlank() && findState.query.isNotBlank() && findState.isDoneCounting && findState.numberOfMatches == 0 -> "0/0"
        else -> ""
    }

    SmokedGlassBox(
        shape = CircleShape,
        glassTintState = barTintState,
        borderSpecular = colors.borderSpecular,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .focalSheetEntrance(riseDp = 12.dp)
            .shadow(
                elevation = 18.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.40f),
                spotColor = Color.Black.copy(alpha = 0.50f),
            )
            .testTag("find_in_page_bar")
            .consumeClicks(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(start = 14.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = FocalGlyphs.Search,
                contentDescription = null,
                tint = colors.inkSecondary,
                modifier = Modifier.size(16.dp),
            )

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (fieldValue.text.isEmpty()) {
                    Text(
                        text = "Find in page",
                        style = typography.urlDomainPill,
                        color = colors.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                BasicTextField(
                    value = fieldValue,
                    onValueChange = { next ->
                        val textChanged = next.text != fieldValue.text
                        fieldValue = next
                        if (textChanged) {
                            onQueryChange(next.text)
                        }
                    },
                    singleLine = true,
                    textStyle = typography.urlDomainPill.copy(color = colors.inkPrimary),
                    cursorBrush = SolidColor(colors.inkPrimary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { onFindNext() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("find_in_page_input")
                        .focusRequester(focusRequester),
                )
            }

            if (matchCounterText.isNotEmpty()) {
                Text(
                    text = matchCounterText,
                    style = typography.tabBadgeNumeral,
                    color = if (hasMatches) colors.inkSecondary else colors.inkFaint,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            FindBarCircleButton(
                icon = FocalGlyphs.ChevronUp,
                contentDescription = "Previous match",
                tint = if (hasMatches) colors.inkPrimary else colors.inkFaint,
                onClick = onFindPrevious,
            )

            FindBarCircleButton(
                icon = FocalGlyphs.ChevronDown,
                contentDescription = "Next match",
                tint = if (hasMatches) colors.inkPrimary else colors.inkFaint,
                onClick = onFindNext,
            )

            FindBarCircleButton(
                icon = FocalGlyphs.Close,
                contentDescription = "Close find",
                tint = colors.inkSecondary,
                onClick = onClose,
            )
        }
    }
}

@Composable
private fun FindBarCircleButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val colors = FocalTheme.colors
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(colors.surfacePillSubtle)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
    }
}

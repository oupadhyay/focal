package app.focal.design

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * OpenType feature flags enforced on Allo Crisp focus & URL UI:
 * - `tnum`: Tabular numerals (zero horizontal jitter on tab counters, timers, speed badges)
 * - `calt`: Contextual alternates
 * - `ss01`: Stylistic Set 1 disambiguating `I / l / 1` against homograph spoofing
 * - `zero`: Slashed/dotted `0` vs `O` disambiguation in URLs and SAS codes
 */
const val ALLO_CRISP_OPENTYPE_FEATURES = "'tnum' 1, 'calt' 1, 'ss01' 1, 'zero' 1"

@OptIn(ExperimentalTextApi::class)
val AlloCrispDisplay = FontFamily(
    Font(
        resId = R.font.allo_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("ROND", 0f),
            FontVariation.Setting("opsz", 28f),
            FontVariation.weight(500),
        ),
    ),
    Font(
        resId = R.font.allo_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("ROND", 0f),
            FontVariation.Setting("opsz", 28f),
            FontVariation.weight(600),
        ),
    ),
    Font(
        resId = R.font.allo_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("ROND", 0f),
            FontVariation.Setting("opsz", 28f),
            FontVariation.weight(700),
        ),
    ),
)

@OptIn(ExperimentalTextApi::class)
val AlloCrispFocusUi = FontFamily(
    Font(
        resId = R.font.allo_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("ROND", 0f),
            FontVariation.Setting("opsz", 14f),
            FontVariation.weight(400),
        ),
    ),
    Font(
        resId = R.font.allo_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("ROND", 0f),
            FontVariation.Setting("opsz", 14f),
            FontVariation.weight(500),
        ),
    ),
    Font(
        resId = R.font.allo_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("ROND", 0f),
            FontVariation.Setting("opsz", 14f),
            FontVariation.weight(600),
        ),
    ),
)

@OptIn(ExperimentalTextApi::class)
val SwitzerBody = FontFamily(
    Font(
        resId = R.font.switzer_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(400),
        ),
    ),
    Font(
        resId = R.font.switzer_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(500),
        ),
    ),
)

@Immutable
data class FocalTypeScale(
    val displayHeadline: TextStyle,
    val sheetTitle: TextStyle,
    val cardTitle: TextStyle,
    val focusInput: TextStyle,
    val urlDomainPill: TextStyle,
    val tabBadgeNumeral: TextStyle,
    val controlPillLabel: TextStyle,
    val bodyReading: TextStyle,
    val bodyCompact: TextStyle,
    val captionMetadata: TextStyle,
)

val DefaultFocalTypeScale = FocalTypeScale(
    displayHeadline = TextStyle(
        fontFamily = AlloCrispDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = ALLO_CRISP_OPENTYPE_FEATURES,
    ),
    sheetTitle = TextStyle(
        fontFamily = AlloCrispDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.25).sp,
        fontFeatureSettings = ALLO_CRISP_OPENTYPE_FEATURES,
    ),
    cardTitle = TextStyle(
        fontFamily = AlloCrispDisplay,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.15).sp,
        fontFeatureSettings = ALLO_CRISP_OPENTYPE_FEATURES,
    ),
    focusInput = TextStyle(
        fontFamily = AlloCrispFocusUi,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = ALLO_CRISP_OPENTYPE_FEATURES,
    ),
    urlDomainPill = TextStyle(
        fontFamily = AlloCrispFocusUi,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.1).sp,
        fontFeatureSettings = ALLO_CRISP_OPENTYPE_FEATURES,
    ),
    tabBadgeNumeral = TextStyle(
        fontFamily = AlloCrispFocusUi,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        fontFeatureSettings = ALLO_CRISP_OPENTYPE_FEATURES,
    ),
    controlPillLabel = TextStyle(
        fontFamily = AlloCrispFocusUi,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        letterSpacing = (-0.05).sp,
        fontFeatureSettings = ALLO_CRISP_OPENTYPE_FEATURES,
    ),
    bodyReading = TextStyle(
        fontFamily = SwitzerBody,
        fontWeight = FontWeight.Normal,
        fontSize = 16.5.sp,
        lineHeight = 26.sp,
    ),
    bodyCompact = TextStyle(
        fontFamily = SwitzerBody,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = (-0.05).sp,
    ),
    captionMetadata = TextStyle(
        fontFamily = SwitzerBody,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.sp,
    ),
)

internal val FocalMaterialTypography = Typography(
    headlineMedium = DefaultFocalTypeScale.displayHeadline,
    titleMedium = DefaultFocalTypeScale.sheetTitle,
    titleSmall = DefaultFocalTypeScale.cardTitle,
    bodyLarge = DefaultFocalTypeScale.bodyReading,
    bodyMedium = DefaultFocalTypeScale.bodyCompact,
    labelLarge = DefaultFocalTypeScale.urlDomainPill,
    labelMedium = DefaultFocalTypeScale.controlPillLabel,
    labelSmall = DefaultFocalTypeScale.captionMetadata,
)

package app.focal.feature.browser

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.focal.adblock.AdblockController
import app.focal.adblock.AdblockWebResponses
import app.focal.adblock.CleanedUrlResult
import app.focal.design.FocalActionTile
import app.focal.design.FocalGeometry
import app.focal.design.FocalGlyphs
import app.focal.design.FocalGroupedCard
import app.focal.design.FocalHairlineDivider
import app.focal.design.FocalMenuRow
import app.focal.design.FocalMotion
import app.focal.design.FocalSheetGrabber
import app.focal.design.FocalTheme
import app.focal.design.GestureLeaseOwner
import app.focal.design.GestureLeaseToken
import app.focal.design.LocalGestureLeaseController
import app.focal.design.consumeClicks
import app.focal.design.focalSheetEntrance
import app.focal.design.shaders.SmokedGlassBox
import app.focal.engine.webview.OAuthUrlDetector
import app.focal.engine.webview.WebViewSession
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLDecoder
import kotlin.math.abs

/**
 * Screen 07 · Rich Long-Press Context & OAuth-Safe `LinkPeekSheet`.
 *
 * - **OAuth / Sign-in Links** (detected via [OAuthUrlDetector.isAuthOrOAuthUrl]): skips
 *   instantiating the isolated preview `WebView` and presents a concise Smoked Glass card so
 *   first-party `sessionStorage`, state nonces, and PKCE cookies remain intact.
 * - **Image-Only Long-Press** (`imageUrl != null && rawUrl == imageUrl`): skips the preview
 *   `WebView` and displays an image summary card with Save Image, Open Image in New Tab,
 *   Copy Image Link, and Share actions.
 * - **Normal Links (with or without linked image)**: runs `<a href>` through
 *   [AdblockController.cleanTrackingUrl], spawns an isolated sandboxed preview `WebView`
 *   (`"focal_link_peek"` profile wiped immediately on `onDispose`), and provides a compact
 *   action cluster (`Open`, `Background`, `Private`, `Copy`, `Share`, and `Save Image`).
 */
@Composable
fun LinkPeekSheet(
    rawUrl: String,
    cleanedResult: CleanedUrlResult,
    adblockController: AdblockController,
    onOpenInBackgroundTab: (String) -> Unit,
    onExpandFullTab: (String) -> Unit,
    onDismiss: () -> Unit,
    imageUrl: String? = null,
    onOpenInPrivateTab: ((url: String) -> Unit)? = null,
    onCopyLink: ((url: String) -> Unit)? = null,
    onShareLink: ((url: String) -> Unit)? = null,
    onSaveImage: ((imageUrl: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val normalizedRawUrl = rawUrl.trim()
    val normalizedImageUrl = imageUrl?.trim()?.takeIf { it.isNotBlank() }
    val isImageOnly = normalizedImageUrl != null && normalizedRawUrl == normalizedImageUrl
    val isAuthOrOAuthLink = !isImageOnly && (
        OAuthUrlDetector.isAuthOrOAuthUrl(normalizedRawUrl) ||
            OAuthUrlDetector.isAuthOrOAuthUrl(cleanedResult.cleanUrl)
        )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        when {
            isAuthOrOAuthLink -> {
                OAuthSafeLinkCard(
                    rawUrl = normalizedRawUrl.ifBlank { rawUrl },
                    imageUrl = normalizedImageUrl,
                    onExpandFullTab = onExpandFullTab,
                    onOpenInBackgroundTab = onOpenInBackgroundTab,
                    onOpenInPrivateTab = onOpenInPrivateTab,
                    onCopyLink = onCopyLink,
                    onShareLink = onShareLink,
                    onSaveImage = onSaveImage,
                    onDismiss = onDismiss,
                )
            }
            isImageOnly && normalizedImageUrl != null -> {
                ImageOnlyContextCard(
                    imageUrl = normalizedImageUrl,
                    onSaveImage = onSaveImage,
                    onExpandFullTab = onExpandFullTab,
                    onOpenInBackgroundTab = onOpenInBackgroundTab,
                    onOpenInPrivateTab = onOpenInPrivateTab,
                    onCopyLink = onCopyLink,
                    onShareLink = onShareLink,
                    onDismiss = onDismiss,
                )
            }
            else -> {
                LiveLinkPeekContent(
                    rawUrl = rawUrl,
                    cleanedResult = cleanedResult,
                    adblockController = adblockController,
                    imageUrl = normalizedImageUrl,
                    onOpenInBackgroundTab = onOpenInBackgroundTab,
                    onExpandFullTab = onExpandFullTab,
                    onOpenInPrivateTab = onOpenInPrivateTab,
                    onCopyLink = onCopyLink,
                    onShareLink = onShareLink,
                    onSaveImage = onSaveImage,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun OAuthSafeLinkCard(
    rawUrl: String,
    imageUrl: String?,
    onExpandFullTab: (String) -> Unit,
    onOpenInBackgroundTab: (String) -> Unit,
    onOpenInPrivateTab: ((url: String) -> Unit)?,
    onCopyLink: ((url: String) -> Unit)?,
    onShareLink: ((url: String) -> Unit)?,
    onSaveImage: ((imageUrl: String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val sheetTint = rememberUpdatedState(colors.effectiveSheetGlass)
    val host = remember(rawUrl) { extractDisplayHost(rawUrl) }

    SmokedGlassBox(
        shape = FocalGeometry.SheetOuterShape,
        glassTintState = sheetTint,
        borderSpecular = colors.borderSpecular,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .focalSheetEntrance(riseDp = 18.dp)
            .testTag("link_peek_oauth_card")
            .consumeClicks(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FocalSheetGrabber(modifier = Modifier.align(Alignment.CenterHorizontally))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.surfacePillSubtle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = FocalGlyphs.Lock,
                            contentDescription = null,
                            tint = colors.inkPrimary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = host,
                            style = typography.cardTitle,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Direct Sign-In",
                            style = typography.captionMetadata,
                            color = colors.inkSecondary,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                ClosePeekCircleButton(onDismiss = onDismiss)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(FocalGeometry.TileShape)
                    .background(colors.surfacePillSubtle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Sign-in link — opens directly to keep your session intact",
                    style = typography.bodyCompact,
                    color = colors.inkPrimary,
                )
                Text(
                    text = rawUrl,
                    style = typography.captionMetadata,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            PrimaryOpenActionRow(
                targetUrl = rawUrl,
                onOpenInBackgroundTab = onOpenInBackgroundTab,
                onExpandFullTab = onExpandFullTab,
                useGlassBackgroundPill = false,
            )

            SecondaryLinkActionsRow(
                targetUrl = rawUrl,
                imageUrl = imageUrl,
                onOpenInPrivateTab = onOpenInPrivateTab,
                onCopyLink = onCopyLink,
                onShareLink = onShareLink,
                onSaveImage = onSaveImage,
                wrapInGlassCard = false,
            )
        }
    }
}

@Composable
private fun ImageOnlyContextCard(
    imageUrl: String,
    onSaveImage: ((imageUrl: String) -> Unit)?,
    onExpandFullTab: (String) -> Unit,
    onOpenInBackgroundTab: (String) -> Unit,
    onOpenInPrivateTab: ((url: String) -> Unit)?,
    onCopyLink: ((url: String) -> Unit)?,
    onShareLink: ((url: String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val sheetTint = rememberUpdatedState(colors.effectiveSheetGlass)
    val fileName = remember(imageUrl) { extractImageFileName(imageUrl) }
    val host = remember(imageUrl) { extractDisplayHost(imageUrl) }

    SmokedGlassBox(
        shape = FocalGeometry.SheetOuterShape,
        glassTintState = sheetTint,
        borderSpecular = colors.borderSpecular,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .focalSheetEntrance(riseDp = 18.dp)
            .testTag("link_peek_image_card")
            .consumeClicks(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FocalSheetGrabber(modifier = Modifier.align(Alignment.CenterHorizontally))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(FocalGeometry.TileShape)
                            .background(colors.surfacePillSubtle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = FocalGlyphs.Image,
                            contentDescription = null,
                            tint = colors.inkPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = fileName,
                            style = typography.cardTitle,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = host,
                            style = typography.captionMetadata,
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                ClosePeekCircleButton(onDismiss = onDismiss)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfacePillSubtle)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    text = imageUrl,
                    style = typography.captionMetadata,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Primary Save Image CTA
            val saveEnabled = onSaveImage != null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(if (saveEnabled) colors.surfacePillActive else colors.surfacePillSubtle)
                    .clickable(enabled = saveEnabled) { onSaveImage?.invoke(imageUrl) }
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = FocalGlyphs.Download,
                    contentDescription = null,
                    tint = if (saveEnabled) colors.inkOnPillActive else colors.inkFaint,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Save Image",
                    style = typography.controlPillLabel,
                    color = if (saveEnabled) colors.inkOnPillActive else colors.inkFaint,
                )
            }

            FocalGroupedCard {
                FocalMenuRow(
                    title = "Open Image in New Tab",
                    icon = FocalGlyphs.ExternalArrow,
                    onClick = { onExpandFullTab(imageUrl) },
                )
                FocalHairlineDivider()
                FocalMenuRow(
                    title = "Open in Background",
                    icon = FocalGlyphs.Plus,
                    onClick = { onOpenInBackgroundTab(imageUrl) },
                )
                if (onOpenInPrivateTab != null) {
                    FocalHairlineDivider()
                    FocalMenuRow(
                        title = "Open in Private Tab",
                        icon = FocalGlyphs.Aperture,
                        onClick = { onOpenInPrivateTab(imageUrl) },
                    )
                }
                FocalHairlineDivider()
                FocalMenuRow(
                    title = "Copy Image Link",
                    icon = FocalGlyphs.Copy,
                    enabled = onCopyLink != null,
                    onClick = onCopyLink?.let { copyCb -> { copyCb(imageUrl) } },
                )
                FocalHairlineDivider()
                FocalMenuRow(
                    title = "Share",
                    icon = FocalGlyphs.Share,
                    enabled = onShareLink != null,
                    onClick = onShareLink?.let { shareCb -> { shareCb(imageUrl) } },
                )
            }
        }
    }
}

@Composable
private fun LiveLinkPeekContent(
    rawUrl: String,
    cleanedResult: CleanedUrlResult,
    adblockController: AdblockController,
    imageUrl: String?,
    onOpenInBackgroundTab: (String) -> Unit,
    onExpandFullTab: (String) -> Unit,
    onOpenInPrivateTab: ((url: String) -> Unit)?,
    onCopyLink: ((url: String) -> Unit)?,
    onShareLink: ((url: String) -> Unit)?,
    onSaveImage: ((imageUrl: String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val gestureLease = LocalGestureLeaseController.current
    val sheetTint = rememberUpdatedState(colors.effectiveSheetGlass)

    var verticalDragPx by remember { mutableFloatStateOf(0f) }

    val strippedSummary = remember(rawUrl, cleanedResult) {
        if (cleanedResult.strippedParams.isEmpty()) {
            null
        } else {
            val rawQuery = rawUrl.substringAfter('?', "").substringBefore('#')
            if (rawQuery.isNotBlank()) "?$rawQuery" else cleanedResult.strippedParams.joinToString("&")
        }
    }

    val dragSettleScope = rememberCoroutineScope()
    val dragSettleAnim = remember { Animatable(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .focalSheetEntrance(riseDp = 18.dp)
            .graphicsLayer {
                val effectiveDragY = verticalDragPx + dragSettleAnim.value
                translationY = effectiveDragY
                val norm = (abs(effectiveDragY) / 600f).coerceIn(0f, 0.15f)
                scaleX = 1f - norm
                scaleY = 1f - norm
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. Top Clean Link Preview Header Card
        SmokedGlassBox(
            shape = FocalGeometry.CardShape,
            glassTintState = sheetTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .consumeClicks(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon(
                            imageVector = FocalGlyphs.Shield,
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "Link Preview",
                            style = typography.controlPillLabel,
                            color = colors.inkPrimary,
                        )
                    }
                    val strippedCount = cleanedResult.strippedParams.size
                    if (strippedCount > 0) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.surfacePillSubtle)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = "$strippedCount tracker${if (strippedCount == 1) "" else "s"} removed",
                                style = typography.captionMetadata,
                                color = colors.inkSecondary,
                            )
                        }
                    }
                }

                Text(
                    text = cleanedResult.cleanUrl,
                    style = typography.urlDomainPill,
                    color = colors.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (!strippedSummary.isNullOrBlank()) {
                    Text(
                        text = strippedSummary,
                        style = typography.captionMetadata.copy(
                            textDecoration = TextDecoration.LineThrough,
                        ),
                        color = colors.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 2. Live Scrollable Sandboxed Peek Card
        SmokedGlassBox(
            shape = FocalGeometry.SheetOuterShape,
            glassTintState = sheetTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .consumeClicks(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Drag Handle Header (Flick down to dismiss, pull up to promote)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(cleanedResult.cleanUrl, gestureLease) {
                            var dragLeaseToken: GestureLeaseToken? = null
                            try {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        dragLeaseToken = gestureLease?.tryAcquire(GestureLeaseOwner.SHEET_DRAG)
                                    },
                                    onDragEnd = {
                                        val stillValid = gestureLease == null || gestureLease.isValid(dragLeaseToken)
                                        gestureLease?.release(dragLeaseToken)
                                        dragLeaseToken = null
                                        val dragged = verticalDragPx
                                        verticalDragPx = 0f
                                        when {
                                            stillValid && dragged > 110.dp.toPx() -> onDismiss()
                                            stillValid && dragged < -110.dp.toPx() -> onExpandFullTab(cleanedResult.cleanUrl)
                                            abs(dragged) > 1f -> {
                                                dragSettleScope.launch {
                                                    dragSettleAnim.snapTo(dragged)
                                                    dragSettleAnim.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = FocalMotion.linkPeekSpring(),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        gestureLease?.release(dragLeaseToken)
                                        dragLeaseToken = null
                                        val dragged = verticalDragPx
                                        verticalDragPx = 0f
                                        if (abs(dragged) > 1f) {
                                            dragSettleScope.launch {
                                                dragSettleAnim.snapTo(dragged)
                                                dragSettleAnim.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = FocalMotion.linkPeekSpring(),
                                                )
                                            }
                                        }
                                    },
                                    onVerticalDrag = { change, dragAmount ->
                                        if (gestureLease != null && !gestureLease.isValid(dragLeaseToken)) {
                                            verticalDragPx = 0f
                                            return@detectVerticalDragGestures
                                        }
                                        change.consume()
                                        verticalDragPx += dragAmount
                                    },
                                )
                            } finally {
                                gestureLease?.release(dragLeaseToken)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    FocalSheetGrabber(modifier = Modifier.align(Alignment.Center))
                    ClosePeekCircleButton(
                        onDismiss = onDismiss,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }

                LinkPeekWebPreview(
                    cleanUrl = cleanedResult.cleanUrl,
                    adblockController = adblockController,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)),
                )
            }
        }

        // 3. Compact Action Cluster (`Background` · `Open` + optional `Private` · `Copy` · `Share` · `Save Image`)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryOpenActionRow(
                targetUrl = cleanedResult.cleanUrl,
                onOpenInBackgroundTab = onOpenInBackgroundTab,
                onExpandFullTab = onExpandFullTab,
                useGlassBackgroundPill = true,
            )

            SecondaryLinkActionsRow(
                targetUrl = cleanedResult.cleanUrl,
                imageUrl = imageUrl,
                onOpenInPrivateTab = onOpenInPrivateTab,
                onCopyLink = onCopyLink,
                onShareLink = onShareLink,
                onSaveImage = onSaveImage,
                wrapInGlassCard = true,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
@Composable
private fun LinkPeekWebPreview(
    cleanUrl: String,
    adblockController: AdblockController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val previewWebView = remember(cleanUrl) {
        WebView(context).apply {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                runCatching {
                    ProfileStore.getInstance().getOrCreateProfile(WebViewSession.LINK_PEEK_PROFILE_NAME)
                    WebViewCompat.setProfile(this, WebViewSession.LINK_PEEK_PROFILE_NAME)
                }
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                databaseEnabled = false
                saveFormData = false
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                    runCatching { WebSettingsCompat.setSafeBrowsingEnabled(this, false) }
                }
            }
            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(this, false) }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val targetUrl = request?.url?.toString() ?: return true
                    return !WebViewSession.isAllowedNavigationUrl(targetUrl)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val targetUrl = url ?: return true
                    return !WebViewSession.isAllowedNavigationUrl(targetUrl)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val req = request ?: return null
                    if (req.isForMainFrame) return null
                    val decision = adblockController.checkNetworkRequest(
                        requestUrl = req.url?.toString().orEmpty(),
                        sourceUrl = cleanUrl,
                        resourceType = "other",
                    )
                    return AdblockWebResponses.toWebResourceResponse(decision)
                }
            }
            if (WebViewSession.isAllowedNavigationUrl(cleanUrl)) {
                loadUrl(cleanUrl)
            }
        }
    }

    DisposableEffect(previewWebView) {
        onDispose {
            (previewWebView.parent as? ViewGroup)?.removeView(previewWebView)
            previewWebView.stopLoading()
            previewWebView.clearHistory()
            previewWebView.clearCache(true)
            previewWebView.destroy()

            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    val store = ProfileStore.getInstance()
                    val peekProfile = store.getProfile(WebViewSession.LINK_PEEK_PROFILE_NAME)
                    peekProfile?.cookieManager?.removeAllCookies(null)
                    peekProfile?.webStorage?.deleteAllData()
                    store.deleteProfile(WebViewSession.LINK_PEEK_PROFILE_NAME)
                } catch (_: RuntimeException) {
                    // Cookies & storage already cleared above if WebView teardown is completing
                }
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                previewWebView.apply {
                    (parent as? ViewGroup)?.removeView(this)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
        )
    }
}

@Composable
private fun PrimaryOpenActionRow(
    targetUrl: String,
    onOpenInBackgroundTab: (String) -> Unit,
    onExpandFullTab: (String) -> Unit,
    useGlassBackgroundPill: Boolean,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val barTint = rememberUpdatedState(colors.effectiveBarGlass)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (useGlassBackgroundPill) {
            SmokedGlassBox(
                shape = CircleShape,
                glassTintState = barTint,
                borderSpecular = colors.borderSpecular,
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .clickable { onOpenInBackgroundTab(targetUrl) },
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 13.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = FocalGlyphs.Plus,
                        contentDescription = null,
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Background",
                        style = typography.controlPillLabel,
                        color = colors.inkPrimary,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle)
                    .clickable { onOpenInBackgroundTab(targetUrl) }
                    .padding(vertical = 13.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = FocalGlyphs.Plus,
                    contentDescription = null,
                    tint = colors.inkPrimary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Background",
                    style = typography.controlPillLabel,
                    color = colors.inkPrimary,
                )
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .clip(CircleShape)
                .background(colors.surfacePillActive)
                .clickable { onExpandFullTab(targetUrl) }
                .padding(vertical = 13.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Open",
                style = typography.controlPillLabel,
                color = colors.inkOnPillActive,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = FocalGlyphs.ExternalArrow,
                contentDescription = null,
                tint = colors.inkOnPillActive,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun SecondaryLinkActionsRow(
    targetUrl: String,
    imageUrl: String?,
    onOpenInPrivateTab: ((url: String) -> Unit)?,
    onCopyLink: ((url: String) -> Unit)?,
    onShareLink: ((url: String) -> Unit)?,
    onSaveImage: ((imageUrl: String) -> Unit)?,
    wrapInGlassCard: Boolean,
) {
    val showSaveImage = imageUrl != null && onSaveImage != null
    val hasAnySecondary = onOpenInPrivateTab != null ||
        onCopyLink != null ||
        onShareLink != null ||
        showSaveImage
    if (!hasAnySecondary) return

    val colors = FocalTheme.colors
    val barTint = rememberUpdatedState(colors.effectiveBarGlass)

    val rowContent: @Composable (Modifier) -> Unit = { rowMod ->
        Row(
            modifier = rowMod,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (onOpenInPrivateTab != null) {
                FocalActionTile(
                    label = "Private",
                    icon = FocalGlyphs.Aperture,
                    onClick = { onOpenInPrivateTab(targetUrl) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (onCopyLink != null) {
                FocalActionTile(
                    label = "Copy",
                    icon = FocalGlyphs.Copy,
                    onClick = { onCopyLink(targetUrl) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (onShareLink != null) {
                FocalActionTile(
                    label = "Share",
                    icon = FocalGlyphs.Share,
                    onClick = { onShareLink(targetUrl) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (showSaveImage && imageUrl != null && onSaveImage != null) {
                FocalActionTile(
                    label = "Save Image",
                    icon = FocalGlyphs.Download,
                    onClick = { onSaveImage(imageUrl) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (wrapInGlassCard) {
        SmokedGlassBox(
            shape = FocalGeometry.CardShape,
            glassTintState = barTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .consumeClicks(),
        ) {
            rowContent(
                Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
            )
        }
    } else {
        rowContent(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ClosePeekCircleButton(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(colors.surfacePillSubtle)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = FocalGlyphs.Close,
            contentDescription = "Close preview",
            tint = colors.inkSecondary,
            modifier = Modifier.size(14.dp),
        )
    }
}

private fun extractDisplayHost(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    val parsedHost = runCatching {
        URI(trimmed).host
    }.getOrNull()?.trimEnd('.')
    if (!parsedHost.isNullOrBlank()) return parsedHost
    val afterScheme = trimmed.substringAfter("://", trimmed)
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.substringAfterLast('@').substringBefore(':').ifBlank { trimmed }
}

private fun extractImageFileName(imageUrl: String): String {
    val withoutFragment = imageUrl.substringBefore('#')
    val withoutQuery = withoutFragment.substringBefore('?')
    val trimmed = withoutQuery.trimEnd('/')
    val schemeIndex = trimmed.indexOf("://")
    val afterHost = if (schemeIndex >= 0) {
        val slashAfterHost = trimmed.indexOf('/', schemeIndex + 3)
        if (slashAfterHost >= 0) trimmed.substring(slashAfterHost + 1) else ""
    } else {
        trimmed
    }
    val rawSegment = afterHost.substringAfterLast('/', "").trim()
    if (rawSegment.isEmpty()) return "Image"
    return runCatching {
        URLDecoder.decode(rawSegment, "UTF-8")
    }.getOrDefault(rawSegment).ifBlank { "Image" }
}

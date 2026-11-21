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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import app.focal.design.FocalGeometry
import app.focal.design.FocalGlyphs
import app.focal.design.FocalSheetGrabber
import app.focal.design.FocalTheme
import app.focal.design.GestureLeaseOwner
import app.focal.design.GestureLeaseToken
import app.focal.design.LocalGestureLeaseController
import app.focal.design.consumeClicks
import app.focal.design.focalSheetEntrance
import app.focal.design.shaders.SmokedGlassBox
import app.focal.engine.webview.WebViewSession
import kotlinx.coroutines.launch

/**
 * Screen 07 · Long-Press Scrollable `LinkPeekSheet` + Clean Link Preview Header.
 *
 * - Runs long-pressed `<a href>` through [AdblockController.cleanTrackingUrl] to strip
 *   `utm_*`, `fbclid`, `gclid`, `msclkid`, `mc_eid`, `igshid`, `si`, etc. and displays the
 *   stripped tracking parameters with strikethrough.
 * - Spawns an isolated sandboxed preview `WebView` (`"focal_link_peek"` profile wiped immediately
 *   on `onDispose`) inside a concentric Smoked Glass frame with flick-down to dismiss (`0ms` return)
 *   and `Open in Background` / `Open Full Tab` actions.
 */
@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
@Composable
fun LinkPeekSheet(
    rawUrl: String,
    cleanedResult: CleanedUrlResult,
    adblockController: AdblockController,
    onOpenInBackgroundTab: (String) -> Unit,
    onExpandFullTab: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val context = LocalContext.current
    val gestureLease = LocalGestureLeaseController.current
    val sheetTint = rememberUpdatedState(colors.effectiveSheetGlass)
    val barTint = rememberUpdatedState(colors.effectiveBarGlass)

    var verticalDragPx by remember { mutableFloatStateOf(0f) }

    val previewWebView = remember(cleanedResult.cleanUrl) {
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
                        sourceUrl = cleanedResult.cleanUrl,
                        resourceType = "other",
                    )
                    return AdblockWebResponses.toWebResourceResponse(decision)
                }
            }
            if (WebViewSession.isAllowedNavigationUrl(cleanedResult.cleanUrl)) {
                loadUrl(cleanedResult.cleanUrl)
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

    val strippedSummary = remember(rawUrl, cleanedResult) {
        if (cleanedResult.strippedParams.isEmpty()) {
            null
        } else {
            val rawQuery = rawUrl.substringAfter('?', "").substringBefore('#')
            if (rawQuery.isNotBlank()) "?$rawQuery" else cleanedResult.strippedParams.joinToString("&")
        }
    }

    val dragSettleScope = androidx.compose.runtime.rememberCoroutineScope()
    val dragSettleAnim = remember { androidx.compose.animation.core.Animatable(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(onClick = onDismiss),
    ) {
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
                    val norm = (kotlin.math.abs(effectiveDragY) / 600f).coerceIn(0f, 0.15f)
                    scaleX = 1f - norm
                    scaleY = 1f - norm
                },
            verticalArrangement = Arrangement.SpaceBetween,
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
                    .fillMaxHeight(0.72f)
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
                                                kotlin.math.abs(dragged) > 1f -> {
                                                    dragSettleScope.launch {
                                                        dragSettleAnim.snapTo(dragged)
                                                        dragSettleAnim.animateTo(
                                                            targetValue = 0f,
                                                            animationSpec = app.focal.design.FocalMotion.linkPeekSpring(),
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
                                            if (kotlin.math.abs(dragged) > 1f) {
                                                dragSettleScope.launch {
                                                    dragSettleAnim.snapTo(dragged)
                                                    dragSettleAnim.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = app.focal.design.FocalMotion.linkPeekSpring(),
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
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
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

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)),
                    ) {
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
            }

            // 3. Bottom Action Cluster (`Open in Background` · `Open Full Tab`)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SmokedGlassBox(
                    shape = CircleShape,
                    glassTintState = barTint,
                    borderSpecular = colors.borderSpecular,
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenInBackgroundTab(cleanedResult.cleanUrl) },
                ) {
                    Text(
                        text = "Open in Background",
                        style = typography.controlPillLabel,
                        color = colors.inkPrimary,
                        modifier = Modifier.padding(vertical = 13.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(colors.surfacePillActive)
                        .clickable { onExpandFullTab(cleanedResult.cleanUrl) }
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Open Full Tab",
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
    }
}

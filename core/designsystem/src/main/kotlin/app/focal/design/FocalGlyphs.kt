package app.focal.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Focal's bespoke 24x24 optical vector icon set.
 *
 * Crafted on a uniform `1.85dp` stroke grid (`StrokeCap.Round`, `StrokeJoin.Round`) to match
 * the quiet hardware precision of Lens and YAB without raw Unicode symbol fallbacks.
 */
object FocalGlyphs {

    private inline fun strokeIcon(
        name: String,
        strokeWidth: Float = 1.85f,
        block: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

    val Search: ImageVector by lazy {
        strokeIcon("FocalSearch") {
            // Circle centered at (10.8, 10.8) radius 6.3
            moveTo(17.1f, 10.8f)
            arcTo(6.3f, 6.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4.5f, 10.8f)
            arcTo(6.3f, 6.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.1f, 10.8f)
            // Handle
            moveTo(15.4f, 15.4f)
            lineTo(19.8f, 19.8f)
        }
    }

    val Aperture: ImageVector by lazy {
        strokeIcon("FocalAperture") {
            // Outer optical ring r=8.2
            moveTo(20.2f, 12f)
            arcTo(8.2f, 8.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 3.8f, 12f)
            arcTo(8.2f, 8.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20.2f, 12f)
            // Inner iris ring r=3.1
            moveTo(15.1f, 12f)
            arcTo(3.1f, 3.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 8.9f, 12f)
            arcTo(3.1f, 3.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15.1f, 12f)
        }
    }

    val MoreHorizontal: ImageVector by lazy {
        ImageVector.Builder(
            name = "FocalMoreHorizontal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                pathFillType = PathFillType.NonZero,
            ) {
                for (cx in floatArrayOf(5.8f, 12f, 18.2f)) {
                    moveTo(cx + 1.65f, 12f)
                    arcTo(1.65f, 1.65f, 0f, isMoreThanHalf = true, isPositiveArc = true, cx - 1.65f, 12f)
                    arcTo(1.65f, 1.65f, 0f, isMoreThanHalf = true, isPositiveArc = true, cx + 1.65f, 12f)
                    close()
                }
            }
        }.build()
    }

    val ArrowLeft: ImageVector by lazy {
        strokeIcon("FocalArrowLeft") {
            moveTo(19f, 12f)
            lineTo(5f, 12f)
            moveTo(11f, 6f)
            lineTo(5f, 12f)
            lineTo(11f, 18f)
        }
    }

    val ArrowRight: ImageVector by lazy {
        strokeIcon("FocalArrowRight") {
            moveTo(5f, 12f)
            lineTo(19f, 12f)
            moveTo(13f, 6f)
            lineTo(19f, 12f)
            lineTo(13f, 18f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("FocalChevronRight", strokeWidth = 1.9f) {
            moveTo(9.5f, 6.5f)
            lineTo(15f, 12f)
            lineTo(9.5f, 17.5f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        strokeIcon("FocalChevronDown", strokeWidth = 1.9f) {
            moveTo(6.5f, 9.5f)
            lineTo(12f, 15f)
            lineTo(17.5f, 9.5f)
        }
    }

    val Reload: ImageVector by lazy {
        strokeIcon("FocalReload") {
            moveTo(19.2f, 12f)
            arcTo(7.2f, 7.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.1f, 6.9f)
            lineTo(19.2f, 9f)
            moveTo(19.2f, 4.6f)
            lineTo(19.2f, 9f)
            lineTo(14.8f, 9f)
        }
    }

    val Share: ImageVector by lazy {
        strokeIcon("FocalShare") {
            moveTo(12f, 15f)
            lineTo(12f, 4.2f)
            moveTo(8f, 8.2f)
            lineTo(12f, 4.2f)
            lineTo(16f, 8.2f)
            moveTo(5.2f, 13.2f)
            lineTo(5.2f, 17.6f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 7.2f, 19.6f)
            lineTo(16.8f, 19.6f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 18.8f, 17.6f)
            lineTo(18.8f, 13.2f)
        }
    }

    val Pin: ImageVector by lazy {
        strokeIcon("FocalPin") {
            moveTo(9f, 4.5f)
            lineTo(15f, 4.5f)
            lineTo(14.2f, 10.2f)
            lineTo(17f, 13f)
            lineTo(7f, 13f)
            lineTo(9.8f, 10.2f)
            close()
            moveTo(12f, 13f)
            lineTo(12f, 19.8f)
        }
    }

    val Bookmark: ImageVector by lazy {
        strokeIcon("FocalBookmark") {
            moveTo(6.8f, 4.8f)
            arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.4f, 3.8f)
            lineTo(15.6f, 3.8f)
            arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17.2f, 4.8f)
            lineTo(17.2f, 19.8f)
            lineTo(12f, 15.8f)
            lineTo(6.8f, 19.8f)
            close()
        }
    }

    val Reader: ImageVector by lazy {
        strokeIcon("FocalReader") {
            moveTo(4.8f, 6.2f)
            lineTo(19.2f, 6.2f)
            moveTo(4.8f, 10.8f)
            lineTo(19.2f, 10.8f)
            moveTo(4.8f, 15.4f)
            lineTo(14.8f, 15.4f)
            moveTo(4.8f, 19f)
            lineTo(11.4f, 19f)
        }
    }

    val Translate: ImageVector by lazy {
        strokeIcon("FocalTranslate") {
            // Globe ring
            moveTo(20f, 12f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 12f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20f, 12f)
            // Equator
            moveTo(4.2f, 12f)
            lineTo(19.8f, 12f)
            // Meridians
            moveTo(12f, 4f)
            quadTo(15.6f, 8f, 15.6f, 12f)
            quadTo(15.6f, 16f, 12f, 20f)
            moveTo(12f, 4f)
            quadTo(8.4f, 8f, 8.4f, 12f)
            quadTo(8.4f, 16f, 12f, 20f)
        }
    }

    val Desktop: ImageVector by lazy {
        strokeIcon("FocalDesktop") {
            moveTo(5.4f, 5f)
            lineTo(18.6f, 5f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 20.4f, 6.8f)
            lineTo(20.4f, 14.2f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 18.6f, 16f)
            lineTo(5.4f, 16f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3.6f, 14.2f)
            lineTo(3.6f, 6.8f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5.4f, 5f)
            moveTo(8.5f, 19.5f)
            lineTo(15.5f, 19.5f)
            moveTo(12f, 16f)
            lineTo(12f, 19.5f)
        }
    }

    val Shield: ImageVector by lazy {
        strokeIcon("FocalShield") {
            moveTo(12f, 3.8f)
            lineTo(18.6f, 6.4f)
            lineTo(18.6f, 11.8f)
            quadTo(18.6f, 17f, 12f, 20.2f)
            quadTo(5.4f, 17f, 5.4f, 11.8f)
            lineTo(5.4f, 6.4f)
            close()
        }
    }

    val Moon: ImageVector by lazy {
        strokeIcon("FocalMoon") {
            moveTo(18.8f, 14.8f)
            arcTo(7.4f, 7.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.2f, 5.2f)
            arcTo(5.8f, 5.8f, 0f, isMoreThanHalf = false, isPositiveArc = false, 18.8f, 14.8f)
            close()
        }
    }

    val Typography: ImageVector by lazy {
        strokeIcon("FocalTypography") {
            // Large T
            moveTo(4.0f, 6.5f)
            lineTo(13.6f, 6.5f)
            moveTo(8.8f, 6.5f)
            lineTo(8.8f, 18.0f)
            // Small T
            moveTo(13.8f, 11.5f)
            lineTo(20.2f, 11.5f)
            moveTo(17.0f, 11.5f)
            lineTo(17.0f, 18.0f)
        }
    }

    val Code: ImageVector by lazy {
        strokeIcon("FocalCode") {
            moveTo(8.2f, 8.2f)
            lineTo(4.2f, 12f)
            lineTo(8.2f, 15.8f)
            moveTo(15.8f, 8.2f)
            lineTo(19.8f, 12f)
            lineTo(15.8f, 15.8f)
            moveTo(13.4f, 6.8f)
            lineTo(10.6f, 17.2f)
        }
    }

    val Copy: ImageVector by lazy {
        strokeIcon("FocalCopy") {
            // Front rounded rect (9,9..19,19)
            moveTo(10.8f, 9f)
            lineTo(17.2f, 9f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 19f, 10.8f)
            lineTo(19f, 17.2f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17.2f, 19f)
            lineTo(10.8f, 19f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 9f, 17.2f)
            lineTo(9f, 10.8f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 10.8f, 9f)
            // Back corner (5,15 -> 5,6.8 -> 15,5)
            moveTo(6f, 15f)
            lineTo(5.6f, 15f)
            arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4.8f, 13.6f)
            lineTo(4.8f, 6.6f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6.6f, 4.8f)
            lineTo(13.6f, 4.8f)
            arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15f, 5.6f)
        }
    }

    val Folder: ImageVector by lazy {
        strokeIcon("FocalFolder") {
            moveTo(3.8f, 7.2f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5.6f, 5.4f)
            lineTo(9.4f, 5.4f)
            lineTo(11.4f, 7.6f)
            lineTo(18.4f, 7.6f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 20.2f, 9.4f)
            lineTo(20.2f, 16.8f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 18.4f, 18.6f)
            lineTo(5.6f, 18.6f)
            arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3.8f, 16.8f)
            close()
        }
    }

    val Laptop: ImageVector by lazy {
        strokeIcon("FocalLaptop") {
            moveTo(5.4f, 6f)
            lineTo(18.6f, 6f)
            arcTo(1.4f, 1.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 20f, 7.4f)
            lineTo(20f, 15.6f)
            lineTo(4f, 15.6f)
            lineTo(4f, 7.4f)
            arcTo(1.4f, 1.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5.4f, 6f)
            moveTo(2.6f, 18.4f)
            lineTo(21.4f, 18.4f)
        }
    }

    val ExternalArrow: ImageVector by lazy {
        strokeIcon("FocalExternalArrow") {
            moveTo(7.5f, 16.5f)
            lineTo(16.5f, 7.5f)
            moveTo(9.5f, 7.5f)
            lineTo(16.5f, 7.5f)
            lineTo(16.5f, 14.5f)
        }
    }

    val Close: ImageVector by lazy {
        strokeIcon("FocalClose", strokeWidth = 1.9f) {
            moveTo(7.2f, 7.2f)
            lineTo(16.8f, 16.8f)
            moveTo(16.8f, 7.2f)
            lineTo(7.2f, 16.8f)
        }
    }

    val Plus: ImageVector by lazy {
        strokeIcon("FocalPlus", strokeWidth = 1.9f) {
            moveTo(12f, 5.8f)
            lineTo(12f, 18.2f)
            moveTo(5.8f, 12f)
            lineTo(18.2f, 12f)
        }
    }

    val Minus: ImageVector by lazy {
        strokeIcon("FocalMinus", strokeWidth = 1.9f) {
            moveTo(6.2f, 12f)
            lineTo(17.8f, 12f)
        }
    }

    val Clock: ImageVector by lazy {
        strokeIcon("FocalClock", strokeWidth = 1.8f) {
            moveTo(19.6f, 12f)
            arcTo(7.6f, 7.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4.4f, 12f)
            arcTo(7.6f, 7.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 19.6f, 12f)
            moveTo(12f, 7.8f)
            lineTo(12f, 12f)
            lineTo(14.8f, 13.8f)
        }
    }

    val Lock: ImageVector by lazy {
        strokeIcon("FocalLock", strokeWidth = 1.8f) {
            moveTo(7.2f, 11f)
            lineTo(16.8f, 11f)
            arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 18.4f, 12.6f)
            lineTo(18.4f, 17.8f)
            arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 16.8f, 19.4f)
            lineTo(7.2f, 19.4f)
            arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5.6f, 17.8f)
            lineTo(5.6f, 12.6f)
            arcTo(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7.2f, 11f)
            moveTo(8.6f, 11f)
            lineTo(8.6f, 8f)
            arcTo(3.4f, 3.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15.4f, 8f)
            lineTo(15.4f, 11f)
        }
    }

    val Play: ImageVector by lazy {
        ImageVector.Builder(
            name = "FocalPlay",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.5f, 6.2f)
                lineTo(18.2f, 12f)
                lineTo(8.5f, 17.8f)
                close()
            }
        }.build()
    }

    val Pause: ImageVector by lazy {
        strokeIcon("FocalPause", strokeWidth = 2.4f) {
            moveTo(9f, 6.5f)
            lineTo(9f, 17.5f)
            moveTo(15f, 6.5f)
            lineTo(15f, 17.5f)
        }
    }

    val Pip: ImageVector by lazy {
        strokeIcon("FocalPip", strokeWidth = 1.8f) {
            moveTo(4.5f, 6f)
            lineTo(19.5f, 6f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 21f, 7.5f)
            lineTo(21f, 16.5f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 19.5f, 18f)
            lineTo(4.5f, 18f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 16.5f)
            lineTo(3f, 7.5f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4.5f, 6f)
            // Inset PiP window
            moveTo(12.2f, 11.8f)
            lineTo(18.5f, 11.8f)
            lineTo(18.5f, 15.8f)
            lineTo(12.2f, 15.8f)
            close()
        }
    }

    val Equals: ImageVector by lazy {
        strokeIcon("FocalEquals", strokeWidth = 1.9f) {
            moveTo(6.2f, 9.5f)
            lineTo(17.8f, 9.5f)
            moveTo(6.2f, 14.5f)
            lineTo(17.8f, 14.5f)
        }
    }

    val Convert: ImageVector by lazy {
        strokeIcon("FocalConvert", strokeWidth = 1.85f) {
            // Top arrow right
            moveTo(5.8f, 8.8f)
            lineTo(18.2f, 8.8f)
            moveTo(14.8f, 5.6f)
            lineTo(18.2f, 8.8f)
            lineTo(14.8f, 12f)
            // Bottom arrow left
            moveTo(18.2f, 15.2f)
            lineTo(5.8f, 15.2f)
            moveTo(9.2f, 12f)
            lineTo(5.8f, 15.2f)
            lineTo(9.2f, 18.4f)
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("FocalCheck", strokeWidth = 2.15f) {
            moveTo(5.6f, 12.6f)
            lineTo(10.0f, 17.0f)
            lineTo(18.6f, 7.6f)
        }
    }
}

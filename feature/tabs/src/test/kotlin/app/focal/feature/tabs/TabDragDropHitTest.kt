package app.focal.feature.tabs

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabDragDropHitTest {

    private val targets = mapOf(
        "personal" to Rect(left = 40f, top = 120f, right = 240f, bottom = 200f),
        "work" to Rect(left = 260f, top = 120f, right = 420f, bottom = 200f),
        "reading" to Rect(left = 440f, top = 120f, right = 640f, bottom = 200f),
    )

    @Test
    fun hitsValidTargetDirectlyAndExcludesCurrentActiveSpace() {
        val valid = setOf("work", "reading")
        assertNull(
            resolveHoveredDropSpaceId(
                pointerInRoot = Offset(140f, 160f),
                dropTargets = targets,
                validTargetIds = valid,
                slopHorizontalPx = 16f,
                slopVerticalPx = 44f,
            ),
        )
        assertEquals(
            "work",
            resolveHoveredDropSpaceId(
                pointerInRoot = Offset(340f, 160f),
                dropTargets = targets,
                validTargetIds = valid,
                slopHorizontalPx = 16f,
                slopVerticalPx = 44f,
            ),
        )
    }

    @Test
    fun hitsTargetAboveTopBarAndWithinMagneticSlop() {
        val valid = setOf("work", "reading")
        // Dragged high up near the top status bar directly above Work
        assertEquals(
            "work",
            resolveHoveredDropSpaceId(
                pointerInRoot = Offset(340f, 18f),
                dropTargets = targets,
                validTargetIds = valid,
                slopHorizontalPx = 16f,
                slopVerticalPx = 44f,
            ),
        )
        // Dragged slightly below Reading pill within magnetic vertical slop
        assertEquals(
            "reading",
            resolveHoveredDropSpaceId(
                pointerInRoot = Offset(540f, 238f),
                dropTargets = targets,
                validTargetIds = valid,
                slopHorizontalPx = 16f,
                slopVerticalPx = 44f,
            ),
        )
    }

    @Test
    fun breaksBoundaryOverlapByChoosingNearestHorizontalCenter() {
        val valid = setOf("work", "reading")
        // Midpoint boundary between work (right=420, center=340) and reading (left=440, center=540)
        assertEquals(
            "work",
            resolveHoveredDropSpaceId(
                pointerInRoot = Offset(426f, 160f),
                dropTargets = targets,
                validTargetIds = valid,
                slopHorizontalPx = 20f,
                slopVerticalPx = 44f,
            ),
        )
        assertEquals(
            "reading",
            resolveHoveredDropSpaceId(
                pointerInRoot = Offset(445f, 160f),
                dropTargets = targets,
                validTargetIds = valid,
                slopHorizontalPx = 20f,
                slopVerticalPx = 44f,
            ),
        )
    }

    @Test
    fun returnsNullWhenReleasedOnTabDeckBelowStrip() {
        val valid = setOf("work", "reading")
        assertNull(
            resolveHoveredDropSpaceId(
                pointerInRoot = Offset(340f, 520f),
                dropTargets = targets,
                validTargetIds = valid,
                slopHorizontalPx = 16f,
                slopVerticalPx = 44f,
            ),
        )
    }
}

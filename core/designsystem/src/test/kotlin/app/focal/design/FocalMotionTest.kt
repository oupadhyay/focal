package app.focal.design

import androidx.compose.animation.core.Spring
import org.junit.Assert.assertTrue
import org.junit.Test

class FocalMotionTest {

    @Test
    fun allMotionSprings_areCriticallyDampedWithZeroBounceOvershoot() {
        val springs = listOf(
            "searchMorphSpring" to FocalMotion.searchMorphSpring<Float>(),
            "siteChipSpring" to FocalMotion.siteChipSpring<Float>(),
            "tabScrubSnapSpring" to FocalMotion.tabScrubSnapSpring<Float>(),
            "spacesSheetSpring" to FocalMotion.spacesSheetSpring<Float>(),
            "pinchSnapSpring" to FocalMotion.pinchSnapSpring<Float>(),
            "linkPeekSpring" to FocalMotion.linkPeekSpring<Float>(),
            "smartPillSpring" to FocalMotion.smartPillSpring<Float>(),
            "menuSubtabSpring" to FocalMotion.menuSubtabSpring<Float>(),
        )
        for ((name, spec) in springs) {
            assertTrue(
                "$name must be critically damped (>= 1.0f) to prevent rubbery overshoot bounce, got ${spec.dampingRatio}",
                spec.dampingRatio >= Spring.DampingRatioNoBouncy,
            )
            assertTrue(
                "$name stiffness should stay responsive (>= 380f), got ${spec.stiffness}",
                spec.stiffness >= 380f,
            )
        }
    }
}

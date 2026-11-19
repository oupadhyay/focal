package app.focal.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class InlineFieldEvaluatorTest {

    // Fixed UTC instant: 2026-06-15T16:30:00Z (Summer DST active in US & Europe)
    private val fixedUtcClock: Clock = Clock.fixed(
        Instant.parse("2026-06-15T16:30:00Z"),
        ZoneId.of("UTC"),
    )

    // -------------------------------------------------------------------------
    // Math Calculator Tests
    // -------------------------------------------------------------------------

    @Test
    fun math_evaluatesArithmeticPrecedenceParenthesesAndFloatingPointCleanup() {
        val tax = InlineFieldEvaluator.evaluate("48 * 1.0825", fixedUtcClock)
        assertNotNull(tax)
        assertEquals(InlineFieldAnswer.Kind.MATH, tax!!.kind)
        assertEquals("= 51.96", tax.headlineAnswer)
        assertEquals("51.96", tax.copyValue)

        val aspect = InlineFieldEvaluator.evaluate("(1920 / 16) * 9", fixedUtcClock)
        assertEquals("= 1,080", aspect?.headlineAnswer)
        assertEquals("1080", aspect?.copyValue)

        val fpClean = InlineFieldEvaluator.evaluate("0.1 + 0.2", fixedUtcClock)
        assertEquals("= 0.3", fpClean?.headlineAnswer)

        val power = InlineFieldEvaluator.evaluate("2^10", fixedUtcClock)
        assertEquals("= 1,024", power?.headlineAnswer)
    }

    @Test
    fun math_evaluatesPercentagesFunctionsAndMultiplyShorthand() {
        val pctOf = InlineFieldEvaluator.evaluate("15% of 240", fixedUtcClock)
        assertEquals("= 36", pctOf?.headlineAnswer)

        val addPct = InlineFieldEvaluator.evaluate("200 + 15%", fixedUtcClock)
        assertEquals("= 230", addPct?.headlineAnswer)

        val root = InlineFieldEvaluator.evaluate("sqrt(144) + 8", fixedUtcClock)
        assertEquals("= 20", root?.headlineAnswer)

        val screen = InlineFieldEvaluator.evaluate("1920 x 1080", fixedUtcClock)
        assertEquals("= 2,073,600", screen?.headlineAnswer)
    }

    @Test
    fun guardrails_neverTriggerOnBareNumbersUrlsIpAddressesOrPlainQueries() {
        assertNull(InlineFieldEvaluator.evaluate("2026", fixedUtcClock))
        assertNull(InlineFieldEvaluator.evaluate("google.com", fixedUtcClock))
        assertNull(InlineFieldEvaluator.evaluate("https://github.com/oupadhyay/focal", fixedUtcClock))
        assertNull(InlineFieldEvaluator.evaluate("192.168.1.1", fixedUtcClock))
        assertNull(InlineFieldEvaluator.evaluate("screen time", fixedUtcClock))
        assertNull(InlineFieldEvaluator.evaluate("git rebase", fixedUtcClock))
        assertNull(InlineFieldEvaluator.evaluate("10 / 0", fixedUtcClock))
    }

    // -------------------------------------------------------------------------
    // Unit Conversion Tests
    // -------------------------------------------------------------------------

    @Test
    fun units_convertsMassLengthTemperatureAndDataSizes() {
        val weight = InlineFieldEvaluator.evaluate("180 lbs in kg", fixedUtcClock)
        assertNotNull(weight)
        assertEquals(InlineFieldAnswer.Kind.UNIT, weight!!.kind)
        assertEquals("81.65 kg", weight.headlineAnswer)
        assertEquals("180 lb → kg", weight.subtitle)

        val implicitWeight = InlineFieldEvaluator.evaluate("180 lbs", fixedUtcClock)
        assertEquals("81.65 kg", implicitWeight?.headlineAnswer)

        val tempFtoC = InlineFieldEvaluator.evaluate("72f in c", fixedUtcClock)
        assertEquals("22.2 °C", tempFtoC?.headlineAnswer)

        val minusForty = InlineFieldEvaluator.evaluate("-40f in c", fixedUtcClock)
        assertEquals("-40 °C", minusForty?.headlineAnswer)

        val miles = InlineFieldEvaluator.evaluate("5 mi to km", fixedUtcClock)
        assertEquals("8.05 km", miles?.headlineAnswer)

        val storage = InlineFieldEvaluator.evaluate("256 gb in gib", fixedUtcClock)
        assertEquals("238.42 GiB", storage?.headlineAnswer)
    }

    // -------------------------------------------------------------------------
    // World Clock & Timezone Tests
    // -------------------------------------------------------------------------

    @Test
    fun timezones_evaluatesWorldClockAndCrossZoneConversionsDeterministically() {
        // 16:30 UTC -> 01:30 AM next day in Tokyo (UTC+9)
        val tokyo = InlineFieldEvaluator.evaluate("time in tokyo", fixedUtcClock)
        assertNotNull(tokyo)
        assertEquals(InlineFieldAnswer.Kind.TIMEZONE, tokyo!!.kind)
        assertEquals("1:30 AM · Tomorrow", tokyo.headlineAnswer)
        assertTrue(tokyo.subtitle.contains("Tokyo"))
        assertTrue(tokyo.subtitle.contains("JST"))

        val tokyoShort = InlineFieldEvaluator.evaluate("tokyo time", fixedUtcClock)
        assertEquals("1:30 AM · Tomorrow", tokyoShort?.headlineAnswer)

        // 14:30 UTC -> 20:00 IST (+5h 30m)
        val utcToIst = InlineFieldEvaluator.evaluate("14:30 utc to ist", fixedUtcClock)
        assertNotNull(utcToIst)
        assertEquals("20:00 IST", utcToIst!!.headlineAnswer)
        assertTrue(utcToIst.subtitle.contains("+5h 30m"))

        // 9am PST (America/Los_Angeles PDT in June, UTC-7) -> 5:00 PM BST (UTC+1, +8h) in London
        val pstToLondon = InlineFieldEvaluator.evaluate("9am pst in london", fixedUtcClock)
        assertNotNull(pstToLondon)
        assertTrue(pstToLondon!!.headlineAnswer.startsWith("5:00 PM"))
        assertTrue(pstToLondon.subtitle.contains("+8h"))
    }
}

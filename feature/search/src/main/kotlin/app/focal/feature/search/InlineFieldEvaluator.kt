package app.focal.feature.search

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

@Immutable
data class InlineFieldAnswer(
    val kind: Kind,
    val headlineAnswer: String,
    val subtitle: String,
    val copyValue: String,
) {
    enum class Kind {
        MATH,
        UNIT,
        TIMEZONE,
    }
}

/**
 * Zero-network offline search field evaluator (`< 0.1ms` per keystroke):
 * - **Calculator**: Recursive-descent arithmetic (`+`, `-`, `*`, `/`, `%`, `^`, parentheses,
 *   percentages like `15% of 240` or `200 + 15%`, constants `pi`/`e`, functions `sqrt`/`sin`/`cos`/`tan`/`ln`/`log`/`abs`).
 * - **Unit Conversions**: Explicit (`180 lbs in kg`, `72f to c`, `256 gb in gib`) and natural
 *   single-unit counterparts (`180 lbs`, `72f`, `5 mi`, `100 km/h`).
 * - **World Clock & Timezones**: Local `java.time.ZoneId` queries (`time in tokyo`, `tokyo time`,
 *   `9am pst in london`, `14:30 utc to ist`) with full DST accuracy and zero network calls.
 */
object InlineFieldEvaluator {

    private val URL_OR_DOMAIN_GUARD = Regex(
        "^(https?://|www\\.|[a-zA-Z0-9-]+\\.(com|org|net|io|dev|app|co|ai|gov|edu|uk|de|jp|fr)(/.*)?$)",
        RegexOption.IGNORE_CASE,
    )
    private val IPV4_GUARD = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$")

    fun evaluate(
        rawQuery: String,
        clock: Clock = Clock.systemDefaultZone(),
    ): InlineFieldAnswer? {
        val trimmed = rawQuery.trim()
        if (trimmed.length < 2 || trimmed.length > 120) return null
        if (URL_OR_DOMAIN_GUARD.containsMatchIn(trimmed) || IPV4_GUARD.matches(trimmed)) return null

        return evaluateTimezone(trimmed, clock)
            ?: evaluateUnits(trimmed)
            ?: evaluateMath(trimmed)
    }

    // -------------------------------------------------------------------------
    // 1. World Clock & Timezone Conversions (zero network, java.time.ZoneId)
    // -------------------------------------------------------------------------

    private data class ZoneEntry(
        val zoneId: String,
        val displayName: String,
        val shortAbbrev: String? = null,
    )

    private val ZONE_LOOKUP: Map<String, ZoneEntry> = buildMap {
        fun add(zoneId: String, displayName: String, abbrev: String? = null, vararg aliases: String) {
            val entry = ZoneEntry(zoneId, displayName, abbrev)
            for (alias in aliases) {
                put(alias.lowercase(Locale.US), entry)
            }
        }

        // Standard abbreviations & offsets
        add("UTC", "UTC", "UTC", "utc", "gmt", "zulu")
        add("America/Los_Angeles", "Pacific", null, "pst", "pdt", "pt", "pacific")
        add("America/Denver", "Mountain", null, "mst", "mdt", "mt", "mountain")
        add("America/Chicago", "Central", null, "cst", "cdt", "ct", "central")
        add("America/New_York", "Eastern", null, "est", "edt", "et", "eastern")
        add("Europe/London", "London", null, "bst", "london", "uk")
        add("Europe/Paris", "Paris", null, "cet", "cest", "paris")
        add("Europe/Athens", "Athens", null, "eet", "eest", "athens")
        add("Asia/Kolkata", "India", "IST", "ist", "india", "mumbai", "delhi", "new delhi", "bangalore", "bengaluru", "hyderabad", "chennai", "kolkata")
        add("Asia/Tokyo", "Tokyo", "JST", "jst", "tokyo", "japan", "osaka", "kyoto")
        add("Asia/Seoul", "Seoul", "KST", "kst", "seoul", "korea")
        add("Asia/Singapore", "Singapore", "SGT", "sgt", "singapore")
        add("Asia/Hong_Kong", "Hong Kong", "HKT", "hkt", "hong kong", "hk")
        add("Asia/Shanghai", "Shanghai", "CST", "shanghai", "beijing", "shenzhen", "china")
        add("Asia/Taipei", "Taipei", "CST", "taipei", "taiwan")
        add("Australia/Sydney", "Sydney", null, "aest", "aedt", "sydney", "melbourne")
        add("Pacific/Auckland", "Auckland", null, "nzst", "nzdt", "auckland", "new zealand")

        // Major North & South America cities
        add("America/Los_Angeles", "San Francisco", null, "sf", "san francisco", "bay area", "la", "los angeles", "seattle", "portland", "san diego", "las vegas", "vancouver")
        add("America/Phoenix", "Phoenix", "MST", "phoenix", "arizona")
        add("America/Denver", "Denver", null, "denver", "salt lake city", "calgary")
        add("America/Chicago", "Chicago", null, "chicago", "austin", "dallas", "houston", "minneapolis", "mexico city")
        add("America/New_York", "New York", null, "nyc", "new york", "boston", "miami", "atlanta", "washington", "dc", "philadelphia", "toronto", "montreal")
        add("Pacific/Honolulu", "Honolulu", "HST", "honolulu", "hawaii", "hst")
        add("America/Anchorage", "Anchorage", null, "anchorage", "alaska", "akst", "akdt")
        add("America/Sao_Paulo", "São Paulo", "BRT", "sao paulo", "são paulo", "rio", "brazil", "brt")
        add("America/Argentina/Buenos_Aires", "Buenos Aires", "ART", "buenos aires", "argentina")

        // Major Europe, Middle East & Africa cities
        add("Europe/Berlin", "Berlin", null, "berlin", "munich", "frankfurt", "germany")
        add("Europe/Zurich", "Zurich", null, "zurich", "geneva", "switzerland")
        add("Europe/Amsterdam", "Amsterdam", null, "amsterdam", "netherlands")
        add("Europe/Dublin", "Dublin", null, "dublin", "ireland")
        add("Europe/Madrid", "Madrid", null, "madrid", "barcelona", "spain")
        add("Europe/Rome", "Rome", null, "rome", "milan", "italy")
        add("Europe/Lisbon", "Lisbon", null, "lisbon", "portugal")
        add("Europe/Stockholm", "Stockholm", null, "stockholm", "sweden")
        add("Europe/Warsaw", "Warsaw", null, "warsaw", "poland")
        add("Europe/Istanbul", "Istanbul", "TRT", "istanbul", "turkey")
        add("Asia/Dubai", "Dubai", "GST", "dubai", "abu dhabi", "uae", "gst")
        add("Asia/Riyadh", "Riyadh", "AST", "riyadh", "saudi")
        add("Africa/Cairo", "Cairo", null, "cairo", "egypt")
        add("Africa/Johannesburg", "Johannesburg", "SAST", "johannesburg", "cape town", "sast")

        // Southeast Asia
        add("Asia/Bangkok", "Bangkok", "ICT", "bangkok", "thailand", "vietnam", "hanoi")
        add("Asia/Jakarta", "Jakarta", "WIB", "jakarta", "indonesia", "wib")
        add("Asia/Manila", "Manila", "PHT", "manila", "philippines", "pht")
    }

    private val NOW_IN_ZONE_REGEX = Regex(
        "^(?:what(?:'s| is)?\\s+(?:the\\s+)?)?(?:current\\s+)?(?:time|now|clock)\\s+(?:in|at)\\s+(.+?)\\??$",
        RegexOption.IGNORE_CASE,
    )
    private val PLACE_TIME_REGEX = Regex(
        "^([a-zA-Z\\s]{2,24})\\s+time\\??$",
        RegexOption.IGNORE_CASE,
    )
    private val CONVERT_TIME_WITH_SOURCE_REGEX = Regex(
        "^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s+([a-zA-Z][a-zA-Z\\s]{1,20}?)\\s+(?:in|to|->|as)\\s+(.+?)\\??$",
        RegexOption.IGNORE_CASE,
    )
    private val CONVERT_TIME_LOCAL_SOURCE_REGEX = Regex(
        "^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s+(?:in|to|->)\\s+(.+?)\\??$",
        RegexOption.IGNORE_CASE,
    )

    private val TIME_12H_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val TIME_24H_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val ZONE_ABBREV_FMT = DateTimeFormatter.ofPattern("zzz", Locale.US)

    private fun lookupZone(rawPlace: String): ZoneEntry? {
        val cleaned = rawPlace.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
        ZONE_LOOKUP[cleaned]?.let { return it }
        // Also support explicit IANA zone IDs like "America/New_York"
        if (cleaned.contains('/')) {
            val matchedId = runCatching {
                ZoneId.getAvailableZoneIds().firstOrNull { it.equals(cleaned, ignoreCase = true) }
            }.getOrNull()
            if (matchedId != null) {
                val city = matchedId.substringAfterLast('/').replace('_', ' ')
                return ZoneEntry(zoneId = matchedId, displayName = city)
            }
        }
        return null
    }

    private fun formatOffsetTag(zdt: ZonedDateTime): String {
        val totalSeconds = zdt.offset.totalSeconds
        if (totalSeconds == 0) return "UTC"
        val sign = if (totalSeconds > 0) "+" else "-"
        val absSec = abs(totalSeconds)
        val hours = absSec / 3600
        val mins = (absSec % 3600) / 60
        return if (mins == 0) "UTC$sign$hours" else "UTC$sign$hours:${mins.toString().padStart(2, '0')}"
    }

    private fun formatDayDelta(sourceDate: LocalDate, targetDate: LocalDate): String {
        return when (ChronoUnit.DAYS.between(sourceDate, targetDate).toInt()) {
            0 -> ""
            1 -> " · Tomorrow"
            -1 -> " · Yesterday"
            else -> " · ${targetDate.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))}"
        }
    }

    private fun evaluateTimezone(query: String, clock: Clock): InlineFieldAnswer? {
        // 1. "time in tokyo" / "now in london"
        NOW_IN_ZONE_REGEX.matchEntire(query)?.let { match ->
            val target = lookupZone(match.groupValues[1]) ?: return null
            return formatCurrentTimeInZone(target, clock)
        }

        // 2. "tokyo time" / "pst time"
        PLACE_TIME_REGEX.matchEntire(query)?.let { match ->
            val target = lookupZone(match.groupValues[1]) ?: return null
            return formatCurrentTimeInZone(target, clock)
        }

        // 3. "9am pst in london" / "14:30 utc to ist"
        CONVERT_TIME_WITH_SOURCE_REGEX.matchEntire(query)?.let { match ->
            val hourRaw = match.groupValues[1].toIntOrNull() ?: return null
            val minuteRaw = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val meridiem = match.groupValues[3].lowercase(Locale.US)
            val sourceEntry = lookupZone(match.groupValues[4]) ?: return null
            val targetEntry = lookupZone(match.groupValues[5]) ?: return null
            return convertTimeBetweenZones(
                hourRaw = hourRaw,
                minuteRaw = minuteRaw,
                meridiem = meridiem,
                sourceZone = ZoneId.of(sourceEntry.zoneId),
                sourceLabel = sourceEntry.shortAbbrev ?: sourceEntry.displayName,
                targetEntry = targetEntry,
                clock = clock,
            )
        }

        // 4. "9am in tokyo" (from local device timezone)
        CONVERT_TIME_LOCAL_SOURCE_REGEX.matchEntire(query)?.let { match ->
            val hourRaw = match.groupValues[1].toIntOrNull() ?: return null
            val minuteRaw = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val meridiem = match.groupValues[3].lowercase(Locale.US)
            val targetEntry = lookupZone(match.groupValues[4]) ?: return null
            val localZone = clock.zone
            return convertTimeBetweenZones(
                hourRaw = hourRaw,
                minuteRaw = minuteRaw,
                meridiem = meridiem,
                sourceZone = localZone,
                sourceLabel = "Local",
                targetEntry = targetEntry,
                clock = clock,
            )
        }

        return null
    }

    private fun formatCurrentTimeInZone(target: ZoneEntry, clock: Clock): InlineFieldAnswer {
        val nowLocal = ZonedDateTime.now(clock)
        val targetZone = ZoneId.of(target.zoneId)
        val nowTarget = nowLocal.withZoneSameInstant(targetZone)
        val timeText = nowTarget.format(TIME_12H_FMT)
        val abbrev = target.shortAbbrev ?: nowTarget.format(ZONE_ABBREV_FMT)
        val offsetTag = formatOffsetTag(nowTarget)
        val dayDelta = formatDayDelta(nowLocal.toLocalDate(), nowTarget.toLocalDate())
        return InlineFieldAnswer(
            kind = InlineFieldAnswer.Kind.TIMEZONE,
            headlineAnswer = "$timeText$dayDelta",
            subtitle = "${target.displayName} · $abbrev ($offsetTag)",
            copyValue = "$timeText $abbrev",
        )
    }

    private fun convertTimeBetweenZones(
        hourRaw: Int,
        minuteRaw: Int,
        meridiem: String,
        sourceZone: ZoneId,
        sourceLabel: String,
        targetEntry: ZoneEntry,
        clock: Clock,
    ): InlineFieldAnswer? {
        if (minuteRaw !in 0..59) return null
        val use24HourOutput = meridiem.isEmpty()
        val hour24 = when {
            meridiem == "am" -> {
                if (hourRaw !in 1..12) return null
                if (hourRaw == 12) 0 else hourRaw
            }
            meridiem == "pm" -> {
                if (hourRaw !in 1..12) return null
                if (hourRaw == 12) 12 else hourRaw + 12
            }
            else -> {
                if (hourRaw !in 0..23) return null
                hourRaw
            }
        }

        val referenceDate = ZonedDateTime.now(clock).withZoneSameInstant(sourceZone).toLocalDate()
        val sourceZdt = ZonedDateTime.of(referenceDate, LocalTime.of(hour24, minuteRaw), sourceZone)
        val targetZone = ZoneId.of(targetEntry.zoneId)
        val targetZdt = sourceZdt.withZoneSameInstant(targetZone)

        val fmt = if (use24HourOutput) TIME_24H_FMT else TIME_12H_FMT
        val formattedSource = sourceZdt.format(fmt)
        val formattedTarget = targetZdt.format(fmt)
        val targetAbbrev = targetEntry.shortAbbrev ?: targetZdt.format(ZONE_ABBREV_FMT)
        val dayDelta = formatDayDelta(sourceZdt.toLocalDate(), targetZdt.toLocalDate())

        val diffMinutes = (targetZdt.offset.totalSeconds - sourceZdt.offset.totalSeconds) / 60
        val diffLabel = when {
            diffMinutes == 0 -> "same time"
            diffMinutes % 60 == 0 -> {
                val h = diffMinutes / 60
                if (h > 0) "+${h}h" else "${h}h"
            }
            else -> {
                val sign = if (diffMinutes > 0) "+" else "-"
                val absMin = abs(diffMinutes)
                "$sign${absMin / 60}h ${absMin % 60}m"
            }
        }

        return InlineFieldAnswer(
            kind = InlineFieldAnswer.Kind.TIMEZONE,
            headlineAnswer = "$formattedTarget $targetAbbrev$dayDelta",
            subtitle = "$formattedSource $sourceLabel → ${targetEntry.displayName} ($diffLabel)",
            copyValue = "$formattedTarget $targetAbbrev",
        )
    }

    // -------------------------------------------------------------------------
    // 2. Unit Conversions (Length, Mass, Temperature, Volume, Speed, Area, Data)
    // -------------------------------------------------------------------------

    private enum class UnitCategory {
        LENGTH,
        MASS,
        TEMPERATURE,
        VOLUME,
        SPEED,
        AREA,
        DATA,
    }

    private data class UnitSpec(
        val id: String,
        val displaySymbol: String,
        val category: UnitCategory,
        val toBaseFactor: Double = 1.0,
        val defaultCounterpartId: String? = null,
        val allowImplicitSingleUnit: Boolean = true,
    )

    private val UNITS_BY_ID: Map<String, UnitSpec>
    private val UNITS_BY_ALIAS: Map<String, UnitSpec>

    init {
        val byId = LinkedHashMap<String, UnitSpec>()
        val byAlias = LinkedHashMap<String, UnitSpec>()

        fun reg(
            id: String,
            symbol: String,
            category: UnitCategory,
            factor: Double = 1.0,
            defaultTarget: String? = null,
            allowImplicit: Boolean = true,
            vararg aliases: String,
        ) {
            val spec = UnitSpec(id, symbol, category, factor, defaultTarget, allowImplicit)
            byId[id] = spec
            byAlias[id] = spec
            for (a in aliases) {
                byAlias[a.lowercase(Locale.US)] = spec
            }
        }

        // Length (base: meter)
        reg("mm", "mm", UnitCategory.LENGTH, 0.001, "in", true, "millimeter", "millimeters", "millimetre", "millimetres")
        reg("cm", "cm", UnitCategory.LENGTH, 0.01, "in", true, "centimeter", "centimeters", "centimetre", "centimetres")
        reg("m", "m", UnitCategory.LENGTH, 1.0, "ft", false, "meter", "meters", "metre", "metres")
        reg("km", "km", UnitCategory.LENGTH, 1000.0, "mi", true, "kilometer", "kilometers", "kilometre", "kilometres")
        reg("in", "in", UnitCategory.LENGTH, 0.0254, "cm", false, "inch", "inches")
        reg("ft", "ft", UnitCategory.LENGTH, 0.3048, "m", true, "foot", "feet")
        reg("yd", "yd", UnitCategory.LENGTH, 0.9144, "m", true, "yard", "yards")
        reg("mi", "mi", UnitCategory.LENGTH, 1609.344, "km", true, "mile", "miles")
        reg("nmi", "nmi", UnitCategory.LENGTH, 1852.0, "km", true, "nm", "nautical mile", "nautical miles")

        // Mass (base: kilogram)
        reg("mg", "mg", UnitCategory.MASS, 1e-6, "g", false, "milligram", "milligrams")
        reg("g", "g", UnitCategory.MASS, 0.001, "oz", false, "gram", "grams")
        reg("kg", "kg", UnitCategory.MASS, 1.0, "lb", true, "kgs", "kilogram", "kilograms", "kilo", "kilos")
        reg("oz", "oz", UnitCategory.MASS, 0.028349523125, "g", true, "ounce", "ounces")
        reg("lb", "lb", UnitCategory.MASS, 0.45359237, "kg", true, "lbs", "pound", "pounds")
        reg("st", "st", UnitCategory.MASS, 6.35029318, "kg", true, "stone", "stones")
        reg("ton", "ton", UnitCategory.MASS, 907.18474, "kg", true, "tons")
        reg("tonne", "t", UnitCategory.MASS, 1000.0, "lb", true, "tonnes", "metric ton")

        // Temperature (affine)
        reg("c", "°C", UnitCategory.TEMPERATURE, 1.0, "f", true, "°c", "celsius", "centigrade")
        reg("f", "°F", UnitCategory.TEMPERATURE, 1.0, "c", true, "°f", "fahrenheit")
        reg("k", "K", UnitCategory.TEMPERATURE, 1.0, "c", false, "kelvin")

        // Volume (base: liter)
        reg("ml", "mL", UnitCategory.VOLUME, 0.001, "floz", true, "milliliter", "milliliters", "millilitre", "millilitres")
        reg("l", "L", UnitCategory.VOLUME, 1.0, "gal", false, "liter", "liters", "litre", "litres")
        reg("tsp", "tsp", UnitCategory.VOLUME, 0.00492892159375, "ml", true, "teaspoon", "teaspoons")
        reg("tbsp", "tbsp", UnitCategory.VOLUME, 0.01478676478125, "ml", true, "tablespoon", "tablespoons")
        reg("floz", "fl oz", UnitCategory.VOLUME, 0.0295735295625, "ml", true, "fl oz", "fl. oz", "fluid ounce", "fluid ounces")
        reg("cup", "cup", UnitCategory.VOLUME, 0.2365882365, "ml", true, "cups")
        reg("pt", "pt", UnitCategory.VOLUME, 0.473176473, "ml", true, "pint", "pints")
        reg("qt", "qt", UnitCategory.VOLUME, 0.946352946, "l", true, "quart", "quarts")
        reg("gal", "gal", UnitCategory.VOLUME, 3.785411784, "l", true, "gallon", "gallons")

        // Speed (base: m/s)
        reg("mps", "m/s", UnitCategory.SPEED, 1.0, "kmh", true, "m/s")
        reg("kmh", "km/h", UnitCategory.SPEED, 1000.0 / 3600.0, "mph", true, "km/h", "kph")
        reg("mph", "mph", UnitCategory.SPEED, 1609.344 / 3600.0, "kmh", true, "mi/h")
        reg("kt", "kn", UnitCategory.SPEED, 1852.0 / 3600.0, "kmh", true, "kn", "knot", "knots")

        // Area (base: square meter)
        reg("sqm", "m²", UnitCategory.AREA, 1.0, "sqft", true, "m2", "m²", "sq m", "square meter", "square meters")
        reg("sqft", "ft²", UnitCategory.AREA, 0.09290304, "sqm", true, "ft2", "ft²", "sq ft", "square foot", "square feet")
        reg("acre", "acre", UnitCategory.AREA, 4046.8564224, "ha", true, "acres")
        reg("ha", "ha", UnitCategory.AREA, 10000.0, "acre", true, "hectare", "hectares")

        // Digital Storage (base: byte)
        reg("b", "B", UnitCategory.DATA, 1.0, null, false, "byte", "bytes")
        reg("kb", "KB", UnitCategory.DATA, 1_000.0, "kib", true, "kilobyte", "kilobytes")
        reg("mb", "MB", UnitCategory.DATA, 1_000_000.0, "mib", true, "megabyte", "megabytes")
        reg("gb", "GB", UnitCategory.DATA, 1_000_000_000.0, "gib", true, "gigabyte", "gigabytes")
        reg("tb", "TB", UnitCategory.DATA, 1_000_000_000_000.0, "tib", true, "terabyte", "terabytes")
        reg("kib", "KiB", UnitCategory.DATA, 1024.0, "kb", true)
        reg("mib", "MiB", UnitCategory.DATA, 1024.0 * 1024.0, "mb", true)
        reg("gib", "GiB", UnitCategory.DATA, 1024.0 * 1024.0 * 1024.0, "gb", true)
        reg("tib", "TiB", UnitCategory.DATA, 1024.0 * 1024.0 * 1024.0 * 1024.0, "tb", true)

        UNITS_BY_ID = byId
        UNITS_BY_ALIAS = byAlias
    }

    private val EXPLICIT_UNIT_REGEX = Regex(
        "^([+-]?\\d[\\d,]*(?:\\.\\d+)?)\\s*(°?[a-zA-Z][a-zA-Z0-9°/\\s.²³-]{0,16}?)\\s+(?:in|to|as|->|=)\\s+(°?[a-zA-Z][a-zA-Z0-9°/\\s.²³-]{0,16})$",
        RegexOption.IGNORE_CASE,
    )
    private val IMPLICIT_UNIT_REGEX = Regex(
        "^([+-]?\\d[\\d,]*(?:\\.\\d+)?)\\s*(°?[a-zA-Z][a-zA-Z0-9°/\\s.²³-]{0,14})$",
        RegexOption.IGNORE_CASE,
    )

    private fun lookupUnit(raw: String): UnitSpec? {
        val normalized = raw.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
        return UNITS_BY_ALIAS[normalized]
    }

    private fun convertValue(value: Double, from: UnitSpec, to: UnitSpec): Double? {
        if (from.category != to.category || from.id == to.id) return null
        return if (from.category == UnitCategory.TEMPERATURE) {
            val celsius = when (from.id) {
                "c" -> value
                "f" -> (value - 32.0) * 5.0 / 9.0
                "k" -> value - 273.15
                else -> return null
            }
            when (to.id) {
                "c" -> celsius
                "f" -> celsius * 9.0 / 5.0 + 32.0
                "k" -> celsius + 273.15
                else -> null
            }
        } else {
            val inBase = value * from.toBaseFactor
            inBase / to.toBaseFactor
        }
    }

    private fun evaluateUnits(query: String): InlineFieldAnswer? {
        EXPLICIT_UNIT_REGEX.matchEntire(query)?.let { match ->
            val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            val from = lookupUnit(match.groupValues[2]) ?: return null
            val to = lookupUnit(match.groupValues[3]) ?: return null
            val converted = convertValue(number, from, to) ?: return null
            if (!converted.isFinite()) return null
            val formattedResult = formatNumeric(converted, maxFractionDigits = if (from.category == UnitCategory.TEMPERATURE) 1 else 2)
            val formattedInput = formatNumeric(number, maxFractionDigits = 4)
            return InlineFieldAnswer(
                kind = InlineFieldAnswer.Kind.UNIT,
                headlineAnswer = "$formattedResult ${to.displaySymbol}",
                subtitle = "$formattedInput ${from.displaySymbol} → ${to.displaySymbol}",
                copyValue = "${formatRawNumeric(converted, maxFractionDigits = 4)} ${to.displaySymbol}",
            )
        }

        IMPLICIT_UNIT_REGEX.matchEntire(query)?.let { match ->
            val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            val rawUnit = match.groupValues[2].trim()
            val from = lookupUnit(rawUnit) ?: return null
            // Single-letter 'c'/'f' requires attached form (e.g., "72f", "25°c") or full name so
            // casual tokens like "3 c" don't falsely trigger, while "72f" / "25c" feel magical.
            if (!from.allowImplicitSingleUnit) return null
            val isSingleLetterTemp = (rawUnit.equals("c", true) || rawUnit.equals("f", true))
            if (isSingleLetterTemp && query.contains(' ')) return null

            val targetId = from.defaultCounterpartId ?: return null
            val to = UNITS_BY_ID[targetId] ?: return null
            val converted = convertValue(number, from, to) ?: return null
            if (!converted.isFinite()) return null
            val formattedResult = formatNumeric(converted, maxFractionDigits = if (from.category == UnitCategory.TEMPERATURE) 1 else 2)
            val formattedInput = formatNumeric(number, maxFractionDigits = 4)
            return InlineFieldAnswer(
                kind = InlineFieldAnswer.Kind.UNIT,
                headlineAnswer = "$formattedResult ${to.displaySymbol}",
                subtitle = "$formattedInput ${from.displaySymbol} → ${to.displaySymbol}",
                copyValue = "${formatRawNumeric(converted, maxFractionDigits = 4)} ${to.displaySymbol}",
            )
        }

        return null
    }

    // -------------------------------------------------------------------------
    // 3. Offline Math Calculator (Safe Recursive-Descent Expression Parser)
    // -------------------------------------------------------------------------

    private val PERCENT_OF_REGEX = Regex(
        "^([+-]?\\d[\\d,]*(?:\\.\\d+)?)%\\s+of\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val ADD_SUB_PERCENT_REGEX = Regex(
        "^([+-]?\\d[\\d,]*(?:\\.\\d+)?)\\s*([+-])\\s*(\\d[\\d,]*(?:\\.\\d+)?)%$",
    )
    private val MULTIPLY_X_REGEX = Regex("(?<=\\d|\\))\\s+[xX]\\s+(?=\\d|\\()")
    private val NUMBER_COMMA_REGEX = Regex("(?<=\\d),(?=\\d{3}(?:\\D|$))")

    private fun evaluateMath(rawQuery: String): InlineFieldAnswer? {
        var normalized = rawQuery
            .replace('×', '*')
            .replace('÷', '/')
            .replace('−', '-')
            .replace('–', '-')
            .replace("π", "pi")
            .trim()

        if (normalized.endsWith("=")) {
            normalized = normalized.dropLast(1).trim()
        }
        if (normalized.isEmpty()) return null

        // Shorthand: "15% of 240" -> "(15 / 100) * (240)"
        PERCENT_OF_REGEX.matchEntire(normalized)?.let { match ->
            val pct = match.groupValues[1].replace(",", "")
            val rhs = match.groupValues[2].trim()
            normalized = "($pct / 100) * ($rhs)"
        }

        // Shorthand: "200 + 15%" -> "200 * (1 + 15 / 100)"
        ADD_SUB_PERCENT_REGEX.matchEntire(normalized)?.let { match ->
            val base = match.groupValues[1].replace(",", "")
            val op = match.groupValues[2]
            val pct = match.groupValues[3].replace(",", "")
            normalized = "$base * (1 $op $pct / 100)"
        }

        normalized = MULTIPLY_X_REGEX.replace(normalized, " * ")
        normalized = NUMBER_COMMA_REGEX.replace(normalized, "")

        val parser = MathExpressionParser(normalized)
        val result = parser.parseOrNull() ?: return null
        if (!parser.usedOperatorOrFunction) return null
        if (!result.isFinite() || abs(result) > 1e15) return null

        val displayNumber = formatNumeric(result, maxFractionDigits = 6)
        val rawNumber = formatRawNumeric(result, maxFractionDigits = 6)
        return InlineFieldAnswer(
            kind = InlineFieldAnswer.Kind.MATH,
            headlineAnswer = "= $displayNumber",
            subtitle = rawQuery.trimEnd('=', ' '),
            copyValue = rawNumber,
        )
    }

    private class MathExpressionParser(private val input: String) {
        private var pos = 0
        var usedOperatorOrFunction: Boolean = false
            private set

        fun parseOrNull(): Double? {
            return try {
                val value = parseExpression()
                skipWhitespace()
                if (pos == input.length) value else null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipWhitespace()
            return if (pos < input.length) input[pos] else null
        }

        private fun parseExpression(): Double {
            var left = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> {
                        pos++
                        usedOperatorOrFunction = true
                        left += parseTerm()
                    }
                    '-' -> {
                        pos++
                        usedOperatorOrFunction = true
                        left -= parseTerm()
                    }
                    else -> return left
                }
            }
        }

        private fun parseTerm(): Double {
            var left = parsePower()
            while (true) {
                when (val ch = peek()) {
                    '*' -> {
                        pos++
                        usedOperatorOrFunction = true
                        left *= parsePower()
                    }
                    '/' -> {
                        pos++
                        usedOperatorOrFunction = true
                        val right = parsePower()
                        if (right == 0.0) throw IllegalArgumentException("Division by zero")
                        left /= right
                    }
                    '%' -> {
                        pos++
                        usedOperatorOrFunction = true
                        val next = peek()
                        if (next != null && (next.isDigit() || next == '(' || next == '.')) {
                            val right = parsePower()
                            if (right == 0.0) throw IllegalArgumentException("Modulo by zero")
                            left %= right
                        } else {
                            // Postfix percentage e.g. "(50%) * 20"
                            left /= 100.0
                        }
                    }
                    '(' -> {
                        // Implicit multiplication: e.g. 2(3+4)
                        usedOperatorOrFunction = true
                        left *= parsePower()
                    }
                    else -> {
                        if (ch != null && ch.isLetter()) {
                            // Implicit multiplication: e.g. 2pi
                            usedOperatorOrFunction = true
                            left *= parsePower()
                        } else {
                            return left
                        }
                    }
                }
            }
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            if (peek() == '^') {
                pos++
                usedOperatorOrFunction = true
                val exponent = parsePower() // right-associative
                return base.pow(exponent)
            }
            return base
        }

        private fun parseUnary(): Double {
            return when (peek()) {
                '+' -> {
                    pos++
                    parseUnary()
                }
                '-' -> {
                    pos++
                    -parseUnary()
                }
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            val ch = peek() ?: throw IllegalArgumentException("Unexpected end")
            if (ch == '(') {
                pos++
                val value = parseExpression()
                if (peek() != ')') throw IllegalArgumentException("Missing ')'")
                pos++
                return value
            }
            if (ch.isDigit() || ch == '.') {
                return parseNumber()
            }
            if (ch.isLetter()) {
                val ident = parseIdentifier().lowercase(Locale.US)
                return when (ident) {
                    "pi" -> PI
                    "e" -> kotlin.math.E
                    "sqrt", "sin", "cos", "tan", "ln", "log", "abs", "round", "ceil", "floor" -> {
                        if (peek() != '(') throw IllegalArgumentException("Expected '(' after $ident")
                        pos++
                        val arg = parseExpression()
                        if (peek() != ')') throw IllegalArgumentException("Missing ')' after $ident")
                        pos++
                        usedOperatorOrFunction = true
                        when (ident) {
                            "sqrt" -> if (arg < 0.0) throw IllegalArgumentException("sqrt<0") else sqrt(arg)
                            "sin" -> sin(arg)
                            "cos" -> cos(arg)
                            "tan" -> tan(arg)
                            "ln" -> if (arg <= 0.0) throw IllegalArgumentException("ln<=0") else ln(arg)
                            "log" -> if (arg <= 0.0) throw IllegalArgumentException("log<=0") else log10(arg)
                            "abs" -> abs(arg)
                            "round" -> round(arg)
                            "ceil" -> ceil(arg)
                            "floor" -> floor(arg)
                            else -> throw IllegalArgumentException("Unknown function")
                        }
                    }
                    else -> throw IllegalArgumentException("Unknown identifier: $ident")
                }
            }
            throw IllegalArgumentException("Unexpected character: $ch")
        }

        private fun parseNumber(): Double {
            val start = pos
            var seenDot = false
            while (pos < input.length) {
                val c = input[pos]
                if (c.isDigit()) {
                    pos++
                } else if (c == '.' && !seenDot) {
                    seenDot = true
                    pos++
                } else {
                    break
                }
            }
            return input.substring(start, pos).toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid number")
        }

        private fun parseIdentifier(): String {
            val start = pos
            while (pos < input.length && input[pos].isLetter()) pos++
            return input.substring(start, pos)
        }
    }

    // -------------------------------------------------------------------------
    // Number Formatting (clean IEEE-754 noise cleanup + tabular grouping)
    // -------------------------------------------------------------------------

    private fun cleanBigDecimal(value: Double, maxFractionDigits: Int): BigDecimal {
        val safeValue = if (abs(value) < 1e-12) 0.0 else value
        val rounded = BigDecimal.valueOf(safeValue)
            .setScale(maxFractionDigits, RoundingMode.HALF_UP)
            .stripTrailingZeros()
        return if (rounded.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO else rounded
    }

    private fun formatNumeric(value: Double, maxFractionDigits: Int): String {
        val bd = cleanBigDecimal(value, maxFractionDigits)
        val symbols = DecimalFormatSymbols(Locale.US)
        val pattern = if (maxFractionDigits > 0) {
            "#,##0.${"#".repeat(maxFractionDigits)}"
        } else {
            "#,##0"
        }
        return DecimalFormat(pattern, symbols).format(bd)
    }

    private fun formatRawNumeric(value: Double, maxFractionDigits: Int): String {
        return cleanBigDecimal(value, maxFractionDigits).toPlainString()
    }
}

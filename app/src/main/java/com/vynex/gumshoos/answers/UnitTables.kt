package com.vynex.gumshoos.answers

enum class Dimension { LENGTH, MASS, TEMPERATURE, VOLUME, AREA, SPEED, DATA, DURATION }

/**
 * One unit, expressed against its dimension's base: `base = value * factor + offset`.
 *
 * The offset exists for temperature alone, but folding it into every unit keeps one conversion
 * path rather than a special case that has to be remembered at each call site.
 *
 * @param system null for dimensions where metric and imperial don't apply — bytes and seconds are
 *   the same everywhere, so they have no "other system" to convert to.
 */
data class UnitDef(
    val id: String,
    val symbol: String,
    val dimension: Dimension,
    val system: MeasurementSystem?,
    val factor: Double,
    val offset: Double = 0.0,
    val aliases: List<String> = emptyList(),
) {
    fun toBase(value: Double) = value * factor + offset
    fun fromBase(base: Double) = (base - offset) / factor
}

/**
 * Base units: metre, kilogram, kelvin, litre, square metre, metre/second, byte, second.
 */
object UnitTables {

    private val SI = MeasurementSystem.SI
    private val US = MeasurementSystem.US
    private val UK = MeasurementSystem.UK

    val all: List<UnitDef> = listOf(
        // --- length, base metre -------------------------------------------------------------
        UnitDef("mm", "mm", Dimension.LENGTH, SI, 0.001, aliases = listOf("millimetre", "millimeter", "millimetres", "millimeters")),
        UnitDef("cm", "cm", Dimension.LENGTH, SI, 0.01, aliases = listOf("centimetre", "centimeter", "centimetres", "centimeters")),
        UnitDef("m", "m", Dimension.LENGTH, SI, 1.0, aliases = listOf("metre", "meter", "metres", "meters")),
        UnitDef("km", "km", Dimension.LENGTH, SI, 1000.0, aliases = listOf("kilometre", "kilometer", "kilometres", "kilometers")),
        UnitDef("in", "in", Dimension.LENGTH, US, 0.0254, aliases = listOf("inch", "inches", "\"")),
        UnitDef("ft", "ft", Dimension.LENGTH, US, 0.3048, aliases = listOf("foot", "feet", "'")),
        UnitDef("yd", "yd", Dimension.LENGTH, US, 0.9144, aliases = listOf("yard", "yards")),
        UnitDef("mi", "mi", Dimension.LENGTH, US, 1609.344, aliases = listOf("mile", "miles")),
        UnitDef("nmi", "nmi", Dimension.LENGTH, US, 1852.0, aliases = listOf("nauticalmile", "nauticalmiles")),

        // --- mass, base kilogram ------------------------------------------------------------
        UnitDef("mg", "mg", Dimension.MASS, SI, 1e-6, aliases = listOf("milligram", "milligrams")),
        UnitDef("g", "g", Dimension.MASS, SI, 0.001, aliases = listOf("gram", "grams", "gramme", "grammes")),
        UnitDef("kg", "kg", Dimension.MASS, SI, 1.0, aliases = listOf("kilogram", "kilograms", "kilo", "kilos")),
        UnitDef("t", "t", Dimension.MASS, SI, 1000.0, aliases = listOf("tonne", "tonnes", "metricton")),
        UnitDef("oz", "oz", Dimension.MASS, US, 0.028349523125, aliases = listOf("ounce", "ounces")),
        UnitDef("lb", "lb", Dimension.MASS, US, 0.45359237, aliases = listOf("lbs", "pound", "pounds")),
        UnitDef("st", "st", Dimension.MASS, UK, 6.35029318, aliases = listOf("stone", "stones")),

        // --- temperature, base kelvin -------------------------------------------------------
        UnitDef("c", "°C", Dimension.TEMPERATURE, null, 1.0, 273.15, listOf("celsius", "centigrade", "°c")),
        UnitDef("f", "°F", Dimension.TEMPERATURE, null, 5.0 / 9.0, 273.15 - 32.0 * 5.0 / 9.0, listOf("fahrenheit", "°f")),
        UnitDef("kelvin", "K", Dimension.TEMPERATURE, null, 1.0, 0.0, listOf("kelvins")),

        // --- volume, base litre -------------------------------------------------------------
        UnitDef("ml", "ml", Dimension.VOLUME, SI, 0.001, aliases = listOf("millilitre", "milliliter", "millilitres", "milliliters")),
        UnitDef("l", "l", Dimension.VOLUME, SI, 1.0, aliases = listOf("litre", "liter", "litres", "liters")),
        UnitDef("tsp", "tsp", Dimension.VOLUME, US, 0.00492892159375, aliases = listOf("teaspoon", "teaspoons")),
        UnitDef("tbsp", "tbsp", Dimension.VOLUME, US, 0.01478676478125, aliases = listOf("tablespoon", "tablespoons")),
        UnitDef("floz", "fl oz", Dimension.VOLUME, US, 0.0295735295625, aliases = listOf("fluidounce", "fluidounces")),
        UnitDef("cup", "cup", Dimension.VOLUME, US, 0.2365882365, aliases = listOf("cups")),
        UnitDef("pt", "pt", Dimension.VOLUME, US, 0.473176473, aliases = listOf("pint", "pints")),
        UnitDef("qt", "qt", Dimension.VOLUME, US, 0.946352946, aliases = listOf("quart", "quarts")),
        UnitDef("gal", "gal", Dimension.VOLUME, US, 3.785411784, aliases = listOf("gallon", "gallons")),
        // Imperial measures share names with US ones but differ by ~20%, so they are reachable
        // only by an explicit prefix rather than being silently picked.
        UnitDef("impgal", "imp gal", Dimension.VOLUME, UK, 4.54609, aliases = listOf("imperialgallon", "ukgal")),
        UnitDef("imppt", "imp pt", Dimension.VOLUME, UK, 0.56826125, aliases = listOf("imperialpint", "ukpt")),
        UnitDef("impfloz", "imp fl oz", Dimension.VOLUME, UK, 0.0284130625, aliases = listOf("imperialfluidounce")),

        // --- area, base square metre --------------------------------------------------------
        UnitDef("cm2", "cm²", Dimension.AREA, SI, 0.0001, aliases = listOf("sqcm", "squarecentimetre", "cm^2")),
        UnitDef("m2", "m²", Dimension.AREA, SI, 1.0, aliases = listOf("sqm", "squaremetre", "squaremeter", "m^2")),
        UnitDef("km2", "km²", Dimension.AREA, SI, 1e6, aliases = listOf("sqkm", "squarekilometre", "km^2")),
        UnitDef("hectare", "ha", Dimension.AREA, SI, 10000.0, aliases = listOf("ha", "hectares")),
        UnitDef("in2", "in²", Dimension.AREA, US, 0.00064516, aliases = listOf("sqin", "squareinch", "in^2")),
        UnitDef("ft2", "ft²", Dimension.AREA, US, 0.09290304, aliases = listOf("sqft", "squarefoot", "squarefeet", "ft^2")),
        UnitDef("yd2", "yd²", Dimension.AREA, US, 0.83612736, aliases = listOf("sqyd", "squareyard", "yd^2")),
        UnitDef("acre", "acre", Dimension.AREA, US, 4046.8564224, aliases = listOf("acres")),
        UnitDef("mi2", "mi²", Dimension.AREA, US, 2589988.110336, aliases = listOf("sqmi", "squaremile", "mi^2")),

        // --- speed, base metre/second -------------------------------------------------------
        UnitDef("m/s", "m/s", Dimension.SPEED, SI, 1.0, aliases = listOf("mps", "metrespersecond")),
        UnitDef("km/h", "km/h", Dimension.SPEED, SI, 1.0 / 3.6, aliases = listOf("kmh", "kph", "kmph", "kilometresperhour")),
        UnitDef("mph", "mph", Dimension.SPEED, US, 0.44704, aliases = listOf("mi/h", "milesperhour")),
        UnitDef("ft/s", "ft/s", Dimension.SPEED, US, 0.3048, aliases = listOf("fps", "feetpersecond")),
        UnitDef("knot", "kn", Dimension.SPEED, US, 0.5144444444444445, aliases = listOf("kn", "knots")),

        // --- data, base byte ----------------------------------------------------------------
        // Decimal and binary are both supported and never conflated: KB is 1000, KiB is 1024.
        UnitDef("bit", "bit", Dimension.DATA, null, 0.125, aliases = listOf("bits")),
        UnitDef("b", "B", Dimension.DATA, null, 1.0, aliases = listOf("byte", "bytes")),
        UnitDef("kb", "KB", Dimension.DATA, null, 1e3, aliases = listOf("kilobyte", "kilobytes")),
        UnitDef("mb", "MB", Dimension.DATA, null, 1e6, aliases = listOf("megabyte", "megabytes")),
        UnitDef("gb", "GB", Dimension.DATA, null, 1e9, aliases = listOf("gigabyte", "gigabytes")),
        UnitDef("tb", "TB", Dimension.DATA, null, 1e12, aliases = listOf("terabyte", "terabytes")),
        UnitDef("pb", "PB", Dimension.DATA, null, 1e15, aliases = listOf("petabyte", "petabytes")),
        UnitDef("kib", "KiB", Dimension.DATA, null, 1024.0, aliases = listOf("kibibyte", "kibibytes")),
        UnitDef("mib", "MiB", Dimension.DATA, null, 1048576.0, aliases = listOf("mebibyte", "mebibytes")),
        UnitDef("gib", "GiB", Dimension.DATA, null, 1073741824.0, aliases = listOf("gibibyte", "gibibytes")),
        UnitDef("tib", "TiB", Dimension.DATA, null, 1.099511627776e12, aliases = listOf("tebibyte", "tebibytes")),

        // --- duration, base second ----------------------------------------------------------
        UnitDef("ms", "ms", Dimension.DURATION, null, 0.001, aliases = listOf("millisecond", "milliseconds")),
        UnitDef("s", "s", Dimension.DURATION, null, 1.0, aliases = listOf("sec", "secs", "second", "seconds")),
        UnitDef("min", "min", Dimension.DURATION, null, 60.0, aliases = listOf("mins", "minute", "minutes")),
        UnitDef("h", "h", Dimension.DURATION, null, 3600.0, aliases = listOf("hr", "hrs", "hour", "hours")),
        UnitDef("d", "d", Dimension.DURATION, null, 86400.0, aliases = listOf("day", "days")),
        UnitDef("wk", "wk", Dimension.DURATION, null, 604800.0, aliases = listOf("week", "weeks")),
    )

    /**
     * Token to unit. Every id and alias, lowercased with spaces stripped, so "sq ft", "sqft" and
     * "square feet" all arrive at the same place.
     */
    private val byToken: Map<String, UnitDef> = buildMap {
        all.forEach { unit ->
            put(unit.id.normalisedToken(), unit)
            unit.aliases.forEach { put(it.normalisedToken(), unit) }
        }
    }

    fun find(token: String): UnitDef? = byToken[token.normalisedToken()]

    /**
     * Units a conversion may land on by default, smallest first.
     *
     * Deliberately narrower than [all]: nobody wants 5ft expressed in nautical miles, so the
     * default target is picked from what a person would actually say.
     */
    private val preferred: Map<Pair<Dimension, MeasurementSystem>, List<String>> = mapOf(
        (Dimension.LENGTH to SI) to listOf("mm", "cm", "m", "km"),
        (Dimension.LENGTH to US) to listOf("in", "ft", "mi"),
        (Dimension.LENGTH to UK) to listOf("in", "ft", "mi"),
        (Dimension.MASS to SI) to listOf("mg", "g", "kg", "t"),
        (Dimension.MASS to US) to listOf("oz", "lb"),
        (Dimension.MASS to UK) to listOf("oz", "lb", "st"),
        (Dimension.VOLUME to SI) to listOf("ml", "l"),
        (Dimension.VOLUME to US) to listOf("floz", "cup", "pt", "gal"),
        (Dimension.VOLUME to UK) to listOf("impfloz", "imppt", "impgal"),
        (Dimension.AREA to SI) to listOf("cm2", "m2", "hectare", "km2"),
        (Dimension.AREA to US) to listOf("in2", "ft2", "acre", "mi2"),
        (Dimension.AREA to UK) to listOf("in2", "ft2", "acre", "mi2"),
        (Dimension.SPEED to SI) to listOf("km/h"),
        (Dimension.SPEED to US) to listOf("mph"),
        (Dimension.SPEED to UK) to listOf("mph"),
    )

    /** Scaling ladders for the dimensions that have no metric/imperial split. */
    private val ladders: Map<Dimension, List<String>> = mapOf(
        Dimension.DATA to listOf("b", "kb", "mb", "gb", "tb", "pb"),
        Dimension.DURATION to listOf("ms", "s", "min", "h", "d", "wk"),
    )

    fun preferredFor(dimension: Dimension, system: MeasurementSystem): List<UnitDef> =
        preferred[dimension to system].orEmpty().mapNotNull { id -> all.find { it.id == id } }

    /**
     * Units never chosen as an automatic target, though still reachable explicitly.
     *
     * Milliseconds are the case: "30 s" answering "30,000 ms" is technically right and practically
     * useless, so the default steps to minutes instead. "30 s to ms" still works.
     */
    private val neverAutomatic = setOf("ms")

    fun ladderFor(dimension: Dimension): List<UnitDef> =
        ladders[dimension].orEmpty().mapNotNull { id -> all.find { it.id == id } }

    /** The ladder as used for defaults, with the units above excluded. */
    fun defaultLadderFor(dimension: Dimension): List<UnitDef> =
        ladderFor(dimension).filterNot { it.id in neverAutomatic }

    /**
     * No plural stripping: it would fold "ms" into "m", turning milliseconds into metres. Plurals
     * are listed explicitly in the alias tables instead, where a collision is visible.
     */
    private fun String.normalisedToken(): String =
        lowercase().replace(" ", "").replace(".", "")
}

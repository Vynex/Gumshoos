package com.vynex.gumshoos.answers

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import java.time.ZoneId
import java.util.Currency
import java.util.Locale

/**
 * Builds an [AnswerContext] from the device.
 *
 * Kept apart from the providers deliberately: this is the only Android-dependent piece, so the
 * providers and their tests stay on plain JVM types. android.icu in particular is unavailable to
 * unit tests, which is why [MeasurementSystem] is our own enum rather than ICU's.
 */
object AnswerContextFactory {

    fun forDevice(context: Context, rates: RateTable? = null): AnswerContext {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return AnswerContext(
            measurementSystem = measurementSystemOf(locale),
            currencyCode = currencyOf(locale),
            zoneId = ZoneId.systemDefault().id,
            locale = locale,
            rates = rates,
        )
    }

    private fun measurementSystemOf(locale: Locale): MeasurementSystem =
        runCatching {
            when (LocaleData.getMeasurementSystem(ULocale.forLocale(locale))) {
                LocaleData.MeasurementSystem.US -> MeasurementSystem.US
                LocaleData.MeasurementSystem.UK -> MeasurementSystem.UK
                else -> MeasurementSystem.SI
            }
        }.getOrDefault(MeasurementSystem.SI)

    private fun currencyOf(locale: Locale): String =
        runCatching { Currency.getInstance(locale).currencyCode }.getOrDefault("USD")
}

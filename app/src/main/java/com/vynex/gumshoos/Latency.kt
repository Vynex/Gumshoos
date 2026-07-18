package com.vynex.gumshoos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One measured show: service entry -> frame committed to the display.
 *
 * This deliberately does NOT include the launcher's touch handling or the binder hops that carry
 * the PendingIntent into our process — we can't timestamp inside the launcher. Budget roughly
 * 5-20ms on top of whatever this reports for the true tap-to-pixels number.
 */
data class ShowSample(
    val source: String,
    val strategy: PrewarmStrategy,
    val millis: Long,
    val coldAttach: Boolean,
)

/** Shared so the overlay can render its own latency — the point is to read it on-device. */
object Latency {
    var last by mutableStateOf<ShowSample?>(null)
        private set

    private val history = ArrayDeque<ShowSample>()

    fun record(sample: ShowSample) {
        last = sample
        history.addLast(sample)
        while (history.size > 20) history.removeFirst()
    }

    /** Median is a better summary than mean here — one GC pause shouldn't dominate the readout. */
    fun medianMillis(): Long? {
        if (history.isEmpty()) return null
        val sorted = history.map { it.millis }.sorted()
        return sorted[sorted.size / 2]
    }

    fun count(): Int = history.size

    fun reset() {
        history.clear()
        last = null
    }
}

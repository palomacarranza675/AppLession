package com.example.lessionapp.ui

import kotlin.math.sqrt

data class SessionStats(
    val sampleCount: Int = 0,
    val currentValue: Int = 0,
    val mean: Double = 0.0,
    val median: Double = 0.0,
    val mode: List<Int> = emptyList(),
    val min: Int = 0,
    val max: Int = 0,
    val range: Int = 0,
    val variance: Double = 0.0,
    val standardDeviation: Double = 0.0,
    val coefficientOfVariation: Double = 0.0,
    val q1: Double = 0.0,
    val q2: Double = 0.0,
    val q3: Double = 0.0,
    val p10: Double = 0.0,
    val p90: Double = 0.0
)

object StatisticsCalculator {
    fun compute(samples: List<Int>): SessionStats {
        if (samples.isEmpty()) return SessionStats()

        val count = samples.size
        val sorted = samples.sorted()
        val current = samples.last()
        val min = sorted.first()
        val max = sorted.last()
        val range = max - min
        val mean = samples.average()

        val median = percentileSorted(sorted, 50.0)
        val q1 = percentileSorted(sorted, 25.0)
        val q3 = percentileSorted(sorted, 75.0)
        val p10 = percentileSorted(sorted, 10.0)
        val p90 = percentileSorted(sorted, 90.0)

        // Variance & Standard Deviation (sample variance, n - 1)
        val variance = if (count > 1) {
            samples.sumOf { (it - mean) * (it - mean) } / (count - 1)
        } else {
            0.0
        }
        val stdDev = sqrt(variance)

        // Coefficient of variation (%)
        val cv = if (mean != 0.0 && !mean.isNaN()) {
            (stdDev / mean) * 100.0
        } else {
            0.0
        }

        // Mode calculation
        val freqMap = samples.groupingBy { it }.eachCount()
        val maxFreq = freqMap.values.maxOrNull() ?: 1
        val mode = if (maxFreq > 1) {
            freqMap.filter { it.value == maxFreq }.keys.sorted()
        } else {
            emptyList()
        }

        return SessionStats(
            sampleCount = count,
            currentValue = current,
            mean = mean,
            median = median,
            mode = mode,
            min = min,
            max = max,
            range = range,
            variance = variance,
            standardDeviation = stdDev,
            coefficientOfVariation = cv,
            q1 = q1,
            q2 = median,
            q3 = q3,
            p10 = p10,
            p90 = p90
        )
    }

    private fun percentileSorted(sorted: List<Int>, percentile: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0].toDouble()

        val index = (percentile / 100.0) * (sorted.size - 1)
        val lower = index.toInt()
        val upper = lower + 1
        val weight = index - lower

        return if (upper >= sorted.size) {
            sorted.last().toDouble()
        } else {
            sorted[lower] + weight * (sorted[upper] - sorted[lower])
        }
    }
}

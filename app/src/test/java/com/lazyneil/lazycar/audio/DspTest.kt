package com.lazyneil.lazycar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class DspTest {
    @Test fun resamplerKeepsLengthAndRatio() {
        val rs = Resampler()
        var outFrames = 0
        repeat(1000) { outFrames += rs.process(ShortArray(480) { (it * 7 % 1000).toShort() }, 1.005).size / 2 }
        assertEquals(240_000 / 1.005, outFrames.toDouble(), 2.0)
    }

    @Test fun limiterNeverClipsAndPassesQuiet() {
        val y = soften(floatArrayOf(32767f, -32768f, 1000f))
        assertTrue(y[0] < 32767 && y[1] > -32767)
        assertEquals(2000.0, y[2].toDouble(), 2.0)
    }

    @Test fun fftRoundTrip() {
        val re = DoubleArray(64) { sin(it * 0.3) }; val im = DoubleArray(64); val orig = re.copyOf()
        fft(re, im, false); fft(re, im, true)
        for (i in re.indices) assertTrue(abs(re[i] - orig[i]) < 1e-9)
    }
}

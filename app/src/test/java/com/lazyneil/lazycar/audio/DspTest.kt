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
        val lim = Limiter()
        // 1 s of a full scale 1 kHz sine (+6 dB gain puts it 7 dB over the ceiling), then quiet.
        val loud = lim.process(FloatArray(96000) { 32767f * sin(2 * Math.PI * 1000 * (it / 2) / 48000).toFloat() })
        assertEquals(0L, lim.clipped)
        assertTrue(loud.drop(9600).maxOf { abs(it.toInt()) } <= 29200)          // about -1 dBFS
        var quiet = ShortArray(0); repeat(20) { quiet = lim.process(FloatArray(9600) { 1000f }) }   // 2 s release
        assertEquals(2000.0, quiet.last().toDouble(), 20.0)
    }

    @Test fun fftRoundTrip() {
        val re = DoubleArray(64) { sin(it * 0.3) }; val im = DoubleArray(64); val orig = re.copyOf()
        fft(re, im, false); fft(re, im, true)
        for (i in re.indices) assertTrue(abs(re[i] - orig[i]) < 1e-9)
    }
}

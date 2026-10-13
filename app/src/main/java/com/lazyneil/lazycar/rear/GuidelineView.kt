package com.lazyneil.lazycar.rear

import com.lazyneil.lazycar.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.Surface
import android.view.View
import kotlin.math.abs

/**
 * Parking guide lines like a factory backup camera: two converging rails with
 * red/yellow/green distance bands. The tablet's gyroscope bends them with the
 * car's actual turn rate while reversing (a tablet has no steering-angle
 * sensor, so the bend responds once the car is turning, not when the wheel is
 * merely turned while stationary).
 */
class GuidelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), SensorEventListener {

    companion object {
        /** px of sideways bend per RADIAN of accumulated turn. Negative flips
         *  direction, tune sign so the lines bend the same way as the
         *  (mirrored) video. */
        private const val BEND_PER_RAD = -560f

        /** Gyro readings below this (rad/s ≈ 1.1°/s) are treated as noise and
         *  used to learn the sensor's zero-rate bias instead of as movement. */
        private const val NOISE_FLOOR = 0.02f
        private const val BIAS_LEARN_RATE = 0.02f

        /** The accumulated angle leaks back to straight with this time
         *  constant (seconds). Long enough that a held turn visibly STAYS
         *  bent, short enough that gyro drift can't leave the lines
         *  permanently crooked. */
        private const val RECENTER_TAU = 25f

        /** Clamp on the accumulated turn (rad ≈ 51°). */
        private const val MAX_ANGLE = 0.9f

        /** Largest sideways bend, as a fraction of view width. */
        private const val MAX_BEND_FRACTION = 0.26f
    }

    /**
     * A gyroscope measures turn RATE, not angle. Drawing the bend straight
     * from the rate meant the lines snapped back to centre the instant the
     * turn rate hit zero, they only bent *while* the turn was accelerating.
     * Integrating the rate gives the angle actually turned through, so the
     * lines hold their bend for as long as the car is held in the turn.
     */
    private var heading = 0f

    /** Learned zero-rate offset, every gyro reads slightly non-zero at rest,
     *  and integrating that raw would slowly walk the lines off to one side. */
    private var bias = 0f
    private var lastEventNs = 0L

    private var bend = 0f
    private var drawnBend = 0f
    private var sensorManager: SensorManager? = null

    private val underPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 13f
        color = 0x66000000
        strokeCap = Paint.Cap.ROUND
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    private val bandColors = intArrayOf(0xE6E53935.toInt(), 0xE6FDD835.toInt(), 0xE643A047.toInt())

    // t runs 0 (bottom/near) -> 1 (top/far); band boundaries along the rails
    private val bandEnds = floatArrayOf(0f, 0.45f, 0.80f, 1f)

    private val rail = Path()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Start every session straight ahead rather than inheriting whatever
        // angle the last one ended on.
        heading = 0f
        lastEventNs = 0L
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onDetachedFromWindow() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(event: SensorEvent) {
        // The gyro's axes are fixed to the tablet's NATURAL orientation, which
        // differs per tablet (portrait-natural tablets have Y running along
        // the long side). The car's turn is rotation about the SCREEN's
        // vertical axis, so remap by the current display rotation, otherwise
        // tilting the tablet up/down bends the lines instead of turning.
        val yaw = when (display?.rotation) {
            Surface.ROTATION_90 -> event.values[0]
            Surface.ROTATION_270 -> -event.values[0]
            Surface.ROTATION_180 -> -event.values[1]
            else -> event.values[1]
        }

        // First event only establishes the clock; there is no interval yet.
        if (lastEventNs == 0L) {
            lastEventNs = event.timestamp
            return
        }
        // Cap dt so a delivery hiccup (or a suspended screen) can't integrate
        // one stale sample into a huge jump.
        val dt = ((event.timestamp - lastEventNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastEventNs = event.timestamp

        val rate = yaw - bias
        if (abs(rate) < NOISE_FLOOR) {
            bias += (yaw - bias) * BIAS_LEARN_RATE
        } else {
            heading += rate * dt
        }
        heading -= heading * (dt / RECENTER_TAU)
        heading = heading.coerceIn(-MAX_ANGLE, MAX_ANGLE)

        val limit = width * MAX_BEND_FRACTION
        bend = (heading * BEND_PER_RAD).coerceIn(-limit, limit)
        if (abs(bend - drawnBend) > 0.5f) invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // Rail geometry: x position of the left/right rail at parameter t,
    // with the gyro bend growing quadratically toward the far end.
    private fun railX(left: Boolean, t: Float): Float {
        val w = width.toFloat()
        val xBottom = if (left) w * 0.14f else w * 0.86f
        val xTop = if (left) w * 0.38f else w * 0.62f
        return xBottom + (xTop - xBottom) * t + bend * t * t
    }

    private fun railY(t: Float): Float {
        val h = height.toFloat()
        return h * 0.94f + (h * 0.46f - h * 0.94f) * t
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawnBend = bend

        // under-strokes first (dark outline for contrast), then color
        for (pass in 0..1) {
            val paint = if (pass == 0) underPaint else bandPaint

            for (band in 0 until 3) {
                if (pass == 1) bandPaint.color = bandColors[band]
                val t0 = bandEnds[band]
                val t1 = bandEnds[band + 1]

                // side rails for this band, sampled so the bend stays smooth
                for (left in booleanArrayOf(true, false)) {
                    rail.reset()
                    rail.moveTo(railX(left, t0), railY(t0))
                    var i = 1
                    while (i <= 6) {
                        val t = t0 + (t1 - t0) * i / 6f
                        rail.lineTo(railX(left, t), railY(t))
                        i++
                    }
                    canvas.drawPath(rail, paint)
                }

                // horizontal distance bar at the near edge of this band;
                // the red (nearest) bar overhangs the rails like on real cars
                val overhang = if (band == 0) width * 0.07f else 0f
                val y = railY(t0)
                canvas.drawLine(
                    railX(true, t0) - overhang, y,
                    railX(false, t0) + overhang, y, paint
                )
                // small center tick on the red and yellow bars
                if (band < 2) {
                    val cx = (railX(true, t0) + railX(false, t0)) / 2
                    canvas.drawLine(cx, y, cx, y - 18f, paint)
                }
            }

            // far (green) bar closing the top of the ladder
            val yTop = railY(1f)
            canvas.drawLine(railX(true, 1f), yTop, railX(false, 1f), yTop, paint)
        }
    }
}

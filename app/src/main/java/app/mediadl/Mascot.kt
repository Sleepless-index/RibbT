package app.mediadl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class Mood { Normal, Thinking, Idea, Error }

private const val STIFF = 0.10f
private const val DAMP = 0.76f
private const val TWO_PI = (2.0 * PI).toFloat()

private val PILL = floatArrayOf(0f, -7.5f, 0f, -2.5f, 0f, 2.5f, 0f, 7.5f)
private val IDEA = floatArrayOf(-14f, 6f, -14f, -12.67f, 14f, -12.67f, 14f, 6f)
private val X_A = floatArrayOf(-13f, -13f, -4.33f, -4.33f, 4.33f, 4.33f, 13f, 13f)
private val X_B = floatArrayOf(-13f, 13f, -4.33f, 4.33f, 4.33f, -4.33f, 13f, -13f)

private const val CLOUD_PATH =
    "M 140 50 L 200 50 A 13 13 0 0 0 200 24 A 14 14 0 0 0 180 10 A 14 14 0 0 0 156 14 A 13 13 0 0 0 140 30 A 11 11 0 0 0 140 50 Z"
private const val STAR_LARGE = "M 0 -13 L 3 -3 L 13 0 L 3 3 L 0 13 L -3 3 L -13 0 L -3 -3 Z"
private const val STAR_SMALL = "M 0 -6 L 1.4 -1.4 L 6 0 L 1.4 1.4 L 0 6 L -1.4 1.4 L -6 0 L -1.4 -1.4 Z"
private const val STAR_MID = "M 0 -8.5 L 2 -2 L 8.5 0 L 2 2 L 0 8.5 L -2 2 L -8.5 0 L -2 -2 Z"

private fun smooth(v: Float): Float = v * v * (3f - 2f * v)

private fun piecewise(t: Float, stops: FloatArray, values: FloatArray): Float {
    if (t <= stops.first()) return values.first()
    for (i in 1 until stops.size) {
        if (t <= stops[i]) {
            val span = stops[i] - stops[i - 1]
            val f = if (span == 0f) 1f else (t - stops[i - 1]) / span
            return values[i - 1] + (values[i] - values[i - 1]) * f
        }
    }
    return values.last()
}

private val HOP_STOPS = floatArrayOf(0f, 0.18f, 0.45f, 0.75f, 1f)
private val HOP_Y = floatArrayOf(0f, 0f, -10f, 0f, 0f)
private val HOP_SX = floatArrayOf(1f, 1.05f, 0.97f, 1.05f, 1f)
private val HOP_SY = floatArrayOf(1f, 0.94f, 1.05f, 0.94f, 1f)
private val SHAKE_STOPS = floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f)
private val SHAKE_X = floatArrayOf(0f, -2f, 2f, -1f, 0f)

private class Eye(val baseX: Float) {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var tx = 0f
    var ty = 0f
    var sy = 1f
    var vsy = 0f
    var ty2 = 1f
}

private class MascotSim {
    val eyes = arrayOf(Eye(76f), Eye(124f))
    val a = FloatArray(8)
    val b = FloatArray(8)
    var w = 13f
    var wPill = 1f
    var wIdea = 0f
    var wX = 0f
    var headX = 0f
    var headY = 0f
    var headRot = 0f
    var driveX = 0f
    var driveY = 0f
    var driveTX = 0f
    var driveTY = 0f
    var earRotL = 0f
    var earRotR = 0f
    var earTrX = 0f
    var earTrY = 0f
    var perk = 0f
    var cloud = 0f
    var wanderX = 0f
    var wanderY = 0f
    var nextWander = 0f
    var saccX = 0f
    var saccY = 0f
    var nextSacc = 0f
    var nextBlink = 2500f
    var blinkStart = -10000f
    var blink = 1f
    var ideaStart = -10000f
    var shakeStart = -10000f
    var hopStart = -10000f
    var lastMood = Mood.Normal
    var lastTap = 0
    var lastHop = 0
    var now = 0f
    var shakeX = 0f
    var hopY = 0f
    var hopSx = 1f
    var hopSy = 1f

    fun step(t: Float, dtMs: Float, mood: Mood, tap: Int, hopSignal: Int) {
        now = t
        val dtN = dtMs / 16.667f
        val damp = DAMP.pow(dtN)
        val k = 1f - 0.86f.pow(dtN)

        if (mood != lastMood) {
            if (mood == Mood.Error) shakeStart = t
            if (mood == Mood.Idea) {
                ideaStart = t
                hopStart = t
            }
            lastMood = mood
        }
        if (tap != lastTap) {
            lastTap = tap
            hopStart = t
            blinkStart = t
        }
        if (hopSignal != lastHop) {
            lastHop = hopSignal
            hopStart = t
        }

        val live = mood == Mood.Normal || mood == Mood.Thinking

        val km = 1f - 0.8f.pow(dtN)
        wPill += ((if (mood == Mood.Normal || mood == Mood.Thinking) 1f else 0f) - wPill) * km
        wIdea += ((if (mood == Mood.Idea) 1f else 0f) - wIdea) * km
        wX += ((if (mood == Mood.Error) 1f else 0f) - wX) * km
        val sum = (wPill + wIdea + wX).coerceAtLeast(0.0001f)
        val mp = smooth((wPill / sum).coerceIn(0f, 1f))
        val mi = smooth((wIdea / sum).coerceIn(0f, 1f))
        val mx = smooth((wX / sum).coerceIn(0f, 1f))
        val msum = (mp + mi + mx).coerceAtLeast(0.0001f)
        val fp = mp / msum
        val fi = mi / msum
        val fx = mx / msum
        for (i in 0 until 8) {
            a[i] = PILL[i] * fp + IDEA[i] * fi + X_A[i] * fx
            b[i] = PILL[i] * fp + IDEA[i] * fi + X_B[i] * fx
        }
        w = 13f * fp + 6f * fi + 5.5f * fx

        if (t >= nextBlink) {
            if (live) blinkStart = t
            nextBlink = t + 2200f + Random.nextFloat() * 4200f
        }
        val bt = (t - blinkStart) / 160f
        blink = if (bt in 0f..1f) 1f - 0.92f * sin(bt * PI.toFloat()) else 1f

        var ny = 0f
        if (live) {
            if (t >= nextSacc) {
                val angle = Random.nextFloat() * TWO_PI
                val radius = 0.85f * sqrt(Random.nextFloat())
                saccX = cos(angle) * radius
                saccY = sin(angle) * radius
                nextSacc = t + 700f + Random.nextFloat() * 1400f
            }
            var nx: Float
            var travelX = 4.6f
            var travelY = 8.5f
            if (mood == Mood.Thinking) {
                val s = 0.5f - 0.5f * cos(((t % 1600f) / 1600f) * TWO_PI)
                var dx = (178f + 30f * s - 100f) / 80f
                var dy = (22f - 144f) / 80f
                val m = hypot(dx, dy)
                if (m > 1f) {
                    dx /= m
                    dy /= m
                }
                nx = dx
                ny = dy
                travelX = 6f
                travelY = 9f
            } else {
                if (t >= nextWander) {
                    val angle = Random.nextFloat() * TWO_PI
                    val radius = sqrt(Random.nextFloat())
                    wanderX = cos(angle) * radius
                    wanderY = sin(angle) * radius
                    nextWander = t + 800f + Random.nextFloat() * 900f
                }
                nx = wanderX
                ny = wanderY
            }
            val floatX = sin((t / 5400f) * TWO_PI) * 1.15f
            val floatY = sin((t / 4100f) * TWO_PI + 1.2f) * 1.15f * 0.65f
            val gx = nx * travelX + saccX + floatX
            val gy = ny * travelY + saccY + floatY
            driveTX = gx
            driveTY = gy
            val squash = 1f - abs(ny) * 0.07f
            for (e in eyes) {
                e.tx = gx
                e.ty = gy
                e.ty2 = squash
            }
        } else {
            driveTX = 0f
            driveTY = 0f
            for (e in eyes) {
                e.tx = 0f
                e.ty = 0f
                e.ty2 = 1f
            }
        }

        for (e in eyes) {
            e.vx = (e.vx + (e.tx - e.x) * STIFF * dtN) * damp
            e.vy = (e.vy + (e.ty - e.y) * STIFF * dtN) * damp
            e.x += e.vx * dtN
            e.y += e.vy * dtN
            e.vsy = (e.vsy + (e.ty2 - e.sy) * STIFF * dtN) * damp
            e.sy += e.vsy * dtN
        }

        driveX += (driveTX - driveX) * k
        driveY += (driveTY - driveY) * k
        headX += (driveX * 0.30f - headX) * k
        headY += (driveY * 0.42f - headY) * k
        headRot += (driveX * 0.40f - headRot) * k

        perk += ((if (mood == Mood.Idea) 1f else 0f) - perk) * (1f - 0.85f.pow(dtN))
        cloud += ((if (mood == Mood.Thinking) 1f else 0f) - cloud) * (1f - 0.8f.pow(dtN))

        val lean = driveX * 1.6f
        val swayL = sin(t / 2700f) * 2f
        val swayR = sin(t / 2700f + 2.1f) * 2f
        earRotL += ((lean + swayL - 6f * perk) - earRotL) * k
        earRotR += ((lean + swayR + 6f * perk) - earRotR) * k
        earTrX += (driveX * 0.95f - earTrX) * k
        earTrY += ((driveY * 0.55f - 3f * perk) - earTrY) * k

        val hopT = (t - hopStart) / 460f
        if (hopT in 0f..1f) {
            hopY = piecewise(hopT, HOP_STOPS, HOP_Y)
            hopSx = piecewise(hopT, HOP_STOPS, HOP_SX)
            hopSy = piecewise(hopT, HOP_STOPS, HOP_SY)
        } else {
            hopY = 0f
            hopSx = 1f
            hopSy = 1f
        }
        val shakeT = (t - shakeStart) / 320f
        shakeX = if (shakeT in 0f..1f) piecewise(shakeT, SHAKE_STOPS, SHAKE_X) else 0f
    }
}

private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val p = t - 1f
    return 1f + c3 * p * p * p + c1 * p * p
}

private fun DrawScope.drawEar(centerX: Float, tilt: Float, trX: Float, trY: Float, rot: Float) {
    withTransform({
        translate(trX, trY)
        rotate(rot, Offset(centerX, 112f))
        rotate(tilt, Offset(centerX, 66f))
    }) {
        drawOval(Accent, Offset(centerX - 17f, 16f), Size(34f, 100f))
    }
}

private fun curve(p: FloatArray): Path = Path().apply {
    moveTo(p[0], p[1])
    cubicTo(p[2], p[3], p[4], p[5], p[6], p[7])
}

private fun thinkPulse(t: Float, delay: Float): Float {
    val p = (((t - delay) % 1100f) + 1100f) % 1100f / 1100f
    return when {
        p <= 0.30f -> 0.3f + 0.7f * smooth(p / 0.30f)
        p <= 0.65f -> 1f - 0.7f * smooth((p - 0.30f) / 0.35f)
        else -> 0.3f
    }
}

@Composable
fun Mascot(mood: Mood, hopSignal: Int, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val sim = remember { MascotSim() }
    var frame by remember { mutableIntStateOf(0) }
    var tapKey by remember { mutableIntStateOf(0) }
    val currentMood by rememberUpdatedState(mood)
    val currentHop by rememberUpdatedState(hopSignal)
    val currentTap by rememberUpdatedState(tapKey)
    val cloudPath = remember { PathParser().parsePathString(CLOUD_PATH).toPath() }
    val starLarge = remember { PathParser().parsePathString(STAR_LARGE).toPath() }
    val starSmall = remember { PathParser().parsePathString(STAR_SMALL).toPath() }
    val starMid = remember { PathParser().parsePathString(STAR_MID).toPath() }

    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        var last = start
        while (true) {
            androidx.compose.runtime.withFrameNanos { nanos ->
                val dt = ((nanos - last) / 1_000_000f).coerceIn(0f, 64f)
                last = nanos
                sim.step((nanos - start) / 1_000_000f, dt, currentMood, currentTap, currentHop)
                frame++
            }
        }
    }

    Canvas(
        modifier
            .width(76.dp)
            .height(85.88.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { tapKey++ },
    ) {
        val tick = frame
        if (tick < 0) return@Canvas
        val factor = size.width / 200f
        val t = sim.now
        withTransform({
            scale(factor, factor, Offset.Zero)
            translate(sim.shakeX, sim.hopY)
            scale(sim.hopSx, sim.hopSy, Offset(100f, 226f))
        }) {
            withTransform({
                translate(sim.headX, sim.headY)
                rotate(sim.headRot, Offset(100f, 152f))
            }) {
                drawEar(74f, -10f, sim.earTrX, sim.earTrY, sim.earRotL)
                drawEar(126f, 10f, sim.earTrX, sim.earTrY, sim.earRotR)
                drawOval(Accent, Offset(36f, 94f), Size(128f, 116f))
                val pa = curve(sim.a)
                val pb = curve(sim.b)
                val style = Stroke(width = sim.w, cap = StrokeCap.Round, join = StrokeJoin.Round)
                for (e in sim.eyes) {
                    withTransform({
                        translate(e.baseX + e.x, 144f + e.y)
                        scale(1f, (e.sy * sim.blink).coerceAtLeast(0.02f), Offset.Zero)
                    }) {
                        drawPath(pa, Color.White, style = style)
                        drawPath(pb, Color.White, style = style)
                    }
                }
            }

            if (sim.cloud > 0.01f) {
                val alpha = sim.cloud.coerceIn(0f, 1f)
                val popScale = 0.2f + 0.8f * alpha
                val bob = -2.5f * (0.5f - 0.5f * cos((t / 2600f) * TWO_PI))
                val thin = Stroke(width = 3.2f, join = StrokeJoin.Round)
                withTransform({
                    translate(0f, bob)
                    scale(popScale, popScale, Offset(176f, 44f))
                }) {
                    drawCircle(palette.card, 3.2f, Offset(156f, 86f), alpha)
                    drawCircle(Accent, 3.2f, Offset(156f, 86f), alpha, thin)
                    drawCircle(palette.card, 5.4f, Offset(165f, 68f), alpha)
                    drawCircle(Accent, 5.4f, Offset(165f, 68f), alpha, thin)
                    withTransform({ translate(22f, -6f) }) {
                        drawPath(cloudPath, palette.card, alpha)
                        drawPath(cloudPath, Accent, alpha, thin)
                    }
                    val xs = floatArrayOf(178f, 192f, 206f)
                    for (i in 0 until 3) {
                        val f = thinkPulse(t, i * 160f)
                        val sc = 0.8f + (f - 0.3f) / 0.7f * 0.3f
                        drawCircle(Accent, 4.6f * sc, Offset(xs[i], 28f), f * alpha)
                    }
                }
            }

            if (sim.perk > 0.01f) {
                val stars = arrayOf(
                    Triple(Offset(166f, 72f), starLarge, 0f),
                    Triple(Offset(187f, 104f), starSmall, 100f),
                    Triple(Offset(139f, 50f), starMid, 200f),
                )
                for ((position, star, delay) in stars) {
                    val age = t - sim.ideaStart - delay
                    if (age < 0f) continue
                    val pop = if (age < 500f) easeOutBack(age / 500f) else 1f
                    val tw = if (age > 500f) 0.5f - 0.5f * cos(((age - 500f) / 1700f) * TWO_PI) else 0f
                    val sc = (pop * (1f - 0.4f * tw)).coerceAtLeast(0f)
                    withTransform({
                        translate(position.x, position.y)
                        rotate(35f * tw, Offset.Zero)
                        scale(sc, sc, Offset.Zero)
                    }) {
                        drawPath(star, Accent, sim.perk * (1f - 0.55f * tw))
                    }
                }
            }
        }
    }
}

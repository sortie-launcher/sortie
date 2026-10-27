package io.github.esko997.sortie.mecha

import kotlin.math.abs

/** Small stable PRNG: ticket seeds reproduce courses and boards across process recreation. */
internal class MechaArcadeRandom(seed: Long) {
    private var value = if (seed == 0L) -7046029254386353131L else seed

    fun nextInt(bound: Int): Int {
        value = value xor (value shl 13)
        value = value xor (value ushr 7)
        value = value xor (value shl 17)
        return ((value ushr 1) % bound).toInt()
    }
}

/** Sixty drops, every fourth junk: a fixed six-slot pool is sufficient for their entire fall. */
internal class MechaScrapEngine(seed: Long) {
    companion object {
        const val DROP_COUNT = 6
        const val ROUND_NANOS = 45_000_000_000L
        const val TRAY_Y = 0.84f
        const val TRAY_HALF = 0.105f
    }

    private val random = MechaArcadeRandom(seed)
    private val x = FloatArray(DROP_COUNT)
    private val y = FloatArray(DROP_COUNT)
    private val junk = BooleanArray(DROP_COUNT)
    private val active = BooleanArray(DROP_COUNT)
    private var spawned = 0
    var elapsedNanos = 0L
        private set
    var tray = 0.5f
        private set
    var direction = 0
    var score = 0
        private set
    val ended: Boolean get() = elapsedNanos >= ROUND_NANOS
    val secondsLeft: Int get() = ((ROUND_NANOS - elapsedNanos + 999_999_999L) / 1_000_000_000L).toInt()

    fun x(index: Int): Float = x[index]

    fun y(index: Int): Float = y[index]

    fun junk(index: Int): Boolean = junk[index]

    fun active(index: Int): Boolean = active[index]

    fun moveTo(position: Float) {
        if (!ended && position.isFinite()) tray = position.coerceIn(TRAY_HALF, 1f - TRAY_HALF)
    }

    fun step() {
        if (ended) return
        val dt = MechaFlightEngine.STEP_NANOS / 1_000_000_000f
        moveTo(tray + direction.coerceIn(-1, 1) * 0.8f * dt)
        if (spawned < 60 && elapsedNanos >= spawned * 700_000_000L) {
            for (i in 0 until DROP_COUNT) {
                if (!active[i]) {
                    x[i] = 0.12f + random.nextInt(761) / 1000f
                    y[i] = 0f
                    junk[i] = spawned % 4 == 3
                    active[i] = true
                    spawned++
                    break
                }
            }
        }
        for (i in 0 until DROP_COUNT) {
            if (!active[i]) continue
            y[i] += 0.36f * dt
            if (y[i] >= TRAY_Y) {
                if (abs(x[i] - tray) <= TRAY_HALF) score = (score + if (junk[i]) -5 else 10).coerceAtLeast(0)
                active[i] = false
            }
        }
        elapsedNanos = (elapsedNanos + MechaFlightEngine.STEP_NANOS).coerceAtMost(ROUND_NANOS)
    }
}

/** No ticking while choosing: only an unmatched pair's finite reveal needs elapsed time. */
internal class MechaCircuitEngine(seed: Long) {
    companion object {
        const val CELLS = 12
        const val MAX_ATTEMPTS = 24
        const val REVEAL_NANOS = 850_000_000L
    }

    private val board = IntArray(CELLS) { it / 2 }
    private val matched = BooleanArray(CELLS)
    var first = -1
        private set
    var second = -1
        private set
    var attempts = 0
        private set
    var pairs = 0
        private set
    var remainingReveal = 0L
        private set
    var elapsedNanos = 0L
        private set
    var reactionNanos = 0L
        private set
    val ended: Boolean get() = pairs == 6 || (attempts == MAX_ATTEMPTS && remainingReveal == 0L)
    val score: Int get() = pairs * 10 + if (pairs == 6) (MAX_ATTEMPTS - attempts) * 5 else 0

    init {
        val random = MechaArcadeRandom(seed)
        for (i in CELLS - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val old = board[i]
            board[i] = board[j]
            board[j] = old
        }
    }

    fun symbol(index: Int): Int = board[index]

    fun matched(index: Int): Boolean = matched[index]

    fun revealed(index: Int): Boolean = matched[index] || first == index || second == index

    fun choose(index: Int): Boolean {
        if (index !in 0 until CELLS || ended || remainingReveal > 0L || matched[index] || index == first) return false
        if (first < 0) {
            first = index
        } else {
            second = index
            attempts++
            if (board[first] == board[second]) {
                matched[first] = true
                matched[second] = true
                pairs++
                reactionNanos = 800_000_000L
                first = -1
                second = -1
            } else {
                remainingReveal = REVEAL_NANOS
            }
        }
        return true
    }

    /** True only when the visible board changes, so the UI need not recompose on each reveal frame. */
    fun step(): Boolean {
        if (remainingReveal == 0L && reactionNanos == 0L) return false
        elapsedNanos += MechaFlightEngine.STEP_NANOS
        var changed = false
        if (reactionNanos > 0L) {
            reactionNanos = (reactionNanos - MechaFlightEngine.STEP_NANOS).coerceAtLeast(0L)
            changed = reactionNanos == 0L
        }
        if (remainingReveal > 0L) {
            remainingReveal = (remainingReveal - MechaFlightEngine.STEP_NANOS).coerceAtLeast(0L)
            if (remainingReveal == 0L) {
                first = -1
                second = -1
                changed = true
            }
        }
        return changed
    }
}

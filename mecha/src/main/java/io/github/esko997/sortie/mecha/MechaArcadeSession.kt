package io.github.esko997.sortie.mecha

import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun MechaArcadeGame.acceptsScore(score: Int): Boolean = when (this) {
    MechaArcadeGame.FLAPPY_FLIGHT -> score >= 0
    MechaArcadeGame.SCRAP_CATCH -> score in 0..450 && score % 5 == 0
    MechaArcadeGame.CIRCUIT_MATCH -> (score in 0..50 && score % 10 == 0) || (score in 60..150 && score % 5 == 0)
}

/** Fixed-storage, fixed-step flight simulation. Coordinates are fractions of the playfield. */
internal class MechaFlightEngine(seed: Long) {
    companion object {
        const val PIPE_COUNT = 4
        const val PIPE_WIDTH = 0.12f
        const val PIPE_SPACING = 0.57f
        const val GAP_HEIGHT = 0.34f
        const val BOT_X = 0.25f
        const val BOT_RADIUS = 0.035f
        const val STEP_NANOS = 8_333_333L
        private const val STEP_SECONDS = STEP_NANOS / 1_000_000_000f
        private const val SPEED = 0.27f
        private const val GRAVITY = 1.35f
        private const val FLAP_VELOCITY = -0.49f
    }

    private var random = if (seed == 0L) -7046029254386353131L else seed
    private val pipeX = FloatArray(PIPE_COUNT)
    private val gapY = FloatArray(PIPE_COUNT)
    private val passed = BooleanArray(PIPE_COUNT)
    var y = 0.5f
        private set
    var velocity = 0f
        private set
    var score = 0
        private set
    var ended = false
        private set
    var elapsedNanos = 0L
        private set

    init {
        for (i in 0 until PIPE_COUNT) {
            pipeX[i] = 1.05f + i * PIPE_SPACING
            gapY[i] = nextGap()
        }
    }

    fun pipeX(index: Int): Float = pipeX[index]

    fun gapY(index: Int): Float = gapY[index]

    fun flap() {
        if (!ended) velocity = FLAP_VELOCITY
    }

    /** Exactly one simulation step; callers bound catch-up rather than replaying time spent hidden. */
    fun step() {
        if (ended) return
        elapsedNanos += STEP_NANOS
        velocity += GRAVITY * STEP_SECONDS
        y += velocity * STEP_SECONDS
        if (y - BOT_RADIUS <= 0f || y + BOT_RADIUS >= 1f) {
            ended = true
            return
        }
        for (i in 0 until PIPE_COUNT) {
            pipeX[i] -= SPEED * STEP_SECONDS
            val overlaps = BOT_X + BOT_RADIUS >= pipeX[i] && BOT_X - BOT_RADIUS <= pipeX[i] + PIPE_WIDTH
            if (overlaps && (y - BOT_RADIUS <= gapY[i] - GAP_HEIGHT / 2 || y + BOT_RADIUS >= gapY[i] + GAP_HEIGHT / 2)) {
                ended = true
                return
            }
            if (!passed[i] && pipeX[i] + PIPE_WIDTH < BOT_X - BOT_RADIUS) {
                passed[i] = true
                if (score < Int.MAX_VALUE) score++
            }
        }
        for (i in 0 until PIPE_COUNT) {
            if (pipeX[i] + PIPE_WIDTH < 0f) {
                var rightmost = 0f
                for (j in 0 until PIPE_COUNT) rightmost = max(rightmost, pipeX[j])
                pipeX[i] = rightmost + PIPE_SPACING
                gapY[i] = nextGap()
                passed[i] = false
            }
        }
    }

    private fun nextGap(): Float {
        random = random xor (random shl 13)
        random = random xor (random ushr 7)
        random = random xor (random shl 17)
        return 0.30f + ((random ushr 40).toInt() / 16_777_215f) * 0.40f
    }
}

internal enum class MechaArcadePhase { EMPTY, CHOOSING, PAUSED, RUNNING, FINISHED, SAVED }

internal data class MechaArcadeRun(
    val ticket: MechaArcadeTicket? = null,
    val phase: MechaArcadePhase = MechaArcadePhase.EMPTY,
    val revision: Int = 0
)

/**
 * One store-owned runtime shared by every Hangar window. All calls run on the UI thread. Only the owning host can flap,
 * advance or pause a live run. No coroutine, clock or Android object is retained here; leaving a host clears its ownership.
 */
internal class MechaArcadeSession {
    private val mutableRun = MutableStateFlow(MechaArcadeRun())
    val run: StateFlow<MechaArcadeRun> = mutableRun.asStateFlow()
    var engine: MechaFlightEngine? = null
        private set
    var scrap: MechaScrapEngine? = null
        private set
    var circuit: MechaCircuitEngine? = null
        private set
    val score: Int get() = engine?.score ?: scrap?.score ?: circuit?.score ?: 0
    val elapsedNanos: Long get() = engine?.elapsedNanos ?: scrap?.elapsedNanos ?: circuit?.elapsedNanos ?: 0L
    val needsFrames: Boolean get() = engine != null || scrap != null || (circuit?.remainingReveal ?: 0L) > 0L || (circuit?.reactionNanos ?: 0L) > 0L
    private val ended: Boolean get() = engine?.ended ?: scrap?.ended ?: circuit?.ended ?: false
    private var owner: Any? = null
    private var accumulatedNanos = 0L
    private var finishRequested = false

    fun sync(ticket: MechaArcadeTicket?) {
        val previous = mutableRun.value
        if (previous.ticket == ticket) return
        if (ticket == null && previous.phase == MechaArcadePhase.FINISHED) {
            owner = null
            mutableRun.value = previous.copy(phase = MechaArcadePhase.SAVED)
            return
        }
        if (ticket == null && previous.phase == MechaArcadePhase.SAVED) return
        owner = null
        accumulatedNanos = 0L
        finishRequested = false
        engine = ticket?.takeIf { it.game == MechaArcadeGame.FLAPPY_FLIGHT }?.let { MechaFlightEngine(it.seed) }
        scrap = ticket?.takeIf { it.game == MechaArcadeGame.SCRAP_CATCH }?.let { MechaScrapEngine(it.seed) }
        circuit = ticket?.takeIf { it.game == MechaArcadeGame.CIRCUIT_MATCH }?.let { MechaCircuitEngine(it.seed) }
        val phase = when {
            ticket == null -> MechaArcadePhase.EMPTY
            ticket.game == null -> MechaArcadePhase.CHOOSING
            else -> MechaArcadePhase.PAUSED
        }
        mutableRun.value = MechaArcadeRun(ticket, phase)
    }

    fun owns(host: Any): Boolean = owner === host

    /** Two composed displays may observe the finish, but only one automatically requests its durable save. */
    fun claimFinish(): Boolean {
        if (mutableRun.value.phase != MechaArcadePhase.FINISHED || finishRequested) return false
        finishRequested = true
        return true
    }

    fun resume(host: Any): Boolean {
        val current = mutableRun.value
        if (owner != null || current.phase != MechaArcadePhase.PAUSED) return false
        owner = host
        accumulatedNanos = 0L
        mutableRun.value = current.copy(phase = MechaArcadePhase.RUNNING)
        return true
    }

    fun pause(host: Any) {
        if (!owns(host)) return
        owner = null
        scrap?.direction = 0
        accumulatedNanos = 0L
        val current = mutableRun.value
        if (current.phase == MechaArcadePhase.RUNNING) mutableRun.value = current.copy(phase = MechaArcadePhase.PAUSED)
    }

    fun flap(host: Any) {
        if (owns(host) && mutableRun.value.phase == MechaArcadePhase.RUNNING) engine?.flap()
    }

    fun steer(host: Any, direction: Int) {
        if (owns(host)) scrap?.direction = direction.coerceIn(-1, 1)
    }

    fun moveTray(host: Any, position: Float) {
        if (owns(host)) scrap?.moveTo(position)
    }

    fun choose(host: Any, index: Int) {
        if (!owns(host) || mutableRun.value.phase != MechaArcadePhase.RUNNING) return
        if (circuit?.choose(index) == true) {
            mutableRun.value = mutableRun.value.copy(revision = mutableRun.value.revision + 1)
            finishIfEnded()
        }
    }

    /** At most six fixed steps per frame; a stalled frame cannot turn into an unbounded catch-up loop. */
    fun advance(host: Any, deltaNanos: Long) {
        if (!owns(host) || mutableRun.value.phase != MechaArcadePhase.RUNNING) return
        accumulatedNanos += deltaNanos.coerceIn(0L, MechaFlightEngine.STEP_NANOS * 6)
        var steps = 0
        while (accumulatedNanos >= MechaFlightEngine.STEP_NANOS && steps < 6 && !ended) {
            engine?.step()
            scrap?.step()
            if (circuit?.step() == true) mutableRun.value = mutableRun.value.copy(revision = mutableRun.value.revision + 1)
            accumulatedNanos -= MechaFlightEngine.STEP_NANOS
            steps++
        }
        finishIfEnded()
    }

    private fun finishIfEnded() {
        if (ended) {
            owner = null
            accumulatedNanos = 0L
            mutableRun.value = mutableRun.value.copy(phase = MechaArcadePhase.FINISHED)
        }
    }
}

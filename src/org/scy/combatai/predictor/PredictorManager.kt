package org.scy.combatai.predictor


import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import kotlinx.coroutines.*
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.ext.getFacing
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import org.scy.combatai.ScyAiV2
import java.awt.Color
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// Global Coroutine scope so we don't leak thread pools between combat engagements
object PredictorThreadPool {
    private val threads = max(2, Runtime.getRuntime().availableProcessors())
    private val threadFactory = ThreadFactory { r ->
        Thread(r).apply {
            name = "ScyPredictor-Worker-${UUID.randomUUID().toString().take(4)}"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    val dispatcher = Executors.newFixedThreadPool(threads, threadFactory).asCoroutineDispatcher()
    val scope = CoroutineScope(dispatcher + SupervisorJob())
}

class PredictorManager private constructor() {

    companion object {
        const val DATA_KEY = "SCY_FLIGHT_PATH_PREDICTOR"
        fun getInstance(engine: CombatEngineAPI): PredictorManager {
            if (!engine.customData.containsKey(DATA_KEY)) {
                engine.customData[DATA_KEY] = PredictorManager()
            }
            return engine.customData[DATA_KEY] as PredictorManager
        }
    }

    @Volatile private var isSimulating = false
    private val outputRef = AtomicReference<SimulationOutput?>(null)

    private val pendingRequests = HashMap<RequestKey, Float>()
    private var latestResults: Map<RequestKey, RequestResult> = emptyMap()
    private var latestEnemyTimelines: Map<String, ShipTimeline> = emptyMap()

    private val resultTimestamps = HashMap<RequestKey, Float>()
    private val timelineTimestamps = HashMap<String, Float>()

    private fun isMatch(val1: Float?, val2: Float?, tolerance: Float): Boolean {
        if (val1 == val2) return true
        if (val1 == null || val2 == null) return false
        return abs(val1 - val2) <= tolerance
    }

    private fun isMobilityMatch(m1: MobilityProfile?, m2: MobilityProfile?): Boolean {
        if (m1 == m2) return true
        if (m1 == null || m2 == null) return false

        return isMatch(m1.phase1Duration, m2.phase1Duration, 0.5f) &&
                isMatch(m1.maxSpeedOverride1, m2.maxSpeedOverride1, 5f) &&
                isMatch(m1.accelOverride1, m2.accelOverride1, 5f) &&
                isMatch(m1.decelOverride1, m2.decelOverride1, 5f) &&
                isMatch(m1.maxSpeedOverride2, m2.maxSpeedOverride2, 5f) &&
                isMatch(m1.accelOverride2, m2.accelOverride2, 5f) &&
                isMatch(m1.decelOverride2, m2.decelOverride2, 5f)
    }

    fun queueRequest(
        target: ShipAPI,
        accelDir: Vector2f,
        mobility: MobilityProfile? = null
    ) {
        val engine = Global.getCombatEngine() ?: return
        val newKey = RequestKey(target.id, accelDir, mobility)

        val iterator = pendingRequests.iterator()
        while (iterator.hasNext()) {
            if (isNear(iterator.next().key, newKey)) {
                iterator.remove()
            }
        }

        pendingRequests[newKey] = engine.getTotalElapsedTime(false)
    }

    private fun isNear(k1: RequestKey, k2: RequestKey): Boolean {
        if (k1.shipId != k2.shipId) return false
        if (!isMobilityMatch(k1.mobility, k2.mobility)) return false

        val isZero1 = k1.accelDir.lengthSquared() == 0f
        val isZero2 = k2.accelDir.lengthSquared() == 0f

        if (isZero1 && isZero2) return true
        if (isZero1 != isZero2) return false

        val angle1 = k1.accelDir.getFacing()
        val angle2 = k2.accelDir.getFacing()

        return abs(MathUtils.getShortestRotation(angle1, angle2)) < 5f
    }

    fun getResult(
        target: ShipAPI,
        accelDir: Vector2f,
        mobility: MobilityProfile? = null
    ): DamageTimeline? {
        val engine = Global.getCombatEngine() ?: return null
        val currentTime = engine.getTotalElapsedTime(false)

        val isZero = accelDir.lengthSquared() == 0f
        val targetAngle = if (isZero) 0f else accelDir.getFacing()

        var bestDamage: DamageTimeline? = null
        var bestScore = Float.MAX_VALUE // Lower is better

        for ((key, result) in latestResults) {
            if (key.shipId != target.id) continue
            if (!isMobilityMatch(key.mobility, mobility)) continue

            val keyIsZero = key.accelDir.lengthSquared() == 0f
            if (isZero != keyIsZero) continue

            val angleError = if (isZero) {
                0f
            } else {
                val keyAngle = key.accelDir.getFacing()
                abs(MathUtils.getShortestRotation(targetAngle, keyAngle))
            }

            if (angleError < 10f) {
                val age = max(0f, currentTime - result.damageTimeline.startTime)

                val score = angleError + (age * 10f)

                if (score < bestScore) {
                    bestScore = score
                    bestDamage = result.damageTimeline
                }
            }
        }
        return bestDamage
    }

    fun advance(engine: CombatEngineAPI) {
        val completedOutput = outputRef.getAndSet(null)
        val currentTime = engine.getTotalElapsedTime(false)

        if (completedOutput != null) {
            latestResults = mergeWithRetention(
                oldMap = latestResults,
                newMap = completedOutput.results,
                timestamps = resultTimestamps,
                currentTime = currentTime,
                isOverridden = { oldKey, newBatch -> newBatch.keys.any { isNear(oldKey, it) } }
            )

            latestEnemyTimelines = mergeWithRetention(
                oldMap = latestEnemyTimelines,
                newMap = completedOutput.enemyTimelines,
                timestamps = timelineTimestamps,
                currentTime = currentTime,
                isOverridden = { oldKey, newBatch -> newBatch.containsKey(oldKey) }
            )

            isSimulating = false
        }

        if (!isSimulating && pendingRequests.isNotEmpty()) {
            val freshestRequests = pendingRequests.entries
                .filter { currentTime - it.value <= 0.1f }
                .map { it.key }

            pendingRequests.clear()

            if (freshestRequests.isNotEmpty()) {
                val combatState = engine.captureCombatState()

                isSimulating = true
                PredictorThreadPool.scope.launch {
                    try {
                        val baseSim = CombatSimulation(combatState)
                        val aiShipIds = freshestRequests.map { it.shipId }.toSet()

                        // Concurrent prediction via the refactored CombatSimulation
                        val deferredResults = freshestRequests.map { req ->
                            async {
                                val branchSim = baseSim.branch()
                                branchSim.updateShip(req.shipId, req.accelDir, req.mobility)
                                req to branchSim.simulateDamage(req.shipId)
                            }
                        }

                        val resultsMap = deferredResults.awaitAll().toMap()
                        val baseEnemyTimelines = baseSim.timelines.filterKeys { it !in aiShipIds }

                        outputRef.set(SimulationOutput(baseEnemyTimelines, resultsMap))
                    } catch (e: Exception) {
                        Global.getLogger(this.javaClass).error("FlightPathPredictor Coroutine Error", e)
                        isSimulating = false
                    }
                }
            }
        }
    }

    private inline fun <K, V> mergeWithRetention(
        oldMap: Map<K, V>,
        newMap: Map<K, V>,
        timestamps: HashMap<K, Float>,
        currentTime: Float,
        threshold: Float = 0.5f,
        isOverridden: (K, Map<K, V>) -> Boolean
    ): Map<K, V> {
        val merged = mutableMapOf<K, V>()

        for ((key, value) in oldMap) {
            val age = currentTime - (timestamps[key] ?: currentTime)
            if (age > threshold || isOverridden(key, newMap)) {
                timestamps.remove(key)
            } else {
                merged[key] = value
            }
        }

        for ((key, value) in newMap) {
            merged[key] = value
            timestamps[key] = currentTime
        }

        return merged
    }

    fun renderDebug(viewport: ViewportAPI) {
        val engine = Global.getCombatEngine() ?: return
        val shipMap = engine.ships.associateBy { it.id }

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)

        for ((shipId, timeline) in latestEnemyTimelines) {
            val ship = shipMap[shipId] ?: continue
            if (ship.isStationModule || !viewport.isNearViewport(ship.location, 2000f)) continue
            GL11.glColor4f(0.6f, 0.6f, 0.6f, 0.3f)
            drawTimeline(timeline)
        }

        val maxStart = latestResults.values.maxOfOrNull { it.damageTimeline.startTime } ?: 0f

        val drawnShips = HashSet<String>()
        for ((key, result) in latestResults) {
            if (result.damageTimeline.startTime + 0.1f < maxStart) continue
            val ship = shipMap[key.shipId] ?: continue
            if (ship.isStationModule || !viewport.isNearViewport(ship.location, 2000f)) continue

            drawnShips.add(key.shipId)

            val damageTimeline = result.damageTimeline

            val fluxGained = if (ship.shield != null && ship.shield.type != ShieldAPI.ShieldType.NONE) {
                damageTimeline.fluxToShield(
                    engine.getTotalElapsedTime(false),
                    Constants.PREDICTION_DURATION,
                    ship, useModifiedShieldMult = true)
            } else {
                damageTimeline.damageInstances.sumOf { inst ->
                    val mult = when (inst.type) {
                        com.fs.starfarer.api.combat.DamageType.KINETIC -> 0.5f
                        com.fs.starfarer.api.combat.DamageType.HIGH_EXPLOSIVE -> 2.0f
                        com.fs.starfarer.api.combat.DamageType.ENERGY -> 1.0f
                        com.fs.starfarer.api.combat.DamageType.FRAGMENTATION -> 0.25f
                        else -> 1.0f
                    }
                    (inst.amount * mult * 10f).toDouble()
                }.toFloat()
            }

            val dangerRatio = fluxGained / ship.maxFlux.coerceAtLeast(1f)
            val clampedDanger = dangerRatio.coerceIn(0f, 1f)
            val hue = 0.55f * (1f - clampedDanger)
            val heatmapColor = Color.getHSBColor(hue, 1f, 1f)

            GL11.glColor4f(heatmapColor.red / 255f, heatmapColor.green / 255f, heatmapColor.blue / 255f, 0.8f)
            drawTimeline(result.shipTimeline)
        }

        GL11.glLineWidth(4f)
        for (shipId in drawnShips) {
            val ship = shipMap[shipId] ?: continue
            val behaviorState = ship.customData["SCY_currentState"] as? ScyAiV2.BehaviorState ?: continue

            when (behaviorState) {
                ScyAiV2.BehaviorState.ADVANCE -> GL11.glColor4f(0f, 1f, 0f, 0.8f)
                ScyAiV2.BehaviorState.STANDOFF  -> GL11.glColor4f(0f, 1f, 1f, 0.8f)
                ScyAiV2.BehaviorState.BACKOFF  -> GL11.glColor4f(1f, 1f, 0f, 0.8f)
                ScyAiV2.BehaviorState.DISENGAGE  -> GL11.glColor4f(1f, 0f, 0f, 0.8f)
                ScyAiV2.BehaviorState.VENTING  -> GL11.glColor4f(1f, 1f, 1f, 0.8f)
            }

            GL11.glBegin(GL11.GL_LINE_LOOP)
            val radius = ship.collisionRadius * 1.2f
            for (i in 0 until 32) {
                val theta = 2.0 * Math.PI * i / 32
                GL11.glVertex2f(ship.location.x + (radius * cos(theta)).toFloat(), ship.location.y + (radius * sin(theta)).toFloat())
            }
            GL11.glEnd()
        }
        GL11.glPopAttrib()
    }

    private fun drawTimeline(timeline: ShipTimeline) {
        GL11.glLineWidth(2f)
        GL11.glBegin(GL11.GL_LINE_STRIP)
        for (i in 0 until timeline.size) {
            GL11.glVertex2f(timeline.x[i], timeline.y[i])
        }
        GL11.glEnd()

        GL11.glBegin(GL11.GL_LINES)
        for (i in 0 until timeline.size step 20) {
            val facingRad = Math.toRadians(timeline.facings[i].toDouble())
            val p2x = timeline.x[i] + (50f * cos(facingRad)).toFloat()
            val p2y = timeline.y[i] + (50f * sin(facingRad)).toFloat()
            GL11.glVertex2f(timeline.x[i], timeline.y[i])
            GL11.glVertex2f(p2x, p2y)
        }
        GL11.glEnd()
    }
}

class CombatPlugin : BaseEveryFrameCombatPlugin() {

    override fun advance(amount: Float, events: List<InputEventAPI>?) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        if (engine.customData["NeedsDamagePredictor"] != true) return

        PredictorManager.getInstance(engine).advance(engine)
    }

    override fun renderInWorldCoords(viewport: ViewportAPI) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.customData["NeedsDamagePredictor"] != true) return

        PredictorManager.getInstance(engine).renderDebug(viewport)
    }
}
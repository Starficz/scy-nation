package org.scy.plugins

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.ceil
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.input.InputEventAPI
import org.scy.StarficzAIUtils
import org.scy.calculateInterceptTime

class SCY_AntiMissilePlugin : BaseEveryFrameCombatPlugin() {
    override fun advance(amount: Float, events: List<InputEventAPI>?) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        if (engine.customData[SCY_AntiMissileManager.FLAG_KEY] != true) return

        SCY_AntiMissileManager.getInstance(engine).advance(amount)
    }
}

class SCY_AntiMissileManager private constructor() {

    companion object {
        private const val DATA_KEY = "SCY_ANTI_MISSILE_MANAGER"
        const val FLAG_KEY = "NEEDS_SCY_ANTI_MISSILE_MANAGER"

        fun getInstance(engine: CombatEngineAPI): SCY_AntiMissileManager {
            if (!engine.customData.containsKey(DATA_KEY)) {
                engine.customData[DATA_KEY] = SCY_AntiMissileManager()
            }
            return engine.customData[DATA_KEY] as SCY_AntiMissileManager
        }

        fun setFlag(engine: CombatEngineAPI) {
            engine.customData[FLAG_KEY] = true
        }
    }

    val assignments = HashMap<MissileAPI, MissileAPI>()
    private val activeAntiMissiles = mutableListOf<MissileAPI>()
    private val updateInterval = IntervalUtil(0.25f, 0.25f)
    private var forceRecalculate = false

    fun register(missile: MissileAPI) {
        if (!activeAntiMissiles.contains(missile)) {
            activeAntiMissiles.add(missile)
            forceRecalculate = true
        }
    }

    fun unregister(missile: MissileAPI) {
        if (activeAntiMissiles.remove(missile)) {
            assignments.remove(missile)
            forceRecalculate = true
        }
    }

    fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || activeAntiMissiles.isEmpty()) return

        // 1. Cleanup dead anti-missiles and flag if any died
        val initialSize = activeAntiMissiles.size
        activeAntiMissiles.removeAll { !engine.isEntityInPlay(it) || it.isFading }

        if (activeAntiMissiles.size != initialSize) {
            // Clean up the map to prevent memory leaks
            assignments.keys.retainAll(activeAntiMissiles.toSet())
            forceRecalculate = true
        }

        // 2. Check if any assigned enemy targets were destroyed by other PD
        if (!forceRecalculate) {
            for (target in assignments.values) {
                if (!engine.isEntityInPlay(target) || target.isFading || target.isFizzling) {
                    forceRecalculate = true
                    break
                }
            }
        }

        // 3. Tick timer and execute if interval elapsed OR flagged
        updateInterval.advance(amount)
        if (updateInterval.intervalElapsed() || forceRecalculate) {
            recalculateAssignments()
            forceRecalculate = false
        }
    }

    private fun recalculateAssignments() {
        val engine = Global.getCombatEngine()
        val allMissiles = engine.missiles


        assignments.clear()

        val swarmsByShip = activeAntiMissiles.groupBy { it.source }
        val UNREACHABLE_COST = 10000f

        for ((ship, swarmMissiles) in swarmsByShip) {
            if (swarmMissiles.isEmpty()) continue

            val anchorPos = if (ship != null && engine.isEntityInPlay(ship) && ship.isAlive) {
                ship.location
            } else {
                val centroid = Vector2f()
                for (m in swarmMissiles) {
                    Vector2f.add(centroid, m.location, centroid)
                }
                centroid.scale(1f / swarmMissiles.size)
                centroid
            }

            val amOwner = swarmMissiles.first().owner
            val amDamage = swarmMissiles.first().damageAmount
            val validTargets = mutableListOf<MissileAPI>()

            for (m in allMissiles) {
                if (m.owner != amOwner && !m.isFading && !m.isFizzling && engine.isEntityInPlay(m)) {
                    if (MathUtils.getDistanceSquared(m.location, anchorPos) <= 1000f*1000f) {
                        validTargets.add(m)
                    }
                }
            }

            if (validTargets.isEmpty()) continue

            val jobs = mutableListOf<MissileAPI>()
            for (target in validTargets) {
                val hitsRequired = ceil(target.hitpoints / amDamage.coerceAtLeast(1f)).toInt()
                for (i in 0 until hitsRequired) {
                    jobs.add(target)
                }
            }

            if (jobs.isEmpty()) continue

            val rows = swarmMissiles.size
            val cols = jobs.size
            val costMatrix = Array(rows) { FloatArray(cols) }

            for (r in 0 until rows) {
                val am = swarmMissiles[r]
                val maxSpeed = am.maxSpeed

                for (c in 0 until cols) {
                    val target = jobs[c]
                    val interceptTime = calculateInterceptTime(
                        am.location, am.velocity,
                        target.location, target.velocity,
                        maxSpeed
                    )

                    if (interceptTime == null || interceptTime > 10f || interceptTime.isNaN()) {
                        costMatrix[r][c] = UNREACHABLE_COST
                    } else {
                        var cost = interceptTime

                        val distToAnchor = MathUtils.getDistance(target.location, anchorPos)
                        cost += (distToAnchor / 500f)

                        if (target.damageAmount > 500f) cost -= 1f

                        costMatrix[r][c] = cost.coerceAtLeast(0f)
                    }
                }
            }

            val hungarian = StarficzAIUtils.HungarianAlgorithm(costMatrix)
            val results = hungarian.execute()

            for (r in 0 until rows) {
                val jobIndex = results[r]
                if (jobIndex != -1) {
                    val am = swarmMissiles[r]
                    val assignedTarget = jobs[jobIndex]

                    if (costMatrix[r][jobIndex] < 9000f) {
                        assignments[am] = assignedTarget
                    }
                }
            }
        }
    }
}
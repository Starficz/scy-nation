package org.scy.plugins

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import com.fs.starfarer.api.loading.MissileSpecAPI
import com.fs.starfarer.api.util.Misc
import kotlinx.coroutines.*
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.ext.rotate
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import org.scy.minus
import org.scy.normalized
import org.scy.plusAssign
import org.scy.times
import java.awt.Color
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

object FlightPathPredictor {
    const val TIME_STEP = 0.05f
    const val PREDICTION_DURATION = 20f
    val TOTAL_FUTURE_STATES = ceil(PREDICTION_DURATION / TIME_STEP).toInt()
    const val ENGINE_COAST_ASSUMPTION = 5f
}

// Global Coroutine scope so we don't leak thread pools between combat engagements
object PredictorThreadPool {
    private val threads = max(2, Runtime.getRuntime().availableProcessors())
    private val threadFactory = java.util.concurrent.ThreadFactory { r ->
        Thread(r).apply {
            name = "ScyPredictor-Worker-${java.util.UUID.randomUUID().toString().take(4)}"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    val dispatcher = Executors.newFixedThreadPool(threads, threadFactory).asCoroutineDispatcher()
    val scope = CoroutineScope(dispatcher + SupervisorJob())
}

// ==========================================
// 1. DATA MODELS (Immutable Snapshots)
// ==========================================

data class RequestKey(val shipId: String, val accel: Vector2f?)

data class DamageProfile(
    var kinetic: Float = 0f,
    var highExplosive: Float = 0f,
    var fragmentation: Float = 0f,
    var energy: Float = 0f,
    var emp: Float = 0f
) {
    fun addScaled(amount: Float, scale: Float, type: DamageType, empAmount: Float) {
        val finalVal = amount * scale
        when (type) {
            DamageType.KINETIC -> kinetic += finalVal
            DamageType.HIGH_EXPLOSIVE -> highExplosive += finalVal
            DamageType.ENERGY -> energy += finalVal
            DamageType.FRAGMENTATION -> fragmentation += finalVal
            else -> {}
        }
        this.emp += empAmount * scale
    }
}

class ShipStateSnapshot(
    val id: String, val owner: Int, val collisionRadius: Float, val hullSize: ShipAPI.HullSize,
    val location: Vector2f, val velocity: Vector2f, val acceleration: Float, val deceleration: Float,
    val facing: Float, val angularVelocity: Float, val turnAcceleration: Float,
    val maxSpeed: Float, val maxTurnRate: Float,
    val engineAccel: Boolean, val engineBack: Boolean, val engineLeft: Boolean, val engineRight: Boolean,
    val isOverloaded: Boolean, val overloadTime: Float, val isVenting: Boolean, val ventTime: Float,
    val parentId: String?, val moduleOffsetDist: Float, val moduleOffsetAngle: Float, val moduleFacingOffset: Float
)

class WeaponSnapshot(
    val localMountOffset: Vector2f, val localRestingAngle: Float, val localCurrentAngle: Float,
    val arc: Float, val range: Float, val maxSpread: Float, val projectileSpeed: Float,
    val turnRate: Float, val damageType: DamageType,
    val empPerBurst: Float, val damagePerBurst: Float, val damagePerHitForArmor: Float,
    val firingTime: Float, val cooldownTime: Float, val currentlyFiring: Boolean, val timeLeftInState: Float,
    val disabledTime: Float
)

data class FutureShipState(
    val timestamp: Float,
    val location: Vector2f,
    var facing: Float? = null
) {
    fun deepCopy() = FutureShipState(timestamp, Vector2f(location), facing)
}

class SimulationInput(
    val time: Float,
    val ships: List<ShipStateSnapshot>,
    val weaponSnaps: Map<String, List<WeaponSnapshot>>,
    val requests: List<RequestKey>
)

data class RequestResult(val profile: DamageProfile, val timeline: List<FutureShipState>)

class SimulationOutput(
    val enemyTimelines: Map<String, List<FutureShipState>>,
    val results: Map<RequestKey, RequestResult>
)

// ==========================================
// 2. STATE CAPTURE
// ==========================================

fun captureShipState(ship: ShipAPI): ShipStateSnapshot {
    val engine = ship.engineController
    val flux = ship.fluxTracker
    val parent = if (ship.isStationModule) ship.parentStation else null

    var modDist = 0f
    var modAngle = 0f
    var modFacing = 0f

    if (parent != null) {
        modDist = MathUtils.getDistance(parent.location, ship.location)
        modAngle = MathUtils.clampAngle(VectorUtils.getAngle(parent.location, ship.location) - parent.facing)
        modFacing = MathUtils.clampAngle(ship.facing - parent.facing)
    }

    return ShipStateSnapshot(
        id = ship.id, owner = ship.owner, collisionRadius = ship.collisionRadius, hullSize = ship.hullSize,
        location = Vector2f(ship.location), velocity = Vector2f(ship.velocity),
        acceleration = ship.acceleration, deceleration = ship.deceleration,
        facing = ship.facing, angularVelocity = ship.angularVelocity, turnAcceleration = ship.turnAcceleration,
        maxSpeed = ship.maxSpeed, maxTurnRate = ship.maxTurnRate,
        engineAccel = engine?.isAccelerating == true, engineBack = engine?.isAcceleratingBackwards == true,
        engineLeft = engine?.isStrafingLeft == true, engineRight = engine?.isStrafingRight == true,
        isOverloaded = flux.isOverloaded, overloadTime = flux.overloadTimeRemaining,
        isVenting = flux.isVenting, ventTime = flux.timeToVent,
        parentId = parent?.id, moduleOffsetDist = modDist, moduleOffsetAngle = modAngle, moduleFacingOffset = modFacing
    )
}

fun captureWeaponState(ship: ShipAPI): List<WeaponSnapshot> {
    return ship.allWeapons.mapNotNull { weapon ->
        val spec = weapon.spec
        if (weapon.slot.isHidden || weapon.isDecorative) return@mapNotNull null
        if (weapon.usesAmmo() && weapon.ammo == 0 && weapon.ammoPerSecond < 0.01f) return@mapNotNull null
        if (spec.projectileSpec is MissileSpecAPI) return@mapNotNull null
        if (weapon.derivedStats.dps < 1f && weapon.derivedStats.empPerSecond < 1f) return@mapNotNull null

        val localMountOffset = (weapon.location - ship.location).rotate(-ship.facing)
        val localCurrentAngle = weapon.currAngle - ship.facing
        val isHardpoint = weapon.slot.isHardpoint
        val effTurnRate = if (isHardpoint) ship.mutableStats.maxTurnRate.modifiedValue * 0.5f else weapon.turnRate
        val effArc = if (isHardpoint) 10f else weapon.arc

        val firingTime: Float
        val cooldownTime: Float
        val damagePerBurst: Float
        val empPerBurst: Float
        var currentlyFiring = weapon.isFiring
        val timeLeftInState: Float

        when {
            weapon.isBurstBeam -> {
                firingTime = weapon.derivedStats.burstFireDuration
                cooldownTime = weapon.cooldown
                damagePerBurst = weapon.derivedStats.burstDamage
                empPerBurst = weapon.derivedStats.empPerSecond * firingTime
                if (currentlyFiring && weapon.burstFireTimeRemaining > 0) {
                    timeLeftInState = weapon.burstFireTimeRemaining
                } else {
                    timeLeftInState = weapon.cooldownRemaining
                    currentlyFiring = false
                }
            }
            weapon.isBeam -> {
                firingTime = 1f
                cooldownTime = 0f
                damagePerBurst = weapon.derivedStats.dps
                empPerBurst = weapon.derivedStats.empPerSecond
                timeLeftInState = 0f
            }
            spec.burstSize > 1 -> {
                firingTime = weapon.derivedStats.burstFireDuration
                cooldownTime = weapon.cooldown
                damagePerBurst = weapon.damage.damage * spec.burstSize
                empPerBurst = weapon.damage.fluxComponent * spec.burstSize
                if (weapon.isInBurst) {
                    timeLeftInState = weapon.burstFireTimeRemaining
                    currentlyFiring = true
                } else if (weapon.cooldownRemaining > 0) {
                    timeLeftInState = weapon.cooldownRemaining
                    currentlyFiring = false
                } else {
                    timeLeftInState = if (weapon.isFiring) spec.chargeTime else 0f
                }
            }
            else -> {
                firingTime = weapon.derivedStats.burstFireDuration
                cooldownTime = weapon.cooldown
                damagePerBurst = weapon.damage.damage
                empPerBurst = weapon.damage.fluxComponent
                if (weapon.cooldownRemaining > 0) {
                    timeLeftInState = weapon.cooldownRemaining
                    currentlyFiring = false
                } else {
                    timeLeftInState = if (weapon.isFiring) firingTime else 0f
                }
            }
        }

        WeaponSnapshot(
            localMountOffset = localMountOffset, localRestingAngle = weapon.arcFacing,
            localCurrentAngle = localCurrentAngle, arc = effArc, range = weapon.range,
            maxSpread = if (isHardpoint) spec.maxSpread / 2f else spec.maxSpread,
            projectileSpeed = if (spec is BeamWeaponSpecAPI) spec.beamSpeed else weapon.projectileSpeed.coerceAtLeast(100f),
            turnRate = effTurnRate, damageType = weapon.damageType,
            empPerBurst = empPerBurst, damagePerBurst = damagePerBurst, damagePerHitForArmor = weapon.damage.damage,
            firingTime = firingTime.coerceAtLeast(0.1f),
            cooldownTime = cooldownTime.coerceAtLeast(0.0f),
            currentlyFiring = currentlyFiring, timeLeftInState = timeLeftInState.coerceAtLeast(0.01f),
            disabledTime = if (weapon.isDisabled) weapon.disabledDuration else 0f
        )
    }
}

// ==========================================
// 3. PHYSICS PREDICTION
// ==========================================

fun generateFlightPaths(ship: ShipStateSnapshot, startTime: Float, accel: Vector2f? = null): Array<FutureShipState> {
    if (ship.parentId != null) {
        return Array(FlightPathPredictor.TOTAL_FUTURE_STATES) { i ->
            FutureShipState(startTime + (i + 1) * FlightPathPredictor.TIME_STEP, Vector2f(ship.location), ship.facing)
        }
    }

    val strafeAccel = when (ship.hullSize) {
        ShipAPI.HullSize.FIGHTER, ShipAPI.HullSize.FRIGATE -> 1.0f * ship.acceleration
        ShipAPI.HullSize.DESTROYER -> 0.75f * ship.acceleration
        ShipAPI.HullSize.CRUISER -> 0.50f * ship.acceleration
        ShipAPI.HullSize.CAPITAL_SHIP -> 0.25f * ship.acceleration
        else -> 1.0f
    }

    val fwdUnitVector = Misc.getUnitVectorAtDegreeAngle(ship.facing)
    val leftUnitVector = Misc.getUnitVectorAtDegreeAngle(ship.facing + 90f)
    val shipAccel = Vector2f()

    if (accel == null) {
        if (ship.engineAccel) shipAccel += fwdUnitVector * ship.acceleration
        else if (ship.engineBack) shipAccel += fwdUnitVector * -ship.deceleration
        if (ship.engineLeft) shipAccel += leftUnitVector * strafeAccel
        else if (ship.engineRight) shipAccel += leftUnitVector * -strafeAccel
    } else if (accel.lengthSquared() > 0.01f) {
        val accelNormalized = accel.normalized()
        val fwdComponent = Vector2f.dot(accelNormalized, fwdUnitVector)
        val latComponent = Vector2f.dot(accelNormalized, leftUnitVector)

        val maxFwdThrust = if (fwdComponent >= 0) ship.acceleration else ship.deceleration
        val limitByFwd = if (abs(fwdComponent) > 0.0001f) maxFwdThrust / abs(fwdComponent) else Float.MAX_VALUE
        val limitByLat = if (abs(latComponent) > 0.0001f) strafeAccel / abs(latComponent) else Float.MAX_VALUE

        accelNormalized.scale(min(limitByFwd, limitByLat))
        shipAccel.set(accelNormalized)
    }

    val futureLocation = Vector2f(ship.location)
    val futureVelocity = Vector2f(ship.velocity)

    return Array(FlightPathPredictor.TOTAL_FUTURE_STATES) { i ->
        val t = startTime + ((i + 1) * FlightPathPredictor.TIME_STEP)

        futureLocation += (futureVelocity * FlightPathPredictor.TIME_STEP)
        if ((i + 1) * FlightPathPredictor.TIME_STEP < FlightPathPredictor.ENGINE_COAST_ASSUMPTION || accel != null) {
            futureVelocity += (shipAccel * FlightPathPredictor.TIME_STEP)
        }

        if (futureVelocity.length() > ship.maxSpeed) {
            futureVelocity.normalise()
            futureVelocity.scale(ship.maxSpeed)
        }

        FutureShipState(t, Vector2f(futureLocation))
    }
}

fun updateFlightPathFacings(flightPaths: Map<String, Array<FutureShipState>>, ships: List<ShipStateSnapshot>) {
    val shipMap = ships.associateBy { it.id }

    for ((shipId, timeline) in flightPaths) {
        val ship = shipMap[shipId] ?: continue
        if (ship.parentId != null) continue

        var currentAngVel = ship.angularVelocity
        var currentFacing = ship.facing

        for (i in 0 until FlightPathPredictor.TOTAL_FUTURE_STATES) {
            val myLoc = timeline[i].location
            var targetLoc: Vector2f? = null
            var minDistanceSq = Float.MAX_VALUE

            if (ship.turnAcceleration >= 0.01f) {
                for ((otherShipId, otherTimeline) in flightPaths) {
                    val otherShip = shipMap[otherShipId] ?: continue
                    if (ship.owner == otherShip.owner) continue

                    val otherLoc = otherTimeline[i].location
                    val distSq = MathUtils.getDistanceSquared(myLoc, otherLoc)

                    if (distSq < minDistanceSq) {
                        minDistanceSq = distSq
                        targetLoc = otherLoc
                    }
                }
            }

            if (targetLoc != null) {
                val angleToTarget = VectorUtils.getAngle(myLoc, targetLoc)
                val rotationNeeded = MathUtils.getShortestRotation(currentFacing, angleToTarget)
                val stoppingDist = (currentAngVel * currentAngVel) / (2f * ship.turnAcceleration)
                val movingTowards = (sign(rotationNeeded) == sign(currentAngVel)) && abs(currentAngVel) > 0.1f

                if (movingTowards && abs(rotationNeeded) <= stoppingDist) {
                    val change = ship.turnAcceleration * FlightPathPredictor.TIME_STEP
                    if (abs(currentAngVel) <= change) currentAngVel = 0f else currentAngVel -= sign(currentAngVel) * change
                } else {
                    currentAngVel += sign(rotationNeeded) * ship.turnAcceleration * FlightPathPredictor.TIME_STEP
                }
            } else {
                if (abs(currentAngVel) > 0) {
                    val change = ship.turnAcceleration * FlightPathPredictor.TIME_STEP
                    if (abs(currentAngVel) <= change) currentAngVel = 0f else currentAngVel -= sign(currentAngVel) * change
                }
            }

            currentAngVel = MathUtils.clamp(currentAngVel, -ship.maxTurnRate, ship.maxTurnRate)
            currentFacing = MathUtils.clampAngle(currentFacing + (currentAngVel * FlightPathPredictor.TIME_STEP))
            timeline[i].facing = currentFacing
        }
    }
}

// ==========================================
// 4. THE EVENT-DRIVEN ORCHESTRATOR
// ==========================================

suspend fun simulateFlightPathsCoroutines(input: SimulationInput): SimulationOutput = coroutineScope {

    val basePositionalPaths = input.ships.associate { ship ->
        ship.id to async { generateFlightPaths(ship, input.time) }
    }.mapValues { it.value.await() }

    val baseEnemyPaths = basePositionalPaths.mapValues { entry ->
        Array(FlightPathPredictor.TOTAL_FUTURE_STATES) { i -> entry.value[i].deepCopy() }
    }.toMutableMap()

    updateFlightPathFacings(baseEnemyPaths, input.ships)

    val aiShipIds = input.requests.map { it.shipId }.toSet()
    val baseEnemyTimelines = baseEnemyPaths.filterKeys { !aiShipIds.contains(it) }.mapValues { it.value.toList() }

    val deferredResults = input.requests.map { req ->
        async {
            val targetData = input.ships.find { it.id == req.shipId } ?: return@async null
            val resultProfile = DamageProfile()

            val reqPaths = basePositionalPaths.mapValues { entry ->
                Array(FlightPathPredictor.TOTAL_FUTURE_STATES) { i -> entry.value[i].deepCopy() }
            }.toMutableMap()

            reqPaths[targetData.id] = generateFlightPaths(targetData, input.time, req.accel)
            updateFlightPathFacings(reqPaths, input.ships)

            // Rigid Body Module Pass
            for (module in input.ships) {
                if (module.parentId == null) continue
                val parentTimeline = reqPaths[module.parentId] ?: continue
                val moduleTimeline = reqPaths[module.id] ?: continue

                for (i in 0 until FlightPathPredictor.TOTAL_FUTURE_STATES) {
                    val pState = parentTimeline[i]
                    val pFacing = pState.facing ?: 0f
                    val angle = pFacing + module.moduleOffsetAngle
                    val loc = MathUtils.getPointOnCircumference(pState.location, module.moduleOffsetDist, angle)

                    moduleTimeline[i].location.set(loc)
                    moduleTimeline[i].facing = MathUtils.clampAngle(pFacing + module.moduleFacingOffset)
                }
            }

            val targetTimeline = reqPaths[targetData.id]!!

            for (enemyData in input.ships) {
                if (enemyData.owner == targetData.owner || enemyData.id == targetData.id) continue

                val shipSilenceTime = if (enemyData.isOverloaded) enemyData.overloadTime
                else if (enemyData.isVenting) enemyData.ventTime else 0f

                val enemyTimeline = reqPaths[enemyData.id]!!
                val weapons = input.weaponSnaps[enemyData.id] ?: continue

                for (snap in weapons) {
                    simulateWeaponTimeline(
                        snap = snap, enemyData = enemyData, enemyTimeline = enemyTimeline,
                        targetData = targetData, targetTimeline = targetTimeline,
                        shipSilenceTime = shipSilenceTime, outProfile = resultProfile
                    )
                }
            }
            req to RequestResult(resultProfile, targetTimeline.toList())
        }
    }

    val resultsMap = deferredResults.awaitAll().filterNotNull().toMap()
    SimulationOutput(baseEnemyTimelines, resultsMap)
}

private fun simulateWeaponTimeline(
    snap: WeaponSnapshot, enemyData: ShipStateSnapshot, enemyTimeline: Array<FutureShipState>,
    targetData: ShipStateSnapshot, targetTimeline: Array<FutureShipState>,
    shipSilenceTime: Float, outProfile: DamageProfile
) {
    var t = snap.disabledTime
    var isFiring = snap.currentlyFiring
    var timeInState = snap.timeLeftInState

    while (t < FlightPathPredictor.PREDICTION_DURATION) {
        if (isFiring) {
            val burstDuration = min(timeInState, FlightPathPredictor.PREDICTION_DURATION - t)
            if (burstDuration <= 0f) break

            val sampleTime = t + (burstDuration * 0.5f)
            val solution = getFiringSolution(sampleTime, snap, enemyData, enemyTimeline, targetData, targetTimeline)

            if (solution.isValid) {
                val fraction = burstDuration / snap.firingTime
                outProfile.addScaled(snap.damagePerBurst * fraction, solution.hitChance, snap.damageType, snap.empPerBurst * fraction)
            }

            t += timeInState
            isFiring = false
            timeInState = snap.cooldownTime

        } else {
            if (timeInState > 0f) {
                t += timeInState
                timeInState = 0f
            }

            if (t < shipSilenceTime) t = shipSilenceTime
            if (t >= FlightPathPredictor.PREDICTION_DURATION) break

            val nextFireTime = findNextFiringWindow(t, snap, enemyData, enemyTimeline, targetData, targetTimeline)

            if (nextFireTime != null) {
                t = nextFireTime
                isFiring = true
                timeInState = snap.firingTime
            } else {
                break
            }
        }
    }
}

private fun findNextFiringWindow(
    startTime: Float, snap: WeaponSnapshot, enemyData: ShipStateSnapshot, enemyTimeline: Array<FutureShipState>,
    targetData: ShipStateSnapshot, targetTimeline: Array<FutureShipState>
): Float? {
    var searchT = startTime
    while (searchT < FlightPathPredictor.PREDICTION_DURATION) {
        val solution = getFiringSolution(searchT, snap, enemyData, enemyTimeline, targetData, targetTimeline)
        if (solution.isValid) return searchT
        searchT += FlightPathPredictor.TIME_STEP
    }
    return null
}

private data class FiringSolution(val isValid: Boolean, val hitChance: Float)

private fun getFiringSolution(
    timeElapsed: Float, snap: WeaponSnapshot, enemyData: ShipStateSnapshot, enemyTimeline: Array<FutureShipState>,
    targetData: ShipStateSnapshot, targetTimeline: Array<FutureShipState>
): FiringSolution {
    val frameIdx = (timeElapsed / FlightPathPredictor.TIME_STEP).toInt().coerceIn(0, FlightPathPredictor.TOTAL_FUTURE_STATES - 1)

    val targetState = targetTimeline[frameIdx]
    val enemyState = enemyTimeline[frameIdx]
    val enemyFacing = enemyState.facing ?: enemyData.facing

    // Inline math to prevent allocating thousands of Vector2f per frame for garbage collection
    val rad = enemyFacing * (Math.PI / 180.0)
    val cosF = cos(rad).toFloat()
    val sinF = sin(rad).toFloat()

    val globalMountLocX = enemyState.location.x + (snap.localMountOffset.x * cosF - snap.localMountOffset.y * sinF)
    val globalMountLocY = enemyState.location.y + (snap.localMountOffset.x * sinF + snap.localMountOffset.y * cosF)

    val dx = targetState.location.x - globalMountLocX
    val dy = targetState.location.y - globalMountLocY
    val dist = kotlin.math.sqrt(dx * dx + dy * dy)

    if (dist > snap.range + targetData.collisionRadius) return FiringSolution(false, 0f)

    val angleToTarget = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    val globalRestingAngle = MathUtils.clampAngle(enemyFacing + snap.localRestingAngle)
    val angleDiffResting = abs(MathUtils.getShortestRotation(globalRestingAngle, angleToTarget))

    if (angleDiffResting > snap.arc * 0.5f) return FiringSolution(false, 0f)

    val effectiveTurnTime = max(0f, timeElapsed - snap.disabledTime)
    val maxPossibleTurn = snap.turnRate * effectiveTurnTime
    val startGlobalAngle = enemyData.facing + snap.localCurrentAngle
    val requiredTurn = abs(MathUtils.getShortestRotation(startGlobalAngle, angleToTarget))

    if (requiredTurn > maxPossibleTurn + 5f) return FiringSolution(false, 0f)

    val halfSpreadRad = Math.toRadians((snap.maxSpread / 2f).toDouble())
    val spreadRadius = dist * kotlin.math.tan(halfSpreadRad).toFloat()

    val hitChance = if (spreadRadius <= targetData.collisionRadius) 1f else targetData.collisionRadius / spreadRadius

    return FiringSolution(true, hitChance)
}

// ==========================================
// 5. COROUTINE MANAGER & API
// ==========================================
class FlightPathPredictorManager private constructor() {

    companion object {
        const val DATA_KEY = "SCY_FLIGHT_PATH_PREDICTOR"
        fun getInstance(engine: CombatEngineAPI): FlightPathPredictorManager {
            if (!engine.customData.containsKey(DATA_KEY)) {
                engine.customData[DATA_KEY] = FlightPathPredictorManager()
            }
            return engine.customData[DATA_KEY] as FlightPathPredictorManager
        }
    }

    private var isSimulating = false
    private val outputRef = AtomicReference<SimulationOutput?>(null)

    private val pendingRequests = HashMap<RequestKey, Float>()
    private var latestResults: Map<RequestKey, RequestResult> = emptyMap()
    private var latestEnemyTimelines: Map<String, List<FutureShipState>> = emptyMap()

    private val resultTimestamps = HashMap<RequestKey, Float>()
    private val timelineTimestamps = HashMap<String, Float>()

    fun queueRequest(target: ShipAPI, accel: Vector2f?) {
        val engine = Global.getCombatEngine() ?: return
        val newKey = RequestKey(target.id, accel)

        // SMART CULLING: Prevent vector-spam bloat.
        // If this ship already asked for a similar vector, overwrite it with this fresher one.
        val iterator = pendingRequests.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (isNear(entry.key, newKey)) {
                iterator.remove()
            }
        }

        pendingRequests[newKey] = engine.getTotalElapsedTime(false)
    }

    fun getResult(target: ShipAPI, accel: Vector2f): DamageProfile? {
        val targetAngle = VectorUtils.getAngle(Vector2f(0f, 0f), accel)
        val isZero = accel.lengthSquared() == 0f

        var bestProfile: DamageProfile? = null
        var bestError = Float.MAX_VALUE

        for ((key, result) in latestResults) {
            if (key.shipId != target.id) continue

            val keyIsZero = key.accel == null || key.accel.lengthSquared() == 0f
            if (isZero && keyIsZero) return result.profile
            if (isZero != keyIsZero) continue

            val keyAngle = VectorUtils.getAngle(Vector2f(0f, 0f), key.accel)
            val diff = abs(MathUtils.getShortestRotation(targetAngle, keyAngle))

            if (diff < bestError && diff < 10f) {
                bestError = diff
                bestProfile = result.profile
            }
        }
        return bestProfile
    }

    fun updateFrame(engine: CombatEngineAPI) {
        val completedOutput = outputRef.getAndSet(null)
        val currentTime = engine.getTotalElapsedTime(false)

        if (completedOutput != null) {
            val retentionThreshold = 0.5f

            latestResults = mergeWithRetention(
                oldMap = latestResults,
                newMap = completedOutput.results,
                timestamps = resultTimestamps,
                currentTime = currentTime,
                threshold = retentionThreshold
            ) { oldKey, newBatch ->
                newBatch.keys.any { isNear(oldKey, it) }
            }

            latestEnemyTimelines = mergeWithRetention(
                oldMap = latestEnemyTimelines,
                newMap = completedOutput.enemyTimelines,
                timestamps = timelineTimestamps,
                currentTime = currentTime,
                threshold = retentionThreshold
            ) { oldKey, newBatch ->
                newBatch.containsKey(oldKey)
            }

            isSimulating = false
        }

        if (!isSimulating && pendingRequests.isNotEmpty()) {

            // STALENESS DROP: Only process requests younger than 0.25s.
            // If the threadpool fell behind, this throws away the backlog so it can catch up.
            val freshestRequests = pendingRequests.entries
                .filter { currentTime - it.value <= 0.25f }
                .map { it.key }

            pendingRequests.clear()

            // If everything was too stale, skip simulating this frame
            if (freshestRequests.isNotEmpty()) {
                val shipSnaps = ArrayList<ShipStateSnapshot>()
                val weaponSnaps = HashMap<String, List<WeaponSnapshot>>()

                for (ship in engine.ships) {
                    if (ship.isShuttlePod || ship.isFighter || ship.isHulk || !ship.isAlive || ship.isPiece) continue
                    shipSnaps.add(captureShipState(ship))
                    weaponSnaps[ship.id] = captureWeaponState(ship)
                }

                val input = SimulationInput(currentTime, shipSnaps, weaponSnaps, freshestRequests)

                isSimulating = true
                PredictorThreadPool.scope.launch {
                    try {
                        val result = simulateFlightPathsCoroutines(input)
                        outputRef.set(result)
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
        threshold: Float,
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

    private fun isNear(k1: RequestKey, k2: RequestKey): Boolean {
        if (k1.shipId != k2.shipId) return false
        val isZero1 = k1.accel == null || k1.accel.lengthSquared() == 0f
        val isZero2 = k2.accel == null || k2.accel.lengthSquared() == 0f

        if (isZero1 && isZero2) return true
        if (isZero1 != isZero2) return false

        val angle1 = VectorUtils.getAngle(Vector2f(0f, 0f), k1.accel)
        val angle2 = VectorUtils.getAngle(Vector2f(0f, 0f), k2.accel)
        val diff = abs(MathUtils.getShortestRotation(angle1, angle2))
        return diff < 10f
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

        val drawnShips = HashSet<String>()
        for ((key, result) in latestResults) {
            val ship = shipMap[key.shipId] ?: continue
            if (ship.isStationModule || !viewport.isNearViewport(ship.location, 2000f)) continue

            drawnShips.add(key.shipId)

            val profile = result.profile
            val shieldMult = ship.mutableStats?.shieldDamageTakenMult?.modifiedValue ?: 1f
            val shieldEfficiency = ship.shield?.fluxPerPointOfDamage ?: 1f

            val fluxGained = if (ship.shield != null && ship.shield.type != com.fs.starfarer.api.combat.ShieldAPI.ShieldType.NONE) {
                (profile.kinetic * 2.0f + profile.highExplosive * 0.5f + profile.energy * 1.0f + profile.fragmentation * 0.25f) * shieldEfficiency * shieldMult
            } else {
                (profile.kinetic * 0.5f + profile.highExplosive * 2.0f + profile.energy + profile.fragmentation * 0.25f) * 10f
            }

            val dangerRatio = (ship.currFlux + fluxGained) / ship.maxFlux.coerceAtLeast(1f)
            val clampedDanger = dangerRatio.coerceIn(0f, 1f)
            val hue = 0.55f * (1f - clampedDanger)
            val heatmapColor = Color.getHSBColor(hue, 1f, 1f)

            GL11.glColor4f(heatmapColor.red / 255f, heatmapColor.green / 255f, heatmapColor.blue / 255f, 0.8f)
            drawTimeline(result.timeline)
        }

        GL11.glLineWidth(4f)
        for (shipId in drawnShips) {
            val ship = shipMap[shipId] ?: continue
            val hasDoNotBackOff = ship.aiFlags?.hasFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF) == true ||
                    ship.aiFlags?.hasFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF_EVEN_WHILE_VENTING) == true
            val hasBackOff = ship.aiFlags?.hasFlag(ShipwideAIFlags.AIFlags.BACK_OFF) == true

            when {
                hasDoNotBackOff -> GL11.glColor4f(0f, 1f, 0f, 0.8f)
                hasBackOff -> GL11.glColor4f(1f, 0f, 0f, 0.8f)
                else -> GL11.glColor4f(1f, 1f, 0f, 0.8f)
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

    private fun drawTimeline(timeline: List<FutureShipState>) {
        GL11.glLineWidth(2f)
        GL11.glBegin(GL11.GL_LINE_STRIP)
        for (state in timeline) {
            GL11.glVertex2f(state.location.x, state.location.y)
        }
        GL11.glEnd()

        GL11.glBegin(GL11.GL_LINES)
        for (i in timeline.indices step 20) {
            val state = timeline[i]
            val facing = state.facing ?: continue
            val p2 = MathUtils.getPointOnCircumference(state.location, 50f, facing)
            GL11.glVertex2f(state.location.x, state.location.y)
            GL11.glVertex2f(p2.x, p2.y)
        }
        GL11.glEnd()
    }
}

// ==========================================
// 6. PLUGIN HOOK
// ==========================================

class FlightPathPredictorPlugin : BaseEveryFrameCombatPlugin() {

    override fun advance(amount: Float, events: List<InputEventAPI>?) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        if (engine.customData["NeedsDamagePredictor"] != true) return

        FlightPathPredictorManager.getInstance(engine).updateFrame(engine)
    }

    override fun renderInWorldCoords(viewport: ViewportAPI) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.customData["NeedsDamagePredictor"] != true) return

        //FlightPathPredictorManager.getInstance(engine).renderDebug(viewport)
    }
}
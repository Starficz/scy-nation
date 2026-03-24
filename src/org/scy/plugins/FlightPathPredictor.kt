package org.scy.plugins

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.combat.WeaponAPI
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

data class MobilityProfile(
    val maxSpeedOverride1: Float,
    val accelOverride1: Float,
    val decelOverride1: Float,
    val phase1Duration: Float? = null,
    val maxSpeedOverride2: Float? = null,
    val accelOverride2: Float? = null,
    val decelOverride2: Float? = null
)

data class RequestKey(
    val shipId: String,
    val accelDir: Vector2f?,
    val mobility: MobilityProfile?
)

data class DamageInstance(
    val time: Float,
    val amount: Float,
    val empAmount: Float,
    val type: DamageType
)

class DamageTimeline {
    val instances = mutableListOf<DamageInstance>()

    fun addInstance(time: Float, amount: Float, scale: Float, type: DamageType, empAmount: Float) {
        val finalVal = amount * scale
        val finalEmp = empAmount * scale
        if (finalVal > 0f || finalEmp > 0f) {
            instances.add(DamageInstance(time, finalVal, finalEmp, type))
        }
    }

    /**
     * Gets the total raw damage expected within the next X seconds.
     * REQUIRES the current absolute engine time.
     */
    fun getTotalDamage(currentTime: Float, nextXSeconds: Float = 5f): Float {
        var total = 0f
        val endTime = currentTime + nextXSeconds
        for (inst in instances) {
            // Must fall exactly between NOW and the end of the window
            if (inst.time in currentTime..endTime) {
                total += inst.amount
            }
        }
        return total
    }

    /**
     * Calculates the estimated flux the ship's shields will take over the next X seconds.
     * REQUIRES the current absolute engine time.
     */
    fun fluxToShield(currentTime: Float, nextXSeconds: Float = 5f, ship: ShipAPI, useModifiedShieldMult: Boolean = false): Float {
        if (ship.shield == null || ship.shield.type == ShieldAPI.ShieldType.NONE) {
            return 0f
        }

        val stats = ship.mutableStats ?: return 0f
        val shieldMultiplier = if (useModifiedShieldMult) stats.shieldDamageTakenMult.modifiedValue else stats.shieldDamageTakenMult.base

        var totalFlux = 0f
        val shieldEfficiency = ship.shield.fluxPerPointOfDamage
        val endTime = currentTime + nextXSeconds

        for (inst in instances) {
            // Check absolute bounds
            if (inst.time < currentTime || inst.time > endTime) continue

            var mult = shieldMultiplier
            mult *= when (inst.type) {
                DamageType.FRAGMENTATION -> 0.25f * stats.fragmentationDamageTakenMult.modifiedValue
                DamageType.KINETIC -> 2f * stats.kineticDamageTakenMult.modifiedValue
                DamageType.HIGH_EXPLOSIVE -> 0.5f * stats.highExplosiveDamageTakenMult.modifiedValue
                DamageType.ENERGY -> stats.energyDamageTakenMult.modifiedValue
                else -> 1f
            }

            totalFlux += inst.amount * shieldEfficiency * mult
        }

        return totalFlux
    }
}

class ShipStateSnapshot(
    val id: String,
    val owner: Int,
    val collisionRadius: Float,
    val hullSize: ShipAPI.HullSize,
    val shieldRadius: Float,
    val location: Vector2f,
    val velocity: Vector2f,
    val acceleration: Float,
    val deceleration: Float,
    val facing: Float,
    val angularVelocity: Float,
    val turnAcceleration: Float,
    val maxSpeed: Float,
    val maxTurnRate: Float,
    val engineAccel: Boolean,
    val engineBack: Boolean,
    val engineLeft: Boolean,
    val engineRight: Boolean,
    val isOverloaded: Boolean,
    val overloadTime: Float,
    val isVenting: Boolean,
    val ventTime: Float,
    val parentId: String?,
    val moduleOffsetDist: Float,
    val moduleOffsetAngle: Float,
    val moduleFacingOffset: Float
)

sealed class WeaponSnapshot(
    val localMountOffset: Vector2f,
    val localRestingAngle: Float,
    val localCurrentAngle: Float,
    val arc: Float,
    val range: Float,
    val turnRate: Float,
    val damageType: DamageType,
    val empPerBurst: Float,
    val damagePerBurst: Float,
    val damagePerHitForArmor: Float,
    val firingTime: Float,
    val cooldownTime: Float,
    val currentlyFiring: Boolean,
    val timeLeftInState: Float,
    val disabledTime: Float
)

class ProjectileWeaponSnapshot(
    localMountOffset: Vector2f,
    localRestingAngle: Float,
    localCurrentAngle: Float,
    arc: Float,
    range: Float,
    turnRate: Float,
    damageType: DamageType,
    empPerBurst: Float,
    damagePerBurst: Float,
    damagePerHitForArmor: Float,
    firingTime: Float,
    cooldownTime: Float,
    currentlyFiring: Boolean,
    timeLeftInState: Float,
    disabledTime: Float,
    val projectileSpeed: Float,
    val maxSpread: Float
) : WeaponSnapshot(
    localMountOffset, localRestingAngle, localCurrentAngle, arc, range, turnRate,
    damageType, empPerBurst, damagePerBurst, damagePerHitForArmor, firingTime,
    cooldownTime, currentlyFiring, timeLeftInState, disabledTime
)

class MissileWeaponSnapshot(
    localMountOffset: Vector2f, localRestingAngle: Float, localCurrentAngle: Float,
    arc: Float, range: Float, turnRate: Float, damageType: DamageType,
    empPerBurst: Float, damagePerBurst: Float, damagePerHitForArmor: Float,
    firingTime: Float, cooldownTime: Float, currentlyFiring: Boolean, timeLeftInState: Float,
    disabledTime: Float,
    val missileAccel: Float, val missileMaxSpeed: Float,
    val missileTurnRate: Float, val missileTurnAccel: Float,
    val maxFlightTime: Float,
    val doNotAim: Boolean
) : WeaponSnapshot(
    localMountOffset, localRestingAngle, localCurrentAngle, arc, range, turnRate,
    damageType, empPerBurst, damagePerBurst, damagePerHitForArmor, firingTime,
    cooldownTime, currentlyFiring, timeLeftInState, disabledTime
)

class MissileSnapshot(
    val location: Vector2f,
    val velocity: Vector2f,
    val facing: Float,
    val angularVelocity: Float,
    val owner: Int,
    val damageAmount: Float,
    val empAmount: Float,
    val damageType: DamageType,
    val isMine: Boolean,
    val mineExplosionRange: Float,
    val acceleration: Float,
    val maxSpeed: Float,
    val maxTurnRate: Float,
    val turnAcceleration: Float,
    val flightTime: Float,
    val maxFlightTime: Float,
    val targetId: String?
)

private data class FiringSolution(val isValid: Boolean, val hitChance: Float, val distance: Float)

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
    val missiles: List<MissileSnapshot>,
    val requests: List<RequestKey>
)

data class RequestResult(val damageTimeline: DamageTimeline, val shipTimeline: List<FutureShipState>)

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
        id = ship.id,
        owner = ship.owner,
        collisionRadius = ship.collisionRadius,
        hullSize = ship.hullSize,
        shieldRadius = ship.shieldRadiusEvenIfNoShield, // <--- ADDED
        location = Vector2f(ship.location),
        velocity = Vector2f(ship.velocity),
        acceleration = ship.acceleration,
        deceleration = ship.deceleration,
        facing = ship.facing,
        angularVelocity = ship.angularVelocity,
        turnAcceleration = ship.turnAcceleration,
        maxSpeed = ship.maxSpeed,
        maxTurnRate = ship.maxTurnRate,
        engineAccel = engine?.isAccelerating == true,
        engineBack = engine?.isAcceleratingBackwards == true,
        engineLeft = engine?.isStrafingLeft == true,
        engineRight = engine?.isStrafingRight == true,
        isOverloaded = flux.isOverloaded,
        overloadTime = flux.overloadTimeRemaining,
        isVenting = flux.isVenting,
        ventTime = flux.timeToVent,
        parentId = parent?.id,
        moduleOffsetDist = modDist,
        moduleOffsetAngle = modAngle,
        moduleFacingOffset = modFacing
    )
}

fun captureMissileState(engine: CombatEngineAPI): List<MissileSnapshot> {
    val snaps = ArrayList<MissileSnapshot>()
    for (proj in engine.projectiles) {
        if (proj !is MissileAPI || proj.isFading || proj.isFlare) continue

        var targetId: String? = null
        val ai = proj.missileAI
        if (ai is com.fs.starfarer.api.combat.GuidedMissileAI) {
            val target = ai.target
            if (target is ShipAPI) targetId = target.id
        }

        snaps.add(
            MissileSnapshot(
                location = Vector2f(proj.location),
                velocity = Vector2f(proj.velocity),
                facing = proj.facing,
                angularVelocity = proj.angularVelocity,
                owner = proj.owner,
                damageAmount = proj.damageAmount,
                empAmount = proj.empAmount,
                damageType = proj.damageType,
                isMine = proj.isMine,
                mineExplosionRange = if (proj.isMine) proj.mineExplosionRange else 0f,
                acceleration = proj.acceleration,
                maxSpeed = proj.maxSpeed,
                maxTurnRate = proj.maxTurnRate,
                turnAcceleration = proj.turnAcceleration,
                flightTime = proj.flightTime,
                maxFlightTime = proj.maxFlightTime,
                targetId = targetId
            )
        )
    }
    return snaps
}

fun captureWeaponState(ship: ShipAPI): List<WeaponSnapshot> {
    return ship.allWeapons.mapNotNull { weapon ->
        val spec = weapon.spec
        if (weapon.slot.isHidden || weapon.isDecorative) return@mapNotNull null
        if (weapon.usesAmmo() && weapon.ammo == 0 && weapon.ammoPerSecond < 0.01f) return@mapNotNull null
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

        if (spec.projectileSpec is MissileSpecAPI) {
            val mSpec = spec.projectileSpec as MissileSpecAPI
            val engineSpec = mSpec.hullSpec?.engineSpec

            val mMaxSpeed = engineSpec?.maxSpeed ?: weapon.projectileSpeed.coerceAtLeast(100f)
            val mAccel = engineSpec?.acceleration ?: (mMaxSpeed * 2f)
            val mTurnRate = engineSpec?.maxTurnRate ?: 0f
            val mTurnAccel = engineSpec?.turnAcceleration ?: (mTurnRate * 2f)

            val doNotAim = spec.aiHints.contains(WeaponAPI.AIHints.DO_NOT_AIM)

            // If the missile can track (turn rate > 0) or is a VLS (doNotAim), simulate it kinematically.
            if (doNotAim) {
                MissileWeaponSnapshot(
                    localMountOffset = localMountOffset, localRestingAngle = weapon.arcFacing,
                    localCurrentAngle = localCurrentAngle, arc = effArc, range = weapon.range + weapon.projectileFadeRange/2,
                    turnRate = effTurnRate, damageType = weapon.damageType,
                    empPerBurst = empPerBurst, damagePerBurst = damagePerBurst, damagePerHitForArmor = weapon.damage.damage,
                    firingTime = firingTime.coerceAtLeast(0.1f), cooldownTime = cooldownTime.coerceAtLeast(0.0f),
                    currentlyFiring = currentlyFiring, timeLeftInState = timeLeftInState.coerceAtLeast(0.01f),
                    disabledTime = if (weapon.isDisabled) weapon.disabledDuration else 0f,
                    missileAccel = mAccel, missileMaxSpeed = mMaxSpeed,
                    missileTurnRate = mTurnRate, missileTurnAccel = mTurnAccel,
                    maxFlightTime = mSpec.maxFlightTime,
                    doNotAim = doNotAim
                )
            } else {
                // Otherwise, it's an unguided rocket. Treat it identically to a standard projectile,
                // using its max speed as the constant projectile speed.
                ProjectileWeaponSnapshot(
                    localMountOffset = localMountOffset, localRestingAngle = weapon.arcFacing,
                    localCurrentAngle = localCurrentAngle, arc = effArc, range = weapon.range + weapon.projectileFadeRange/2,
                    turnRate = effTurnRate, damageType = weapon.damageType,
                    empPerBurst = empPerBurst, damagePerBurst = damagePerBurst, damagePerHitForArmor = weapon.damage.damage,
                    firingTime = firingTime.coerceAtLeast(0.1f), cooldownTime = cooldownTime.coerceAtLeast(0.0f),
                    currentlyFiring = currentlyFiring, timeLeftInState = timeLeftInState.coerceAtLeast(0.01f),
                    disabledTime = if (weapon.isDisabled) weapon.disabledDuration else 0f,
                    projectileSpeed = mMaxSpeed,
                    maxSpread = if (isHardpoint) spec.maxSpread / 2f else spec.maxSpread
                )
            }
        } else {
            // Standard Energy/Ballistic weapon
            ProjectileWeaponSnapshot(
                localMountOffset = localMountOffset, localRestingAngle = weapon.arcFacing,
                localCurrentAngle = localCurrentAngle, arc = effArc, range = weapon.range + weapon.projectileFadeRange/2,
                turnRate = effTurnRate, damageType = weapon.damageType,
                empPerBurst = empPerBurst, damagePerBurst = damagePerBurst, damagePerHitForArmor = weapon.damage.damage,
                firingTime = firingTime.coerceAtLeast(0.1f), cooldownTime = cooldownTime.coerceAtLeast(0.0f),
                currentlyFiring = currentlyFiring, timeLeftInState = timeLeftInState.coerceAtLeast(0.01f),
                disabledTime = if (weapon.isDisabled) weapon.disabledDuration else 0f,
                projectileSpeed = if (spec is BeamWeaponSpecAPI) spec.beamSpeed else weapon.projectileSpeed.coerceAtLeast(100f),
                maxSpread = if (isHardpoint) spec.maxSpread / 2f else spec.maxSpread
            )
        }
    }
}

// ==========================================
// 3. PHYSICS PREDICTION
// ==========================================

fun generateFlightPaths(
    ship: ShipStateSnapshot,
    startTime: Float,
    accelDir: Vector2f? = null,
    mobility: MobilityProfile? = null
): Array<FutureShipState> {
    if (ship.parentId != null) {
        return Array(FlightPathPredictor.TOTAL_FUTURE_STATES) { i ->
            FutureShipState(startTime + (i + 1) * FlightPathPredictor.TIME_STEP, Vector2f(ship.location), ship.facing)
        }
    }

    val fwdUnitVector = Misc.getUnitVectorAtDegreeAngle(ship.facing)
    val leftUnitVector = Misc.getUnitVectorAtDegreeAngle(ship.facing + 90f)

    // Pre-calculate the acceleration vector for a given multiplier
    fun calcAccel(accelOverride: Float?, decelOverride: Float?): Vector2f {
        val accel = accelOverride ?: ship.acceleration
        val decel = decelOverride ?: ship.deceleration

        val strafeAccel = when (ship.hullSize) {
            ShipAPI.HullSize.FIGHTER, ShipAPI.HullSize.FRIGATE -> 1.0f * accel
            ShipAPI.HullSize.DESTROYER -> 0.75f * accel
            ShipAPI.HullSize.CRUISER -> 0.50f * accel
            ShipAPI.HullSize.CAPITAL_SHIP -> 0.25f * accel
            else -> 1.0f
        }

        val shipAccel = Vector2f()

        if (accelDir == null) {
            if (ship.engineAccel) shipAccel += fwdUnitVector * accel
            else if (ship.engineBack) shipAccel += fwdUnitVector * -accel
            if (ship.engineLeft) shipAccel += leftUnitVector * strafeAccel
            else if (ship.engineRight) shipAccel += leftUnitVector * -strafeAccel
        } else if (accelDir.lengthSquared() > 0.01f) {
            val accelNormalized = accelDir.normalized()
            val fwdComponent = Vector2f.dot(accelNormalized, fwdUnitVector)
            val latComponent = Vector2f.dot(accelNormalized, leftUnitVector)

            val maxFwdThrust = if (fwdComponent >= 0) accel else decel
            val limitByFwd = if (abs(fwdComponent) > 0.0001f) maxFwdThrust / abs(fwdComponent) else Float.MAX_VALUE
            val limitByLat = if (abs(latComponent) > 0.0001f) strafeAccel / abs(latComponent) else Float.MAX_VALUE

            accelNormalized.scale(min(limitByFwd, limitByLat))
            shipAccel.set(accelNormalized)
        }
        return shipAccel
    }

    val spd1 = mobility?.maxSpeedOverride1 ?: ship.maxSpeed
    val duration1 = mobility?.phase1Duration ?: Float.MAX_VALUE

    val spd2 = mobility?.maxSpeedOverride2 ?: ship.maxSpeed

    val shipAccel1 = calcAccel(mobility?.accelOverride1, mobility?.decelOverride1)
    val shipAccel2 = calcAccel(mobility?.accelOverride2, mobility?.decelOverride2)

    val futureLocation = Vector2f(ship.location)
    val futureVelocity = Vector2f(ship.velocity)

    return Array(FlightPathPredictor.TOTAL_FUTURE_STATES) { i ->
        val elapsedTime = (i + 1) * FlightPathPredictor.TIME_STEP
        val t = startTime + elapsedTime

        val inPhase1 = elapsedTime <= duration1
        val currentMaxSpeed = if (inPhase1) spd1 else spd2
        val currentAccel = if (inPhase1) shipAccel1 else shipAccel2

        futureLocation += (futureVelocity * FlightPathPredictor.TIME_STEP)

        if (elapsedTime < FlightPathPredictor.ENGINE_COAST_ASSUMPTION || accelDir != null) {
            futureVelocity += (currentAccel * FlightPathPredictor.TIME_STEP)
        }

        if (futureVelocity.length() > currentMaxSpeed) {
            futureVelocity.normalise()
            futureVelocity.scale(currentMaxSpeed)
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
            val resultDamage = DamageTimeline()

            val reqPaths = basePositionalPaths.mapValues { entry ->
                Array(FlightPathPredictor.TOTAL_FUTURE_STATES) { i -> entry.value[i].deepCopy() }
            }.toMutableMap()

            reqPaths[targetData.id] = generateFlightPaths(
                ship = targetData,
                startTime = input.time,
                accelDir = req.accelDir,
                mobility = req.mobility
            )
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

            // Temporary list to hold newly fired future-missiles
            val predictedMissiles = mutableListOf<MissileSnapshot>()

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
                        shipSilenceTime = shipSilenceTime, outDamage = resultDamage,
                        generatedMissiles = predictedMissiles, // <--- PASS IT HERE
                        startTime = input.time
                    )
                }
            }

            // Combine real in-flight missiles with predicted future missiles
            val allMissilesToSimulate = input.missiles + predictedMissiles

            simulateMissileTimelines(
                missiles = allMissilesToSimulate,
                targetData = targetData,
                targetTimeline = targetTimeline,
                allShipTimelines = reqPaths,
                outDamage = resultDamage
            )

            req to RequestResult(resultDamage, targetTimeline.toList())
        }
    }

    val resultsMap = deferredResults.awaitAll().filterNotNull().toMap()
    SimulationOutput(baseEnemyTimelines, resultsMap)
}

private fun simulateWeaponTimeline(
    snap: WeaponSnapshot,
    enemyData: ShipStateSnapshot,
    enemyTimeline: Array<FutureShipState>,
    targetData: ShipStateSnapshot,
    targetTimeline: Array<FutureShipState>,
    shipSilenceTime: Float,
    outDamage: DamageTimeline,
    generatedMissiles: MutableList<MissileSnapshot>,
    startTime: Float
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

                if (snap is MissileWeaponSnapshot) {
                    val frameIdx = (sampleTime / FlightPathPredictor.TIME_STEP).toInt().coerceIn(0, FlightPathPredictor.TOTAL_FUTURE_STATES - 1)
                    val spawnerState = enemyTimeline[frameIdx]

                    val rad = (spawnerState.facing ?: enemyData.facing) * (Math.PI / 180.0)
                    val globalMountLocX = spawnerState.location.x + (snap.localMountOffset.x * cos(rad).toFloat() - snap.localMountOffset.y * sin(rad).toFloat())
                    val globalMountLocY = spawnerState.location.y + (snap.localMountOffset.x * sin(rad).toFloat() + snap.localMountOffset.y * cos(rad).toFloat())

                    generatedMissiles.add(
                        MissileSnapshot(
                            location = Vector2f(globalMountLocX, globalMountLocY),
                            velocity = Vector2f(enemyData.velocity),
                            facing = spawnerState.facing ?: enemyData.facing,
                            angularVelocity = enemyData.angularVelocity,
                            owner = enemyData.owner,
                            damageAmount = snap.damagePerBurst * fraction,
                            empAmount = snap.empPerBurst * fraction,
                            damageType = snap.damageType,
                            isMine = false,
                            mineExplosionRange = 0f,
                            acceleration = snap.missileAccel,
                            maxSpeed = snap.missileMaxSpeed,
                            maxTurnRate = snap.missileTurnRate,
                            turnAcceleration = snap.missileTurnAccel,
                            flightTime = -sampleTime, // Keeps it dormant via relative logic
                            maxFlightTime = snap.maxFlightTime,
                            targetId = targetData.id
                        )
                    )
                } else if (snap is ProjectileWeaponSnapshot) {
                    val travelTime = solution.distance / snap.projectileSpeed

                    val hitTimeRelative = sampleTime + travelTime
                    val hitTimeAbsolute = startTime + hitTimeRelative

                    if (hitTimeRelative <= FlightPathPredictor.PREDICTION_DURATION) {
                        outDamage.addInstance(
                            time = hitTimeAbsolute,
                            amount = snap.damagePerBurst * fraction,
                            scale = solution.hitChance,
                            type = snap.damageType,
                            empAmount = snap.empPerBurst * fraction
                        )
                    }
                }
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

private fun getFiringSolution(
    timeElapsed: Float, snap: WeaponSnapshot, enemyData: ShipStateSnapshot, enemyTimeline: Array<FutureShipState>,
    targetData: ShipStateSnapshot, targetTimeline: Array<FutureShipState>
): FiringSolution {
    val frameIdx = (timeElapsed / FlightPathPredictor.TIME_STEP).toInt().coerceIn(0, FlightPathPredictor.TOTAL_FUTURE_STATES - 1)

    val targetState = targetTimeline[frameIdx]
    val enemyState = enemyTimeline[frameIdx]
    val enemyFacing = enemyState.facing ?: enemyData.facing

    val rad = enemyFacing * (Math.PI / 180.0)
    val cosF = cos(rad).toFloat()
    val sinF = sin(rad).toFloat()

    val globalMountLocX = enemyState.location.x + (snap.localMountOffset.x * cosF - snap.localMountOffset.y * sinF)
    val globalMountLocY = enemyState.location.y + (snap.localMountOffset.x * sinF + snap.localMountOffset.y * cosF)

    val dx = targetState.location.x - globalMountLocX
    val dy = targetState.location.y - globalMountLocY
    val dist = kotlin.math.sqrt(dx * dx + dy * dy)

    if (dist > snap.range + targetData.collisionRadius) return FiringSolution(false, 0f, dist)

    // VLS missiles can fire at anything in range
    if (snap is MissileWeaponSnapshot && snap.doNotAim) {
        return FiringSolution(true, 1f, dist)
    }

    val angleToTarget = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    val globalRestingAngle = MathUtils.clampAngle(enemyFacing + snap.localRestingAngle)
    val angleDiffResting = abs(MathUtils.getShortestRotation(globalRestingAngle, angleToTarget))

    if (angleDiffResting > snap.arc * 0.5f) return FiringSolution(false, 0f, dist)

    val effectiveTurnTime = max(0f, timeElapsed - snap.disabledTime)
    val maxPossibleTurn = snap.turnRate * effectiveTurnTime
    val startGlobalAngle = enemyData.facing + snap.localCurrentAngle
    val requiredTurn = abs(MathUtils.getShortestRotation(startGlobalAngle, angleToTarget))

    if (requiredTurn > maxPossibleTurn + 5f) return FiringSolution(false, 0f, dist)

    // Missiles track perfectly once fired
    if (snap is MissileWeaponSnapshot) {
        return FiringSolution(true, 1f, dist)
    }

    snap as ProjectileWeaponSnapshot
    val halfSpreadRad = Math.toRadians((snap.maxSpread / 2f).toDouble())
    val spreadRadius = dist * kotlin.math.tan(halfSpreadRad).toFloat()

    val hitChance = if (spreadRadius <= targetData.collisionRadius) 1f else targetData.collisionRadius / spreadRadius

    return FiringSolution(true, hitChance, dist)
}

private fun simulateMissileTimelines(
    missiles: List<MissileSnapshot>,
    targetData: ShipStateSnapshot,
    targetTimeline: Array<FutureShipState>,
    allShipTimelines: Map<String, Array<FutureShipState>>,
    outDamage: DamageTimeline
) {
    val timeStep = FlightPathPredictor.TIME_STEP

    for (missile in missiles) {
        if (missile.owner == targetData.owner) continue

        // Fast cull: if it's tracking someone else entirely, ignore it to save CPU
        if (!missile.isMine && missile.targetId != null && missile.targetId != targetData.id) continue

        val loc = Vector2f(missile.location)
        val vel = Vector2f(missile.velocity)
        var facing = missile.facing
        var angVel = missile.angularVelocity

        // Local state for the simulation loop
        var currentFlightTime = missile.flightTime

        for (i in 0 until FlightPathPredictor.TOTAL_FUTURE_STATES) {

            // Dormant check: If it's still negative, skip physics calculation for this frame
            if (currentFlightTime < 0f) {
                currentFlightTime += timeStep
                continue
            }
            if (currentFlightTime >= missile.maxFlightTime) break // Missile fizzles out

            val targetState = targetTimeline[i]

            if (missile.isMine) {
                // Mines mostly drift along their velocity
                loc.x += vel.x * timeStep
                loc.y += vel.y * timeStep

                val distSq = MathUtils.getDistanceSquared(loc, targetState.location)
                val triggerRadius = targetData.shieldRadius + missile.mineExplosionRange * 1.1f

                if (distSq <= triggerRadius * triggerRadius) {
                    outDamage.addInstance(targetState.timestamp, missile.damageAmount, 1f, missile.damageType, missile.empAmount)
                    break // Mine exploded
                }
            } else {
                // Determine where the target is going to be in this exact frame
                var guideTargetLoc: Vector2f? = null
                if (missile.targetId != null) {
                    val guideTimeline = allShipTimelines[missile.targetId]
                    if (guideTimeline != null) guideTargetLoc = guideTimeline[i].location
                }

                // 1. Angular Kinematics (Tracking)
                if (guideTargetLoc != null) {
                    val angleToTarget = VectorUtils.getAngle(loc, guideTargetLoc)
                    val rotationNeeded = MathUtils.getShortestRotation(facing, angleToTarget)

                    val effTurnAccel = missile.turnAcceleration.coerceAtLeast(1f)
                    val stoppingDist = (angVel * angVel) / (2f * effTurnAccel)
                    val movingTowards = (sign(rotationNeeded) == sign(angVel)) && abs(angVel) > 0.1f

                    if (movingTowards && abs(rotationNeeded) <= stoppingDist) {
                        val change = effTurnAccel * timeStep
                        if (abs(angVel) <= change) angVel = 0f else angVel -= sign(angVel) * change
                    } else {
                        angVel += sign(rotationNeeded) * effTurnAccel * timeStep
                    }
                } else {
                    // Lost track: dampen rotation
                    val change = missile.turnAcceleration * timeStep
                    if (abs(angVel) <= change) angVel = 0f else angVel -= sign(angVel) * change
                }

                angVel = MathUtils.clamp(angVel, -missile.maxTurnRate, missile.maxTurnRate)
                facing = MathUtils.clampAngle(facing + angVel * timeStep)

                // 2. Forward Kinematics (Acceleration)
                val accelVector = Misc.getUnitVectorAtDegreeAngle(facing)
                accelVector.scale(missile.acceleration * timeStep)
                vel.translate(accelVector.x, accelVector.y)

                if (vel.length() > missile.maxSpeed) {
                    vel.normalise()
                    vel.scale(missile.maxSpeed)
                }

                loc.translate(vel.x * timeStep, vel.y * timeStep)

                // 3. Collision Check
                val distSq = MathUtils.getDistanceSquared(loc, targetState.location)
                if (distSq <= (targetData.shieldRadius + 100f) * (targetData.shieldRadius + 100f)) {
                    outDamage.addInstance(targetState.timestamp, missile.damageAmount, 1f, missile.damageType, missile.empAmount)
                    break // Missile hit, destroy it from timeline
                }
            }
            currentFlightTime += timeStep
        }
    }
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

    private fun isMatch(val1: Float?, val2: Float?, tolerance: Float): Boolean {
        if (val1 == val2) return true
        if (val1 == null && val2 == null) return true
        if (val1 == null || val2 == null) return false
        return abs(val1 - val2) <= tolerance
    }

    fun queueRequest(
        target: ShipAPI,
        accelDir: Vector2f?,
        mobility: MobilityProfile? = null
    ) {
        val engine = Global.getCombatEngine() ?: return
        val newKey = RequestKey(target.id, accelDir, mobility)

        // SMART CULLING: Prevent vector-spam bloat.
        val iterator = pendingRequests.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (isNear(entry.key, newKey)) {
                iterator.remove()
            }
        }

        pendingRequests[newKey] = engine.getTotalElapsedTime(false)
    }

    fun getResult(
        target: ShipAPI,
        accelDir: Vector2f,
        mobility: MobilityProfile? = null
    ): DamageTimeline? {
        val targetAngle = VectorUtils.getAngle(Vector2f(0f, 0f), accelDir)
        val isZero = accelDir.lengthSquared() == 0f

        var bestDamage: DamageTimeline? = null
        var bestError = Float.MAX_VALUE

        for ((key, result) in latestResults) {
            if (key.shipId != target.id) continue

            val m1 = key.mobility
            val m2 = mobility

            if ((m1 == null) != (m2 == null)) continue

            if (m1 != null && m2 != null) {
                // Validate the engine modifiers match the request
                if (!isMatch(m1.phase1Duration, m2.phase1Duration, 0.1f)) continue
                if (!isMatch(m1.maxSpeedOverride1, m2.maxSpeedOverride1, 5f)) continue
                if (!isMatch(m1.accelOverride1, m2.accelOverride1, 5f)) continue
                if (!isMatch(m1.decelOverride1, m2.decelOverride1, 5f)) continue

                if (!isMatch(m1.maxSpeedOverride2, m2.maxSpeedOverride2, 5f)) continue
                if (!isMatch(m1.accelOverride2, m2.accelOverride2, 5f)) continue
                if (!isMatch(m1.decelOverride2, m2.decelOverride2, 5f)) continue
            }

            val keyIsZero = key.accelDir == null || key.accelDir.lengthSquared() == 0f
            if (isZero && keyIsZero) return result.damageTimeline
            if (isZero != keyIsZero) continue

            val keyAngle = VectorUtils.getAngle(Vector2f(0f, 0f), key.accelDir)
            val diff = abs(MathUtils.getShortestRotation(targetAngle, keyAngle))

            if (diff < bestError && diff < 10f) {
                bestError = diff
                bestDamage = result.damageTimeline
            }
        }
        return bestDamage
    }

    private fun isNear(k1: RequestKey, k2: RequestKey): Boolean {
        if (k1.shipId != k2.shipId) return false

        val m1 = k1.mobility
        val m2 = k2.mobility

        if ((m1 == null) != (m2 == null)) return false

        if (m1 != null && m2 != null) {
            // Check for parameter drifting in the culling logic
            if (!isMatch(m1.phase1Duration, m2.phase1Duration, 0.1f)) return false
            if (!isMatch(m1.maxSpeedOverride1, m2.maxSpeedOverride1, 5f)) return false
            if (!isMatch(m1.accelOverride1, m2.accelOverride1, 5f)) return false
            if (!isMatch(m1.decelOverride1, m2.decelOverride1, 5f)) return false

            if (!isMatch(m1.maxSpeedOverride2, m2.maxSpeedOverride2, 5f)) return false
            if (!isMatch(m1.accelOverride2, m2.accelOverride2, 5f)) return false
            if (!isMatch(m1.decelOverride2, m2.decelOverride2, 5f)) return false
        }

        val isZero1 = k1.accelDir == null || k1.accelDir.lengthSquared() == 0f
        val isZero2 = k2.accelDir == null || k2.accelDir.lengthSquared() == 0f

        if (isZero1 && isZero2) return true
        if (isZero1 != isZero2) return false

        val angle1 = VectorUtils.getAngle(Vector2f(0f, 0f), k1.accelDir)
        val angle2 = VectorUtils.getAngle(Vector2f(0f, 0f), k2.accelDir)
        val diff = abs(MathUtils.getShortestRotation(angle1, angle2))
        return diff < 10f
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

                val missileSnaps = captureMissileState(engine)

                val input = SimulationInput(currentTime, shipSnaps, weaponSnaps, missileSnaps, freshestRequests)

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

            val damageTimeline = result.damageTimeline

            // Calculate total flux over the full prediction duration for the heat map
            val fluxGained = if (ship.shield != null && ship.shield.type != ShieldAPI.ShieldType.NONE) {
                damageTimeline.fluxToShield(
                    engine.getTotalElapsedTime(false),
                    FlightPathPredictor.PREDICTION_DURATION,
                    ship, useModifiedShieldMult = true)
            } else {
                // Approximate raw armor/hull impact if unshielded
                damageTimeline.instances.sumOf { inst ->
                    val mult = when (inst.type) {
                        DamageType.KINETIC -> 0.5f
                        DamageType.HIGH_EXPLOSIVE -> 2.0f
                        DamageType.ENERGY -> 1.0f
                        DamageType.FRAGMENTATION -> 0.25f
                        else -> 1.0f
                    }
                    (inst.amount * mult * 10f).toDouble()
                }.toFloat()
            }

            val dangerRatio = (ship.currFlux + fluxGained) / ship.maxFlux.coerceAtLeast(1f)
            val clampedDanger = dangerRatio.coerceIn(0f, 1f)
            val hue = 0.55f * (1f - clampedDanger)
            val heatmapColor = Color.getHSBColor(hue, 1f, 1f)

            GL11.glColor4f(heatmapColor.red / 255f, heatmapColor.green / 255f, heatmapColor.blue / 255f, 0.8f)
            drawTimeline(result.shipTimeline)
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

        FlightPathPredictorManager.getInstance(engine).renderDebug(viewport)
    }
}
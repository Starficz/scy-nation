package org.scy.plugins

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import com.fs.starfarer.api.loading.MissileSpecAPI
import com.fs.starfarer.api.util.Misc
import kotlinx.coroutines.*
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.ext.*
import org.lwjgl.opengl.GL11
import org.lwjgl.util.vector.Vector2f
import org.magiclib.kotlin.getDistance
import org.magiclib.kotlin.getDistanceSq
import org.magiclib.kotlin.normalizeAngle
import org.scy.*
import org.scy.hullmods.ScyEngineering
import java.awt.Color
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

object FlightPathPredictor {
    const val TIME_STEP = 0.05f
    const val PREDICTION_DURATION = 20f
    val TOTAL_FUTURE_STATES = ceil(PREDICTION_DURATION / TIME_STEP).toInt()
    const val ENGINE_COAST_ASSUMPTION = 5f
}

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

class PredictorMemory {
    private val timelinePool = ConcurrentLinkedQueue<Array<FutureShipState>>()

    fun borrowTimeline(): Array<FutureShipState> {
        return timelinePool.poll() ?: Array(FlightPathPredictor.TOTAL_FUTURE_STATES) { FutureShipState() }
    }

    fun releaseTimeline(timeline: Array<FutureShipState>) {
        timelinePool.offer(timeline)
    }
}

class MobilityProfile(
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
    val accelDir: Vector2f,
    val mobility: MobilityProfile?
)

class DamageTimeline(val startTime: Float) {
    val damageInstances = mutableListOf<DamageInstance>()
    val lagBuffer = 0.5f

    data class DamageInstance(
        val time: Float, // global time
        val amount: Float,
        val hitStrength: Float,
        val empAmount: Float,
        val type: DamageType,
        val conservative: Boolean
    )

    fun addInstance(
        time: Float,
        amount: Float,
        scale: Float,
        hitStrength: Float,
        type: DamageType,
        empAmount: Float,
        conservative: Boolean
    ) {
        val finalVal = amount * scale
        val finalEmp = empAmount * scale
        if (finalVal > 0f || finalEmp > 0f) {
            damageInstances.add(
                DamageInstance(time,
                    finalVal,
                    hitStrength,
                    finalEmp,
                    type,
                    conservative)
            )
        }
    }

    /**
     * Calculates the estimated flux the ship's shields will take over the next X seconds.
     * REQUIRES the current absolute engine time.
     */
    fun fluxToShield(
        currentTime: Float,
        nextXSeconds: Float = 5f,
        ship: ShipAPI,
        useModifiedShieldMult: Boolean = false
    ): Float {
        if (ship.shield == null || ship.shield.type == ShieldAPI.ShieldType.NONE) {
            return 0f
        }

        val stats = ship.mutableStats ?: return 0f
        val shieldEffMult = ship.shield.fluxPerPointOfDamage *
            if (useModifiedShieldMult) stats.shieldDamageTakenMult.modifiedValue
            else stats.shieldDamageTakenMult.base

        var totalFlux = 0f
        val endTime = currentTime + nextXSeconds

        for (damage in damageInstances) {
            // Check absolute bounds
            if (damage.time !in (currentTime - lagBuffer)..endTime) continue
            if (damage.conservative) continue

            val damageEffMult = when (damage.type) {
                DamageType.FRAGMENTATION -> 0.25f * stats.fragmentationDamageTakenMult.modifiedValue
                DamageType.KINETIC -> 2f * stats.kineticDamageTakenMult.modifiedValue
                DamageType.HIGH_EXPLOSIVE -> 0.5f * stats.highExplosiveDamageTakenMult.modifiedValue
                DamageType.ENERGY -> stats.energyDamageTakenMult.modifiedValue
                else -> 1f
            }

            totalFlux += damage.amount * damageEffMult * shieldEffMult
        }

        return totalFlux
    }

    /**
     * Calculates the estimated armor and hull damage the ship will take over the next X seconds.
     * Simulates consecutive hits to track armor stripping.
     * REQUIRES the current absolute engine time.
     *
     * @param startingArmor The starting armor value. Defaults to the ship's base overall armor rating.
     * @return Pair containing (Total Armor Damage Taken, Total Hull Damage Taken)
     */
    fun damageToArmorAndHull(
        currentTime: Float,
        nextXSeconds: Float = 5f,
        ship: ShipAPI,
        startingArmor: Float = ship.armorGrid.armorAtCell(ship.armorGrid.weakestArmorRegion()!!) ?: 0f
    ): Pair<Float, Float> {
        if (ship.mutableStats == null) return Pair(0f, 0f)

        var totalArmorDamage = 0f
        var totalHullDamage = 0f
        val endTime = currentTime + nextXSeconds

        // Track armor state throughout the prediction timeframe
        var currentArmor = startingArmor

        for (damage in damageInstances) {
            // Check absolute bounds
            if (damage.time !in (currentTime - lagBuffer)..endTime) continue

            // Evaluate damage using your helper
            val (armorDmg, hullDmg) = damageAfterArmor(
                damageType = damage.type,
                damage = damage.amount,
                hitStrength = damage.hitStrength,
                armorValue = currentArmor,
                ship = ship
            )

            totalArmorDamage += armorDmg
            totalHullDamage += hullDmg

            // Subtract the armor damage from our local armor tracker
            // bounded at 0f (Minimum Armor is accounted for inside damageAfterArmor)
            currentArmor = max(0f, currentArmor - armorDmg)
        }

        return Pair(totalArmorDamage, totalHullDamage)
    }

    data class VentingDamageResult(
        val ventingArmorDamage: Float,
        val ventingHullDamage: Float,
        val notVentingArmorDamage: Float,
        val notVentingHullDamage: Float
    ) {
        val totalVentingDamage: Float get() = ventingArmorDamage + ventingHullDamage
        val totalNotVentingDamage: Float get() = notVentingArmorDamage + notVentingHullDamage

        // not every enemy will actually target you. only flip true if we are fairly sure.
        val isVentingSafer: Boolean get() = totalVentingDamage * 1.3f < totalNotVentingDamage
    }

    /**
     * Queries if venting will result in more or less total armor+hull damage than not venting.
     * Accounts for enemies holding "conservative" attacks until shields are down.
     */
    fun compareVentingVsNotVenting(
        currentTime: Float,
        dangerTime: Float,
        spareFlux: Float,
        ship: ShipAPI,
        useModifiedShieldMult: Boolean = false,
        startingArmor: Float = ship.armorGrid.armorAtCell(ship.armorGrid.weakestArmorRegion()!!) ?: 0f
    ): VentingDamageResult {
        val stats = ship.mutableStats ?: return VentingDamageResult(0f, 0f, 0f, 0f)

        // 1. Calculate damage if venting (Shields down for dangerTime).
        // Since shields are down, enemies won't delay conservative hits. They hit right on schedule.
        // Your existing damageToArmorAndHull already evaluates all hits indiscriminately.
        val (ventArmor, ventHull) = damageToArmorAndHull(currentTime, dangerTime, ship, startingArmor)

        // 2. Calculate damage if not venting (Shields absorb up to spareFlux).
        var notVentingArmorDamage = 0f
        var notVentingHullDamage = 0f
        var currentArmor = startingArmor
        var remainingFlux = spareFlux

        // Queue to hold conservative hits while shields are up
        val pendingConservativeHits = mutableListOf<DamageInstance>()

        val shieldEffMult = if (ship.shield != null && ship.shield.type != ShieldAPI.ShieldType.NONE) {
            ship.shield.fluxPerPointOfDamage *
                    if (useModifiedShieldMult) stats.shieldDamageTakenMult.modifiedValue
                    else stats.shieldDamageTakenMult.base
        } else {
            0f
        }
        val hasShield = shieldEffMult > 0f
        val endTime = currentTime + dangerTime

        // Sort instances by time to simulate chronological shield hits and flux buildup
        val instancesToEvaluate = damageInstances
            .filter { it.time in (currentTime - lagBuffer)..endTime }
            .sortedBy { it.time }

        for (damage in instancesToEvaluate) {
            if (!hasShield || remainingFlux <= 0f) {
                // Shields are down (either naturally or broken).
                // Any incoming hit (conservative or not) hits armor/hull immediately.
                val (armorDmg, hullDmg) = damageAfterArmor(
                    damageType = damage.type,
                    damage = damage.amount,
                    hitStrength = damage.hitStrength,
                    armorValue = currentArmor,
                    ship = ship
                )
                notVentingArmorDamage += armorDmg
                notVentingHullDamage += hullDmg
                currentArmor = max(0f, currentArmor - armorDmg)
            } else {
                // Shield is currently UP
                if (damage.conservative) {
                    // Enemy holds fire, waiting for shields to drop
                    pendingConservativeHits.add(damage)
                } else {
                    // Enemy fires at shield
                    val damageEffMult = when (damage.type) {
                        DamageType.FRAGMENTATION -> 0.25f * stats.fragmentationDamageTakenMult.modifiedValue
                        DamageType.KINETIC -> 2f * stats.kineticDamageTakenMult.modifiedValue
                        DamageType.HIGH_EXPLOSIVE -> 0.5f * stats.highExplosiveDamageTakenMult.modifiedValue
                        DamageType.ENERGY -> stats.energyDamageTakenMult.modifiedValue
                        else -> 1f
                    }

                    val fluxTaken = damage.amount * damageEffMult * shieldEffMult

                    if (remainingFlux >= fluxTaken) {
                        // Shield fully absorbs the hit
                        remainingFlux -= fluxTaken
                    } else {
                        val (armorDmg, hullDmg) = damageAfterArmor(
                            damageType = damage.type,
                            damage = damage.amount,
                            hitStrength = damage.hitStrength,
                            armorValue = currentArmor,
                            ship = ship
                        )
                        notVentingArmorDamage += armorDmg
                        notVentingHullDamage += hullDmg
                        currentArmor = max(0f, currentArmor - armorDmg)

                        // THE SHIELDS ARE NOW DOWN!
                        // Enemies immediately unleash all the conservative shots they were holding.
                        for (heldDamage in pendingConservativeHits) {
                            val (heldArmorDmg, heldHullDmg) = damageAfterArmor(
                                damageType = heldDamage.type,
                                damage = heldDamage.amount,
                                hitStrength = heldDamage.hitStrength,
                                armorValue = currentArmor,
                                ship = ship
                            )
                            notVentingArmorDamage += heldArmorDmg
                            notVentingHullDamage += heldHullDmg
                            currentArmor = max(0f, currentArmor - heldArmorDmg)
                        }
                        // Clear the queue so we don't apply them twice
                        pendingConservativeHits.clear()
                    }
                }
            }
        }

        return VentingDamageResult(
            ventingArmorDamage = ventArmor,
            ventingHullDamage = ventHull,
            notVentingArmorDamage = notVentingArmorDamage,
            notVentingHullDamage = notVentingHullDamage
        )
    }
}

class CombatSnapshot(
    val ships: List<ShipStateSnapshot>,
    val weapons: HashMap<String, List<WeaponSnapshot>>,
    val missiles: List<MissileSnapshot>
)

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
    val hitStrength: Float,
    val firingTime: Float,
    val cooldownTime: Float,
    val currentlyFiring: Boolean,
    val timeLeftInState: Float,
    val disabledTime: Float,
    val conservative: Boolean,
    val ammoLeft: Int?
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
    hitStrength: Float,
    firingTime: Float,
    cooldownTime: Float,
    currentlyFiring: Boolean,
    timeLeftInState: Float,
    disabledTime: Float,
    conservative: Boolean,
    ammoLeft: Int?,
    val projectileSpeed: Float,
    val maxSpread: Float
) : WeaponSnapshot(
    localMountOffset, localRestingAngle, localCurrentAngle, arc, range, turnRate,
    damageType, empPerBurst, damagePerBurst, hitStrength, firingTime,
    cooldownTime, currentlyFiring, timeLeftInState, disabledTime, conservative, ammoLeft
)

class MissileWeaponSnapshot(
    localMountOffset: Vector2f, localRestingAngle: Float, localCurrentAngle: Float,
    arc: Float, range: Float, turnRate: Float, damageType: DamageType,
    empPerBurst: Float, damagePerBurst: Float, hitStrength: Float,
    firingTime: Float, cooldownTime: Float, currentlyFiring: Boolean, timeLeftInState: Float,
    disabledTime: Float,
    conservative: Boolean,
    ammoLeft: Int?,
    val missileAccel: Float, val missileMaxSpeed: Float,
    val missileTurnRate: Float, val missileTurnAccel: Float,
    val maxFlightTime: Float,
    val doNotAim: Boolean
) : WeaponSnapshot(
    localMountOffset, localRestingAngle, localCurrentAngle, arc, range, turnRate,
    damageType, empPerBurst, damagePerBurst, hitStrength, firingTime,
    cooldownTime, currentlyFiring, timeLeftInState, disabledTime, conservative, ammoLeft
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
    val conservative: Boolean,
    val targetId: String?
)

class FiringSolution(val isValid: Boolean, val hitChance: Float, val distance: Float)

class FutureShipState(
    var timestamp: Float = 0f,
    var facing: Float = 0f
) {
    // Allocate exactly once
    val location = Vector2f()

    // Helper to mutate without allocating
    fun set(other: FutureShipState) {
        this.timestamp = other.timestamp
        this.facing = other.facing
        this.location.set(other.location)
    }

    fun set(time: Float, loc: Vector2f, face: Float? = null) {
        this.timestamp = time
        this.location.set(loc)
        if (face != null) this.facing = face
    }
}

class RequestResult(
    val damageTimeline: DamageTimeline,
    val shipTimeline: Array<FutureShipState>
)

class SimulationOutput(
    val enemyTimelines: Map<String, Array<FutureShipState>>,
    val results: Map<RequestKey, RequestResult>
)


fun captureCombatState(engine: CombatEngineAPI): CombatSnapshot {
    val weaponSnaps = HashMap<String, List<WeaponSnapshot>>()

    val shipSnaps = engine.ships
        .mapNotNull { ship ->
            if (ship.isShuttlePod || ship.isFighter || ship.isHulk || !ship.isAlive || ship.isPiece) return@mapNotNull null

            weaponSnaps[ship.id] = captureWeaponState(ship)

            val parent = if (ship.isStationModule) ship.parentStation else null

            var modDist = 0f
            var modAngle = 0f
            var modFacing = 0f

            if (parent != null) {
                modDist = parent.location.getDistance(ship.location)
                modAngle = (parent.location.getAngle(ship.location) - parent.facing).normalizeAngle()
                modFacing = (ship.facing - parent.facing).normalizeAngle()
            }

            ShipStateSnapshot(
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
                engineAccel = ship.engineController?.isAccelerating == true,
                engineBack = ship.engineController?.isAcceleratingBackwards == true,
                engineLeft = ship.engineController?.isStrafingLeft == true,
                engineRight = ship.engineController?.isStrafingRight == true,
                isOverloaded = ship.fluxTracker.isOverloaded,
                overloadTime = ship.fluxTracker.overloadTimeRemaining,
                isVenting = ship.fluxTracker.isVenting,
                ventTime = ship.fluxTracker.timeToVent,
                parentId = parent?.id,
                moduleOffsetDist = modDist,
                moduleOffsetAngle = modAngle,
                moduleFacingOffset = modFacing
            )
        }

    val missileSnaps = engine.projectiles
        .mapNotNull { proj ->
             if (proj !is MissileAPI || proj.isFading || proj.isFlare ) return@mapNotNull null

            var targetId: String? = null
            val ai = proj.missileAI
            if (ai is GuidedMissileAI) {
                val target = ai.target
                if (target is ShipAPI) targetId = target.id
            }

            MissileSnapshot(
                location = proj.location.copy(),
                velocity = proj.velocity.copy(),
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
                conservative = false,
                targetId = targetId
            )
        }

    return CombatSnapshot(shipSnaps, weaponSnaps, missileSnaps)
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
        val effArc = if (isHardpoint) 20f else weapon.arc
        val maxSpread = (if (isHardpoint) spec.maxSpread / 2f else spec.maxSpread) * ship.mutableStats.maxRecoilMult.modifiedValue
        val conservative = weapon.usesAmmo() && weapon.ammoPerSecond < 0.2f && !spec.aiHints.contains(WeaponAPI.AIHints.DO_NOT_CONSERVE)

        val firingTime: Float
        val cooldownTime: Float
        val damagePerBurst: Float
        val empPerBurst: Float
        val hitStrength: Float
        var currentlyFiring = weapon.isFiring
        val timeLeftInState: Float

        when {
            weapon.isBurstBeam -> {
                firingTime = weapon.derivedStats.burstFireDuration
                cooldownTime = weapon.cooldown
                damagePerBurst = weapon.derivedStats.burstDamage
                hitStrength = weapon.damage.damage / 2
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
                hitStrength = weapon.damage.damage / 2
                empPerBurst = weapon.derivedStats.empPerSecond
                timeLeftInState = 0f
            }
            spec.burstSize > 1 -> {
                firingTime = weapon.derivedStats.burstFireDuration
                cooldownTime = weapon.cooldown
                damagePerBurst = weapon.damage.damage * spec.burstSize
                hitStrength = weapon.damage.damage
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
                hitStrength = weapon.damage.damage
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

            // If the missile has DO_NOT_AIM, its a tracking missile. simulate it kinematically.
            if (doNotAim) {
                MissileWeaponSnapshot(
                    localMountOffset = localMountOffset,
                    localRestingAngle = weapon.arcFacing,
                    localCurrentAngle = localCurrentAngle,
                    arc = effArc,
                    range = weapon.range + weapon.projectileFadeRange/2,
                    turnRate = effTurnRate,
                    damageType = weapon.damageType,
                    empPerBurst = empPerBurst,
                    damagePerBurst = damagePerBurst,
                    hitStrength = hitStrength,
                    firingTime = firingTime.coerceAtLeast(0.1f),
                    cooldownTime = cooldownTime.coerceAtLeast(0.0f),
                    currentlyFiring = currentlyFiring,
                    timeLeftInState = timeLeftInState.coerceAtLeast(0.01f),
                    conservative = conservative,
                    disabledTime = if (weapon.isDisabled) weapon.disabledDuration else 0f,
                    ammoLeft = if (weapon.usesAmmo()) weapon.ammo / spec.burstSize else null,
                    missileAccel = mAccel,
                    missileMaxSpeed = mMaxSpeed,
                    missileTurnRate = mTurnRate,
                    missileTurnAccel = mTurnAccel,
                    maxFlightTime = mSpec.maxFlightTime,
                    doNotAim = doNotAim
                )
            } else {
                // Otherwise, it's an unguided rocket. Treat it identically to a standard projectile,
                // using its max speed as the constant projectile speed.
                ProjectileWeaponSnapshot(
                    localMountOffset = localMountOffset,
                    localRestingAngle = weapon.arcFacing,
                    localCurrentAngle = localCurrentAngle,
                    arc = effArc,
                    range = weapon.range + weapon.projectileFadeRange/2,
                    turnRate = effTurnRate,
                    damageType = weapon.damageType,
                    empPerBurst = empPerBurst,
                    damagePerBurst = damagePerBurst,
                    hitStrength = hitStrength,
                    firingTime = firingTime.coerceAtLeast(0.1f),
                    cooldownTime = cooldownTime.coerceAtLeast(0.0f),
                    currentlyFiring = currentlyFiring,
                    timeLeftInState = timeLeftInState.coerceAtLeast(0.01f),
                    conservative = conservative,
                    disabledTime = if (weapon.isDisabled) weapon.disabledDuration else 0f,
                    ammoLeft = if (weapon.usesAmmo()) weapon.ammo / spec.burstSize else null,
                    projectileSpeed = mMaxSpeed,
                    maxSpread = maxSpread
                )
            }
        } else {
            // Standard Energy/Ballistic weapon
            ProjectileWeaponSnapshot(
                localMountOffset = localMountOffset,
                localRestingAngle = weapon.arcFacing,
                localCurrentAngle = localCurrentAngle,
                arc = effArc,
                range = weapon.range + weapon.projectileFadeRange/4,
                turnRate = effTurnRate,
                damageType = weapon.damageType,
                empPerBurst = empPerBurst,
                damagePerBurst = damagePerBurst,
                hitStrength = hitStrength,
                firingTime = firingTime.coerceAtLeast(0.1f),
                cooldownTime = cooldownTime.coerceAtLeast(0.0f),
                currentlyFiring = currentlyFiring,
                timeLeftInState = timeLeftInState.coerceAtLeast(0.01f),
                conservative = conservative,
                disabledTime = if (weapon.isDisabled) weapon.disabledDuration else 0f,
                ammoLeft = if (weapon.usesAmmo()) weapon.ammo / spec.burstSize else null,
                projectileSpeed = if (spec is BeamWeaponSpecAPI) spec.beamSpeed else weapon.projectileSpeed.coerceAtLeast(100f),
                maxSpread = maxSpread
            )
        }
    }
}

fun generateFlightPath(
    ship: ShipStateSnapshot,
    startTime: Float,
    resultTarget: Array<FutureShipState>,
    accelDir: Vector2f? = null,
    mobility: MobilityProfile? = null
) {
    if (ship.parentId != null) return // process modules later

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
            val fwdComponent = accelNormalized.dot(fwdUnitVector)
            val latComponent = accelNormalized.dot(leftUnitVector)

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

    val loc = Vector2f(ship.location)
    val vel = Vector2f(ship.velocity)

    for (i in 0 until FlightPathPredictor.TOTAL_FUTURE_STATES) {
        val elapsedTime = (i + 1) * FlightPathPredictor.TIME_STEP
        val inPhase1 = elapsedTime <= duration1
        val currentMaxSpeed = if (inPhase1) spd1 else spd2
        val currentAccel = if (inPhase1) shipAccel1 else shipAccel2

        loc.addScaled(vel, FlightPathPredictor.TIME_STEP)

        if (elapsedTime < FlightPathPredictor.ENGINE_COAST_ASSUMPTION || accelDir != null) {
            vel.addScaled(currentAccel, FlightPathPredictor.TIME_STEP)
        }

        if (vel.lengthSquared() > currentMaxSpeed * currentMaxSpeed) {
            vel.normalise()
            vel.scale(currentMaxSpeed)
        }

        resultTarget[i].set(startTime + elapsedTime, loc)
    }
}

fun updateFlightPathFacings(
    flightPaths: Map<String, Array<FutureShipState>>,
    ships: List<ShipStateSnapshot>
) {
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
                    // enemy ships that arnt modules
                    if (ship.owner == otherShip.owner || otherShip.parentId != null) continue

                    val otherLoc = otherTimeline[i].location
                    val distSq = myLoc.getDistanceSq(otherLoc)

                    if (distSq < minDistanceSq) {
                        minDistanceSq = distSq
                        targetLoc = otherLoc
                    }
                }
            }

            if (targetLoc != null) {
                val angleToTarget = myLoc.getAngle(targetLoc)
                val rotationNeeded = MathUtils.getShortestRotation(currentFacing, angleToTarget)
                val stoppingDist = (currentAngVel * currentAngVel) / (2f * ship.turnAcceleration)
                val movingTowards = (sign(rotationNeeded) == sign(currentAngVel)) && abs(currentAngVel) > 0.1f

                if (movingTowards && abs(rotationNeeded) <= stoppingDist) {
                    val change = ship.turnAcceleration * FlightPathPredictor.TIME_STEP
                    if (abs(currentAngVel) <= change) currentAngVel =
                        0f else currentAngVel -= sign(currentAngVel) * change
                } else {
                    currentAngVel += sign(rotationNeeded) * ship.turnAcceleration * FlightPathPredictor.TIME_STEP
                }
            } else {
                if (abs(currentAngVel) > 0) {
                    val change = ship.turnAcceleration * FlightPathPredictor.TIME_STEP
                    if (abs(currentAngVel) <= change) currentAngVel =
                        0f else currentAngVel -= sign(currentAngVel) * change
                }
            }

            currentAngVel = currentAngVel.coerceIn(-ship.maxTurnRate, ship.maxTurnRate)
            currentFacing = (currentFacing + (currentAngVel * FlightPathPredictor.TIME_STEP)).normalizeAngle()
            timeline[i].facing = currentFacing
        }
    }
}

fun updateModulePositions(
    flightPaths: Map<String, Array<FutureShipState>>,
    ships: List<ShipStateSnapshot>
) {
    for (module in ships) {
        if (module.parentId == null) continue

        val parentTimeline = flightPaths[module.parentId] ?: continue
        val moduleTimeline = flightPaths[module.id] ?: continue

        for (i in 0 until FlightPathPredictor.TOTAL_FUTURE_STATES) {
            val pState = parentTimeline[i]

            val angle = pState.facing  + module.moduleOffsetAngle
            val loc = MathUtils.getPointOnCircumference(pState.location, module.moduleOffsetDist, angle)

            moduleTimeline[i].location.set(loc)
            moduleTimeline[i].facing = (pState.facing + module.moduleFacingOffset).normalizeAngle()
        }
    }
}

suspend fun simulateFlightPathsCoroutines(
    currentTime: Float,
    combatState: CombatSnapshot,
    requests: List<RequestKey>,
    timelinePool: PredictorMemory
): SimulationOutput = coroutineScope {

    val basePositionalPaths = combatState.ships.associate { ship ->
        val timeline = timelinePool.borrowTimeline()
        generateFlightPath(ship, currentTime, timeline)
        ship.id to timeline
    }

    updateFlightPathFacings(basePositionalPaths, combatState.ships)
    updateModulePositions(basePositionalPaths, combatState.ships)

    val aiShipIds = requests.map { it.shipId }.toSet()

    val deferredResults = requests.map { req ->
        async {
            val targetData = combatState.ships.find { it.id == req.shipId } ?: return@async null
            val resultDamage = DamageTimeline(currentTime)

            val reqPaths = mutableMapOf<String, Array<FutureShipState>>()
            for ((id, baseTimeline) in basePositionalPaths) {
                val copiedTimeline = timelinePool.borrowTimeline()
                for (i in 0 until FlightPathPredictor.TOTAL_FUTURE_STATES) {
                    copiedTimeline[i].set(baseTimeline[i])
                }
                reqPaths[id] = copiedTimeline
            }

            generateFlightPath(
                ship = targetData,
                startTime = currentTime,
                resultTarget = reqPaths[targetData.id]!!,
                accelDir = req.accelDir,
                mobility = req.mobility
            )

            updateFlightPathFacings(reqPaths, combatState.ships)
            updateModulePositions(reqPaths, combatState.ships)

            val targetTimeline = reqPaths[targetData.id]!!

            // Temporary list to hold newly fired future-missiles
            val predictedMissiles = mutableListOf<MissileSnapshot>()

            for (enemyData in combatState.ships) {
                if (enemyData.owner == targetData.owner || enemyData.id == targetData.id) continue

                val shipSilenceTime = if (enemyData.isOverloaded) enemyData.overloadTime
                else if (enemyData.isVenting) enemyData.ventTime else 0f

                val enemyTimeline = reqPaths[enemyData.id]!!
                val weapons = combatState.weapons[enemyData.id] ?: continue

                for (snap in weapons) {
                    simulateWeaponTimeline(
                        snap = snap, enemyData = enemyData, enemyTimeline = enemyTimeline,
                        targetData = targetData, targetTimeline = targetTimeline,
                        shipSilenceTime = shipSilenceTime, outDamage = resultDamage,
                        generatedMissiles = predictedMissiles, // <--- PASS IT HERE
                        startTime = currentTime
                    )
                }
            }

            // Combine real in-flight missiles with predicted future missiles
            val allMissilesToSimulate = combatState.missiles + predictedMissiles

            simulateMissileTimelines(
                missiles = allMissilesToSimulate,
                targetData = targetData,
                targetTimeline = targetTimeline,
                allShipTimelines = reqPaths,
                outDamage = resultDamage
            )

            // give the pooled array directly to the RequestResult
            val result = RequestResult(resultDamage, targetTimeline)

            // Cleanup the unused reqPaths back to the pool
            for ((id, timeline) in reqPaths) {
                if (id != targetData.id) timelinePool.releaseTimeline(timeline)
            }

            req to result
        }
    }

    val resultsMap = deferredResults.awaitAll().filterNotNull().toMap()
    val baseEnemyTimelines = mutableMapOf<String, Array<FutureShipState>>()

    for ((id, baseTimeline) in basePositionalPaths) {
        if (!aiShipIds.contains(id)) {
            baseEnemyTimelines[id] = baseTimeline
        } else {
            timelinePool.releaseTimeline(baseTimeline)
        }
    }

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
    var currentRelativeTime = snap.disabledTime
    var isFiring = snap.currentlyFiring
    var timeLeftInState = snap.timeLeftInState
    var ammoLeft = snap.ammoLeft

    while (currentRelativeTime < FlightPathPredictor.PREDICTION_DURATION) {
        if (isFiring) {
            val burstDuration = min(timeLeftInState, FlightPathPredictor.PREDICTION_DURATION - currentRelativeTime)
            if (burstDuration <= 0f) break

            val sampleTime = currentRelativeTime + (burstDuration * 0.5f)
            val solution = getFiringSolution(sampleTime, snap, enemyTimeline, targetData, targetTimeline)

            if (solution.isValid) {
                val fraction = burstDuration / snap.firingTime

                // ammo check
                if (ammoLeft != null){
                    if (ammoLeft <= 0) break
                    ammoLeft -= 1
                }

                if (snap is MissileWeaponSnapshot) {
                    val frameIdx = (sampleTime / FlightPathPredictor.TIME_STEP).toInt().coerceIn(0, FlightPathPredictor.TOTAL_FUTURE_STATES - 1)
                    val currEnemy = enemyTimeline[frameIdx]
                    val currMountLoc = currEnemy.location + snap.localMountOffset.copy().rotate(currEnemy.facing)

                    generatedMissiles.add(
                        MissileSnapshot(
                            location = currMountLoc,
                            velocity = enemyData.velocity.copy(),
                            facing = currEnemy.facing,
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
                            conservative = snap.conservative,
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
                            hitStrength = snap.hitStrength,
                            type = snap.damageType,
                            empAmount = snap.empPerBurst * fraction,
                            conservative = snap.conservative,
                        )
                    }
                }
            }

            currentRelativeTime += timeLeftInState
            isFiring = false
            timeLeftInState = snap.cooldownTime

        } else {
            if (timeLeftInState > 0f) {
                currentRelativeTime += timeLeftInState
                timeLeftInState = 0f
            }

            if (currentRelativeTime < shipSilenceTime) currentRelativeTime = shipSilenceTime
            if (currentRelativeTime >= FlightPathPredictor.PREDICTION_DURATION) break

            val nextFireTime = findNextFiringWindow(currentRelativeTime, snap, enemyTimeline, targetData, targetTimeline)

            if (nextFireTime != null) {
                currentRelativeTime = nextFireTime
                isFiring = true
                timeLeftInState = snap.firingTime
            } else {
                break
            }
        }
    }
}

private fun findNextFiringWindow(
    startTime: Float, snap: WeaponSnapshot, enemyTimeline: Array<FutureShipState>,
    targetData: ShipStateSnapshot, targetTimeline: Array<FutureShipState>
): Float? {
    var searchT = startTime
    while (searchT < FlightPathPredictor.PREDICTION_DURATION) {
        val solution = getFiringSolution(searchT, snap, enemyTimeline, targetData, targetTimeline)
        if (solution.isValid) return searchT
        searchT += FlightPathPredictor.TIME_STEP
    }
    return null
}

private fun getFiringSolution(
    timeElapsed: Float, snap: WeaponSnapshot, enemyTimeline: Array<FutureShipState>,
    targetData: ShipStateSnapshot, targetTimeline: Array<FutureShipState>
): FiringSolution {
    val frameIdx = (timeElapsed / FlightPathPredictor.TIME_STEP).toInt().coerceIn(0, FlightPathPredictor.TOTAL_FUTURE_STATES - 1)

    val targetState = targetTimeline[frameIdx]
    val enemyState = enemyTimeline[frameIdx]

    val currMountLoc = enemyState.location + snap.localMountOffset.copy().rotate(enemyState.facing)

    val dist = currMountLoc.getDistance(targetState.location)
    val maxDist = (snap.range + targetData.shieldRadius)

    if (dist > maxDist) return FiringSolution(false, 0f, dist)

    // most missiles can fire at anything in range
    if (snap is MissileWeaponSnapshot && snap.doNotAim) {
        return FiringSolution(true, 1f, dist)
    }

    // --- NEW: Calculate the Angular Radius of the target ---
    val angularRadius = if (dist > 0.1f) {
        // asin takes a value from -1.0 to 1.0. Coerce it to prevent NaN if radius > distance (overlapping ships)
        val ratio = (targetData.shieldRadius / dist).coerceIn(-1.0f, 1.0f)
        kotlin.math.asin(ratio) * (180f / kotlin.math.PI.toFloat()) // Convert radians to degrees
    } else {
        180f // We are inside the target, it occupies our entire field of view
    }

    val angleToTarget = currMountLoc.getAngle(targetState.location)
    val globalRestingAngle = MathUtils.clampAngle(enemyState.facing + snap.localRestingAngle)
    val angleDiffResting = abs(MathUtils.getShortestRotation(globalRestingAngle, angleToTarget))

    if (angleDiffResting > snap.arc * 0.5f) return FiringSolution(false, 0f, dist)

    val effectiveTurnTime = max(0f, timeElapsed - snap.disabledTime)
    val maxPossibleTurn = snap.turnRate * effectiveTurnTime
    val startGlobalAngle = enemyState.facing + snap.localCurrentAngle
    val requiredTurn = abs(MathUtils.getShortestRotation(startGlobalAngle, angleToTarget))

    if (requiredTurn > maxPossibleTurn + angularRadius)
        return FiringSolution(false, 0f, dist)

    // Missiles track perfectly once fired
    if (snap is MissileWeaponSnapshot) {
        return FiringSolution(true, 1f, dist)
    }

    snap as ProjectileWeaponSnapshot
    val halfSpreadRad = (snap.maxSpread / 2f) * (PI.toFloat() / 180f)
    val spreadRadius = dist * tan(halfSpreadRad)

    val hitChance = if (spreadRadius <= targetData.shieldRadius) 1f else targetData.shieldRadius / spreadRadius

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
                    outDamage.addInstance(
                        targetState.timestamp,
                        missile.damageAmount, 1f,
                        missile.damageAmount,
                        missile.damageType,
                        missile.empAmount,
                        missile.conservative
                    )
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
                if (distSq <= (targetData.shieldRadius + 50f) * (targetData.shieldRadius + 50f)) {
                    outDamage.addInstance(
                        targetState.timestamp,
                        missile.damageAmount, 1f,
                        missile.damageAmount,
                        missile.damageType,
                        missile.empAmount,
                        missile.conservative
                    )
                    break // Missile hit, destroy it from timeline
                }
            }
            currentFlightTime += timeStep
        }
    }
}

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

    val timelinePool = PredictorMemory()

    @Volatile private var isSimulating = false
    private val outputRef = AtomicReference<SimulationOutput?>(null)

    private val pendingRequests = HashMap<RequestKey, Float>()
    private var latestResults: Map<RequestKey, RequestResult> = emptyMap()
    private var latestEnemyTimelines: Map<String, Array<FutureShipState>> = emptyMap()

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
                isOverridden = { oldKey, newBatch -> newBatch.keys.any { isNear(oldKey, it) } },
                onDiscard = { timelinePool.releaseTimeline(it.shipTimeline) }
            )

            latestEnemyTimelines = mergeWithRetention(
                oldMap = latestEnemyTimelines,
                newMap = completedOutput.enemyTimelines,
                timestamps = timelineTimestamps,
                currentTime = currentTime,
                isOverridden = { oldKey, newBatch -> newBatch.containsKey(oldKey) },
                onDiscard = { oldTimeline -> timelinePool.releaseTimeline(oldTimeline) }
            )

            isSimulating = false
        }

        if (!isSimulating && pendingRequests.isNotEmpty()) {

            // STALENESS DROP: Only process requests younger than 0.1s.
            // If the threadpool fell behind, this throws away the backlog so it can catch up.
            val freshestRequests = pendingRequests.entries
                .filter { currentTime - it.value <= 0.1f }
                .map { it.key }

            pendingRequests.clear()

            // If everything was too stale, skip simulating this frame
            if (freshestRequests.isNotEmpty()) {
                val combatState = captureCombatState(engine)

                isSimulating = true
                PredictorThreadPool.scope.launch {
                    try {
                        val result = simulateFlightPathsCoroutines(currentTime, combatState, freshestRequests, timelinePool)
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
        threshold: Float = 0.5f,
        isOverridden: (K, Map<K, V>) -> Boolean,
        onDiscard: (V) -> Unit
    ): Map<K, V> {
        val merged = mutableMapOf<K, V>()

        for ((key, value) in oldMap) {
            val age = currentTime - (timestamps[key] ?: currentTime)
            if (age > threshold || isOverridden(key, newMap)) {
                timestamps.remove(key)
                onDiscard(value)
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

            // Calculate total flux over the full prediction duration for the heat map
            val fluxGained = if (ship.shield != null && ship.shield.type != ShieldAPI.ShieldType.NONE) {
                damageTimeline.fluxToShield(
                    engine.getTotalElapsedTime(false),
                    FlightPathPredictor.PREDICTION_DURATION,
                    ship, useModifiedShieldMult = true)
            } else {
                // Approximate raw armor/hull impact if unshielded
                damageTimeline.damageInstances.sumOf { inst ->
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
            val behaviorState = ship.customData["SCY_currentState"] as? ScyEngineering.ScyAiV2.BehaviorState ?: continue


            when (behaviorState) {
                ScyEngineering.ScyAiV2.BehaviorState.ADVANCE -> GL11.glColor4f(0f, 1f, 0f, 0.8f)
                ScyEngineering.ScyAiV2.BehaviorState.BACKOFF  -> GL11.glColor4f(1f, 0.5f, 0f, 0.8f)
                ScyEngineering.ScyAiV2.BehaviorState.DISENGAGE  -> GL11.glColor4f(1f, 0f, 0f, 0.8f)
                ScyEngineering.ScyAiV2.BehaviorState.STANDOFF  -> GL11.glColor4f(1f, 1f, 0f, 0.8f)
                ScyEngineering.ScyAiV2.BehaviorState.VENTING  -> GL11.glColor4f(1f, 1f, 1f, 0.8f)
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

    private fun drawTimeline(timeline: Array<FutureShipState>) {
        GL11.glLineWidth(2f)
        GL11.glBegin(GL11.GL_LINE_STRIP)
        for (state in timeline) {
            GL11.glVertex2f(state.location.x, state.location.y)
        }
        GL11.glEnd()

        GL11.glBegin(GL11.GL_LINES)
        for (i in timeline.indices step 20) {
            val state = timeline[i]
            val facing = state.facing
            val p2 = MathUtils.getPointOnCircumference(state.location, 50f, facing)
            GL11.glVertex2f(state.location.x, state.location.y)
            GL11.glVertex2f(p2.x, p2.y)
        }
        GL11.glEnd()
    }
}


class FlightPathPredictorPlugin : BaseEveryFrameCombatPlugin() {

    override fun advance(amount: Float, events: List<InputEventAPI>?) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused) return
        if (engine.customData["NeedsDamagePredictor"] != true) return

        FlightPathPredictorManager.getInstance(engine).advance(engine)
    }

    override fun renderInWorldCoords(viewport: ViewportAPI) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.customData["NeedsDamagePredictor"] != true) return

        FlightPathPredictorManager.getInstance(engine).renderDebug(viewport)
    }
}
package org.starficz.combatai.predictor

import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import org.json.JSONObject
import org.lwjgl.util.vector.Vector2f
import org.scy.armorAtCell
import org.scy.damageAfterArmor
import org.scy.weakestArmorRegion
import java.util.HashMap
import kotlin.math.ceil
import kotlin.math.max

object Constants {
    const val TIME_STEP = 0.05f
    const val PREDICTION_DURATION = 20f
    val TOTAL_FUTURE_STATES = ceil(PREDICTION_DURATION / TIME_STEP).toInt()
    const val ENGINE_COAST_ASSUMPTION = 5f
}

class CombatSnapshot(
    val snapshotTime: Float,
    val ships: List<ShipStateSnapshot>,
    val weapons: HashMap<String, List<WeaponSnapshot>>,
    val missiles: List<MissileSnapshot>,
    val projectiles: List<ProjectileSnapshot>
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
    val flameoutTime: Float,
    val fadeTime: Float,
    val armingTime: Float,
    val isNoCollisionFading: Boolean,
    val isReduceDamageFading: Boolean,
    val doNotAim: Boolean,
    val demParams: DemParams? // <--- ADDED
) : WeaponSnapshot(
    localMountOffset, localRestingAngle, localCurrentAngle, arc, range, turnRate,
    damageType, empPerBurst, damagePerBurst, hitStrength, firingTime,
    cooldownTime, currentlyFiring, timeLeftInState, disabledTime, conservative, ammoLeft
)

class ProjectileSnapshot(
    val location: Vector2f,
    val velocity: Vector2f,
    val owner: Int,
    val damageAmount: Float,
    val empAmount: Float,
    val damageType: DamageType,
    val hitStrength: Float,
    val remainingFlightTime: Float,
    val delay: Float = 0f
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
    val flameoutTime: Float,
    val fadeTime: Float,
    val armingTime: Float,
    val isNoCollisionFading: Boolean,
    val isReduceDamageFading: Boolean,
    val conservative: Boolean,
    val targetId: String?,
    val demParams: DemParams?,
    val initialDemState: DemState,
    val initialDemElapsed: Float
)

enum class DemState { WAIT, TURN_TO_TARGET, SIGNAL, FIRE, DONE }

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

        val sorted = damageInstances
            .filter { it.time in (currentTime - lagBuffer)..endTime }
            .sortedBy { it.time }


        for (damage in sorted) {
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
        val isVentingSafer: Boolean get() = totalVentingDamage * 1.2f < totalNotVentingDamage
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
        startingArmor: Float = ship.armorGrid.weakestArmorRegion()?.let { ship.armorGrid.armorAtCell(it) } ?: 0f
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


class FiringSolution(val isValid: Boolean, val hitChance: Float, val distance: Float)

class ShipTimeline(val startTime: Float) {
    val size = Constants.TOTAL_FUTURE_STATES
    val facings = FloatArray(size)
    val x = FloatArray(size)
    val y = FloatArray(size)

    fun copyFrom(other: ShipTimeline) {
        System.arraycopy(other.facings, 0, this.facings, 0, size)
        System.arraycopy(other.x, 0, this.x, 0, size)
        System.arraycopy(other.y, 0, this.y, 0, size)
    }

    // Optional helper if you still need to pull a Vector2f out for Starsector API calls
    fun location(index: Int): Vector2f = Vector2f(x[index], y[index])
    fun timestamp(index: Int): Float {
        return startTime + (index + 1) * Constants.TIME_STEP
    }
}

class RequestResult(
    val damageTimeline: DamageTimeline,
    val shipTimeline: ShipTimeline
)

class SimulationOutput(
    val enemyTimelines: Map<String, ShipTimeline>,
    val results: Map<RequestKey, RequestResult>
)
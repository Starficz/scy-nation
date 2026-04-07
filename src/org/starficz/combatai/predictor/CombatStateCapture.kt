package org.starficz.combatai.predictor

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import com.fs.starfarer.api.loading.MissileSpecAPI
import com.fs.starfarer.api.util.Misc
import org.json.JSONObject
import org.lazywizard.lazylib.ext.getAngle
import org.lazywizard.lazylib.ext.minus
import org.lazywizard.lazylib.ext.rotate
import org.lwjgl.util.vector.Vector2f
import org.magiclib.kotlin.getDistance
import org.magiclib.kotlin.normalizeAngle
import org.scy.ReflectionUtils.get
import org.scy.copy
import java.util.HashMap
import kotlin.math.ceil

fun CombatEngineAPI.captureCombatState(): CombatSnapshot {
    val weaponSnaps = HashMap<String, List<WeaponSnapshot>>()
    val missileSnaps = mutableListOf<MissileSnapshot>()
    val projectileSnaps = mutableListOf<ProjectileSnapshot>()

    val shipSnaps = this.ships.mapNotNull { ship ->
        if (ship.hullSpec.hullId == "dem_drone") {
            val payloadWeapon = ship.allWeapons.find { it.slot.id == "WS 001" } ?: ship.allWeapons.firstOrNull()

            if (payloadWeapon != null && payloadWeapon.isBeam && (payloadWeapon.isFiring || payloadWeapon.chargeLevel > 0f)) {
                // Determine how much longer the payload is firing
                val remainingFiringTime = if (payloadWeapon.isBurstBeam) payloadWeapon.burstFireTimeRemaining
                else payloadWeapon.derivedStats.burstFireDuration.coerceAtLeast(0.5f)
                val effFiringTime = remainingFiringTime.coerceAtLeast(0.1f)

                val interval = 1f / 3f
                val numIntervals = ceil(effFiringTime / interval).toInt().coerceAtLeast(1)
                val totalTicks = numIntervals + 1
                val actualTickInterval = effFiringTime / numIntervals

                val damagePerTick = (payloadWeapon.derivedStats.dps * effFiringTime) / totalTicks
                val empPerTick = (payloadWeapon.derivedStats.empPerSecond * effFiringTime) / totalTicks
                val hitStrength = payloadWeapon.derivedStats.dps / 2f

                val projSpeed = 10000f // Fast enough to be near-instant but use standard collision checking
                val dir = Misc.getUnitVectorAtDegreeAngle(ship.facing)
                val vel = Vector2f(dir.x * projSpeed, dir.y * projSpeed)

                for (i in 0 until totalTicks) {
                    projectileSnaps.add(
                        ProjectileSnapshot(
                            location = payloadWeapon.location.copy(),
                            velocity = vel,
                            owner = ship.owner,
                            damageAmount = damagePerTick,
                            empAmount = empPerTick,
                            damageType = payloadWeapon.damageType,
                            hitStrength = hitStrength,
                            remainingFlightTime = payloadWeapon.range / projSpeed,
                            delay = i * actualTickInterval
                        )
                    )
                }
            }
            return@mapNotNull null // Throw away the drone body
        }

        if (ship.isShuttlePod || ship.isFighter || ship.isHulk || !ship.isAlive || ship.isPiece) return@mapNotNull null

        weaponSnaps[ship.id] = ship.captureWeaponState()

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

    this.projectiles.forEach { proj ->
        if (proj.isFading || proj.isExpired) return@forEach

        if (proj is MissileAPI) {
            if (proj.isFlare) return@forEach

            var targetId: String? = null
            val ai = proj.unwrappedMissileAI ?: proj.missileAI

            if (ai is GuidedMissileAI) {
                val target = ai.target
                if (target is ShipAPI) targetId = target.id
            }

            val mSpec = proj.spec
            val demParams = parseDemParams(mSpec?.behaviorJSON)
            var capturedDemState = DemState.WAIT
            var capturedDemElapsed = proj.flightTime // If it's waiting, elapsedWaiting is roughly flightTime

            var damageAmount = proj.damageAmount
            var empAmount = proj.empAmount

            if (demParams != null) {
                val payloadId = mSpec?.behaviorJSON?.optString("payloadWeaponId")
                if (!payloadId.isNullOrEmpty()) {
                    try {
                        val payloadSpec = Global.getSettings().getWeaponSpec(payloadId)
                        if (payloadSpec != null) {
                            if (payloadSpec.isBeam) {
                                damageAmount = payloadSpec.derivedStats.dps * demParams.firingTime
                                empAmount = payloadSpec.derivedStats.empPerSecond * demParams.firingTime
                            } else {
                                damageAmount = payloadSpec.derivedStats.burstDamage
                                val ratio = if (proj.damageAmount > 0f) damageAmount / proj.damageAmount else 1f
                                empAmount = proj.empAmount * ratio
                            }
                        }
                    } catch (e: Exception) { }
                }


                // If the AI is DEMScript, it has passed the trigger range
                if (ai != null && ai.javaClass.simpleName == "DEMScript") {
                    try {
                        // Grab the true target out of DEMScript
                        val fireTarget = ai.get(name = "fireTarget") as? ShipAPI
                        if (fireTarget != null) {
                            targetId = fireTarget.id
                        }

                        // Using your ReflectionUtils!
                        val stateObj = ai.get(name = "state") as? Enum<*>
                        if (stateObj != null) {
                            capturedDemState = DemState.valueOf(stateObj.name)
                        }

                        // Read the appropriate elapsed time based on the state
                        val elapsedFieldName = when (capturedDemState) {
                            DemState.SIGNAL -> "elapsedTargeting"
                            DemState.FIRE -> "elapsedFiring"
                            else -> null
                        }

                        if (elapsedFieldName != null) {
                            capturedDemElapsed = (ai.get(name = elapsedFieldName) as? Float) ?: 0f
                        } else if (capturedDemState == DemState.TURN_TO_TARGET) {
                            capturedDemElapsed = 0f
                        }
                    } catch (e: Exception) {
                        // If reflection fails, we fallback to WAIT.
                    }
                }
            }

            missileSnaps.add(
                MissileSnapshot(
                    location = proj.location.copy(),
                    velocity = proj.velocity.copy(),
                    facing = proj.facing,
                    angularVelocity = proj.angularVelocity,
                    owner = proj.owner,
                    damageAmount = damageAmount,
                    empAmount = empAmount,
                    damageType = proj.damageType,
                    isMine = proj.isMine,
                    mineExplosionRange = if (proj.isMine) proj.mineExplosionRange else 0f,
                    acceleration = proj.acceleration,
                    maxSpeed = proj.maxSpeed,
                    maxTurnRate = proj.maxTurnRate,
                    turnAcceleration = proj.turnAcceleration,
                    flightTime = proj.flightTime,
                    maxFlightTime = proj.maxFlightTime, // Active tracking phase
                    flameoutTime = mSpec?.flameoutTime ?: 0f, // Coasting/Fizzle phase
                    fadeTime = mSpec?.fadeTime ?: 0.5f,
                    armingTime = mSpec?.armingTime ?: 0f,
                    isNoCollisionFading = mSpec?.isNoCollisionWhileFading ?: false,
                    isReduceDamageFading = mSpec?.isReduceDamageWhileFading ?: false,
                    conservative = false,
                    targetId = targetId,
                    demParams = demParams,
                    initialDemState = capturedDemState,
                    initialDemElapsed = capturedDemElapsed
                )
            )
        } else {
            val speed = proj.velocity.length()
            if (speed < 1f) return@forEach

            // A projectile might not have a weapon reference if spawned by a script/system
            val weapon = proj.weapon
            val spec = proj.projectileSpec

            val range = weapon?.range ?: spec?.maxRange ?: 1000f
            val fadeRange = weapon?.projectileFadeRange ?: 200f

            val remainingTime = ((range + fadeRange) / speed) - proj.elapsed
            if (remainingTime <= 0f) return@forEach

            projectileSnaps.add(
                ProjectileSnapshot(
                    location = proj.location.copy(),
                    velocity = proj.velocity.copy(),
                    owner = proj.owner,
                    damageAmount = proj.damageAmount,
                    empAmount = proj.empAmount,
                    damageType = proj.damageType,
                    hitStrength = proj.damageAmount, // standard projectile hit strength is its damage
                    remainingFlightTime = remainingTime
                )
            )
        }
    }

    val time = this.getTotalElapsedTime(false)

    return CombatSnapshot(time, shipSnaps, weaponSnaps, missileSnaps, projectileSnaps)
}

data class DemParams(
    val minDelay: Float,
    val triggerDistance: Float,
    val preferredMin: Float,
    val preferredMax: Float,
    val turnRateBoost: Float,
    val targetingLaserArc: Float,
    val targetingTime: Float,
    val firingTime: Float,
    val bombPumped: Boolean,
    val allowedDriftFraction: Float
)

fun getDemValue(json: JSONObject, key: String, default: Float): Float {
    val arr = json.optJSONArray(key)
    if (arr != null && arr.length() >= 2) {
        val min = arr.optDouble(0).toFloat()
        val max = arr.optDouble(1).toFloat()
        return min + (max - min) * 0.5f // Use average for prediction
    }
    return json.optDouble(key, default.toDouble()).toFloat()
}

fun parseDemParams(behavior: JSONObject?): DemParams? {
    // Vanilla DEMs use the CUSTOM behavior type, and must have a triggerDistance
    if (behavior == null || behavior.optString("behavior") != "CUSTOM" || !behavior.has("triggerDistance")) {
        return null
    }

    val triggerDist = getDemValue(behavior, "triggerDistance", 500f)
    return DemParams(
        minDelay = getDemValue(behavior, "minDelayBeforeTriggering", 1f),
        triggerDistance = triggerDist,
        preferredMin = getDemValue(behavior, "preferredMinFireDistance", 0f),
        preferredMax = getDemValue(behavior, "preferredMaxFireDistance", triggerDist),
        turnRateBoost = behavior.optDouble("turnRateBoost", 100.0).toFloat(),
        targetingLaserArc = behavior.optDouble("targetingLaserArc", 10.0).toFloat(),
        targetingTime = getDemValue(behavior, "targetingTime", 1f),
        firingTime = behavior.optDouble("firingTime", 1.25).toFloat(),
        bombPumped = behavior.optBoolean("bombPumped", false),
        allowedDriftFraction = behavior.optDouble("allowedDriftFraction", 0.33).toFloat()
    )
}

private fun ShipAPI.captureWeaponState(): List<WeaponSnapshot> {
    return this.allWeapons.mapNotNull { weapon ->
        val spec = weapon.spec
        if (weapon.slot.isHidden || weapon.isDecorative) return@mapNotNull null
        if (weapon.usesAmmo() && weapon.ammo == 0 && weapon.ammoPerSecond < 0.01f) return@mapNotNull null
        if (weapon.derivedStats.dps < 1f && weapon.derivedStats.empPerSecond < 1f) return@mapNotNull null

        val localMountOffset = (weapon.location - this.location).rotate(-this.facing)
        val localCurrentAngle = weapon.currAngle - this.facing
        val isHardpoint = weapon.slot.isHardpoint
        val effTurnRate = if (isHardpoint) this.mutableStats.maxTurnRate.modifiedValue * 0.5f else weapon.turnRate
        val effArc = if (isHardpoint) 20f else weapon.arc
        val maxSpread = (if (isHardpoint) spec.maxSpread / 2f else spec.maxSpread) * this.mutableStats.maxRecoilMult.modifiedValue
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
                hitStrength = weapon.derivedStats.dps / 2
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
            val demParams = parseDemParams(mSpec.behaviorJSON) // <--- ADDED
            val engineSpec = mSpec.hullSpec?.engineSpec

            var mDamagePerBurst = damagePerBurst
            var mEmpPerBurst = empPerBurst

            if (demParams != null) {
                // Correct predictive burst damage for unfired DEMs
                val payloadId = mSpec.behaviorJSON?.optString("payloadWeaponId")
                if (!payloadId.isNullOrEmpty()) {
                    try {
                        val payloadSpec = Global.getSettings().getWeaponSpec(payloadId)
                        if (payloadSpec != null) {
                            if (payloadSpec.isBeam) {
                                mDamagePerBurst = payloadSpec.derivedStats.dps * demParams.firingTime
                                mEmpPerBurst = payloadSpec.derivedStats.empPerSecond * demParams.firingTime
                            } else {
                                mDamagePerBurst = payloadSpec.derivedStats.burstDamage
                                val ratio = if (damagePerBurst > 0f) mDamagePerBurst / damagePerBurst else 1f
                                mEmpPerBurst = empPerBurst * ratio
                            }
                        }
                    } catch (e: Exception) { }
                }
            }

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
                    range = weapon.range + weapon.projectileFadeRange * 0.8f,
                    turnRate = effTurnRate,
                    damageType = weapon.damageType,
                    empPerBurst = mEmpPerBurst,
                    damagePerBurst = mDamagePerBurst,
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
                    flameoutTime = mSpec.flameoutTime,
                    fadeTime = mSpec.fadeTime,
                    armingTime = mSpec.armingTime,
                    isNoCollisionFading = mSpec.isNoCollisionWhileFading,
                    isReduceDamageFading = mSpec.isReduceDamageWhileFading,
                    doNotAim = true,
                    demParams = demParams
                )
            } else {
                ProjectileWeaponSnapshot(
                    localMountOffset = localMountOffset,
                    localRestingAngle = weapon.arcFacing,
                    localCurrentAngle = localCurrentAngle,
                    arc = effArc,
                    range = weapon.range + weapon.projectileFadeRange * 0.8f,
                    turnRate = effTurnRate,
                    damageType = weapon.damageType,
                    empPerBurst = mEmpPerBurst,
                    damagePerBurst = mDamagePerBurst,
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
            ProjectileWeaponSnapshot(
                localMountOffset = localMountOffset,
                localRestingAngle = weapon.arcFacing,
                localCurrentAngle = localCurrentAngle,
                arc = effArc,
                range = weapon.range + weapon.projectileFadeRange * 0.8f,
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
                projectileSpeed = if (spec is BeamWeaponSpecAPI) spec.beamSpeed
                else weapon.projectileSpeed.coerceAtLeast(100f),
                maxSpread = maxSpread
            )
        }
    }
}

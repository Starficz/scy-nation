package org.scy.combatai.predictor

import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import com.fs.starfarer.api.loading.MissileSpecAPI
import org.lazywizard.lazylib.ext.getAngle
import org.lazywizard.lazylib.ext.minus
import org.lazywizard.lazylib.ext.rotate
import org.lwjgl.util.vector.Vector2f
import org.magiclib.kotlin.getDistance
import org.magiclib.kotlin.normalizeAngle
import org.scy.copy
import java.util.HashMap

fun CombatEngineAPI.captureCombatState(): CombatSnapshot {
    val weaponSnaps = HashMap<String, List<WeaponSnapshot>>()

    val shipSnaps = this.ships.mapNotNull { ship ->
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

    val missileSnaps = this.projectiles
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
    val time = this.getTotalElapsedTime(false)

    return CombatSnapshot(time, shipSnaps, weaponSnaps, missileSnaps)
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

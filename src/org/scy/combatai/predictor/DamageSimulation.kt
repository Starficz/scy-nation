package org.scy.combatai.predictor

import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.ext.getAngle
import org.lazywizard.lazylib.ext.plus
import org.lazywizard.lazylib.ext.rotate
import org.lwjgl.util.vector.Vector2f
import org.magiclib.kotlin.getDistance
import org.magiclib.kotlin.getDistanceSq
import org.magiclib.kotlin.normalizeAngle
import org.scy.copy
import kotlin.math.*

fun CombatSimulation.simulateDamage(targetId: String): RequestResult{
    return DamageSimulation(targetId, this).run()
}

private class DamageSimulation(
    private val targetId: String,
    private val combatSim: CombatSimulation
) {
    private val resultDamage = DamageTimeline(combatSim.snapshot.snapshotTime)
    private val generatedMissiles = mutableListOf<MissileSnapshot>()
    private val ships = combatSim.snapshot.ships.associateBy { it.id }
    private val targetShip = ships[targetId]!!
    private val targetTimeline = combatSim.timelines[targetId]!!

    fun run(): RequestResult {
        for (enemyShip in combatSim.snapshot.ships) {
            if (enemyShip.owner == targetShip.owner || enemyShip.id == targetId) continue

            val shipSilenceTime = if (enemyShip.isOverloaded) enemyShip.overloadTime
                                  else if (enemyShip.isVenting) enemyShip.ventTime
                                  else 0f

            val enemyTimeline = combatSim.timelines[enemyShip.id]!!
            val weapons = combatSim.snapshot.weapons[enemyShip.id] ?: continue

            for (weapon in weapons) {
                simulateWeapon(weapon, enemyShip, enemyTimeline, shipSilenceTime)
            }
        }


        for (missile in combatSim.snapshot.missiles + generatedMissiles) {
            if (missile.owner == targetShip.owner) continue
            if (!missile.isMine && missile.targetId != null && missile.targetId != targetId) continue

            simulateMissile(missile)
        }


        return RequestResult(resultDamage, targetTimeline)
    }

    private fun simulateWeapon(
        weapon: WeaponSnapshot,
        enemyShip: ShipStateSnapshot,
        enemyTimeline: ShipTimeline,
        silenceTime: Float
    ) {
        var currentTime = weapon.disabledTime
        var isFiring = weapon.currentlyFiring
        var timeLeftInState = weapon.timeLeftInState
        var ammo = weapon.ammoLeft

        while (currentTime < Constants.PREDICTION_DURATION) {
            if (isFiring) {
                val burstDuration = min(timeLeftInState, Constants.PREDICTION_DURATION - currentTime)
                if (burstDuration <= 0f) break

                // Fail fast if out of ammo
                if (ammo != null && ammo <= 0) break

                val solution = getFiringSolution(currentTime, weapon, enemyTimeline)

                if (solution.isValid) {
                    if (ammo != null) ammo -= 1

                    val fraction = (burstDuration / weapon.firingTime).coerceIn(0f, 1f)

                    when (weapon) {
                        is MissileWeaponSnapshot -> fireMissile(weapon, fraction, currentTime, enemyShip, enemyTimeline)
                        is ProjectileWeaponSnapshot -> fireProjectile(weapon, fraction, currentTime, solution)
                    }
                }

                // Transition to cooldown state
                currentTime += timeLeftInState
                isFiring = false
                timeLeftInState = weapon.cooldownTime

            } else {
                // Fast-forward through cooldown and apply silence time
                currentTime = maxOf(currentTime + timeLeftInState, silenceTime)

                if (currentTime >= Constants.PREDICTION_DURATION) break

                // Find next valid firing time
                val nextFireTime = findNextFiringTime(currentTime, weapon, enemyTimeline) ?: break

                // Transition to firing state
                currentTime = nextFireTime
                isFiring = true
                timeLeftInState = weapon.firingTime
            }
        }
    }

    private fun getFiringSolution(
        timeElapsed: Float,
        weapon: WeaponSnapshot,
        enemyTimeline: ShipTimeline,
    ): FiringSolution {
        val frameIdx = (timeElapsed / Constants.TIME_STEP).toInt().coerceIn(0, Constants.TOTAL_FUTURE_STATES - 1)

        val targetShipLoc = targetTimeline.location(frameIdx)
        val enemyShipLoc = enemyTimeline.location(frameIdx)
        val enemyShipFacing = enemyTimeline.facings[frameIdx]

        val currWeaponLoc = enemyShipLoc + weapon.localMountOffset.copy().rotate(enemyShipFacing)

        val dist = currWeaponLoc.getDistance(targetShipLoc)
        val maxDist = (weapon.range + targetShip.shieldRadius)

        if (dist > maxDist) return FiringSolution(false, 0f, dist)

        // most missiles can fire at anything in range
        if (weapon is MissileWeaponSnapshot && weapon.doNotAim)
            return FiringSolution(true, 1f, dist)

        val angularRadius = if (dist > 0.1f) {
            val ratio = (targetShip.shieldRadius / dist).coerceIn(-1.0f, 1.0f)
            kotlin.math.asin(ratio) * (180f / PI.toFloat())
        } else {
            180f
        }

        val angleToTarget = currWeaponLoc.getAngle(targetShipLoc)
        val globalRestingAngle = (enemyShipFacing + weapon.localRestingAngle).normalizeAngle()
        val angleDiffResting = abs(MathUtils.getShortestRotation(globalRestingAngle, angleToTarget))

        if (angleDiffResting > weapon.arc * 0.5f) return FiringSolution(false, 0f, dist)

        val effectiveTurnTime = max(0f, timeElapsed - weapon.disabledTime)
        val maxPossibleTurn = weapon.turnRate * effectiveTurnTime
        val startGlobalAngle = enemyShipFacing + weapon.localCurrentAngle
        val requiredTurn = abs(MathUtils.getShortestRotation(startGlobalAngle, angleToTarget))

        if (requiredTurn > maxPossibleTurn + angularRadius/2)
            return FiringSolution(false, 0f, dist)

        if (weapon is MissileWeaponSnapshot)
            return FiringSolution(true, 1f, dist)

        weapon as ProjectileWeaponSnapshot
        val halfSpreadRad = (weapon.maxSpread / 2f) * (PI.toFloat() / 180f)
        val spreadRadius = dist * tan(halfSpreadRad)

        val hitChance = if (spreadRadius <= targetShip.shieldRadius) 1f
                        else (targetShip.shieldRadius / spreadRadius).coerceIn(0f, 1f)

        return FiringSolution(true, hitChance, dist)
    }

    private fun findNextFiringTime(
        startTime: Float,
        snap: WeaponSnapshot,
        enemyTimeline: ShipTimeline
    ): Float? {
        var t = startTime
        while (t < Constants.PREDICTION_DURATION) {
            val solution = getFiringSolution(t, snap, enemyTimeline)
            if (solution.isValid) return t
            t += Constants.TIME_STEP
        }
        return null
    }

    private fun fireMissile(
        weapon: MissileWeaponSnapshot,
        hitFraction: Float,
        fireTime: Float,
        enemyShip: ShipStateSnapshot,
        enemyTimeline: ShipTimeline
    ) {
        val frameIdx = (fireTime / Constants.TIME_STEP).toInt()
            .coerceIn(0, Constants.TOTAL_FUTURE_STATES - 1)

        val currEnemyLoc = enemyTimeline.location(frameIdx)
        val currEnemyFacing = enemyTimeline.facings[frameIdx]
        val currMountLoc = currEnemyLoc + weapon.localMountOffset.copy().rotate(currEnemyFacing)

        generatedMissiles.add(
            MissileSnapshot(
                location = currMountLoc,
                velocity = enemyShip.velocity.copy(),
                facing = currEnemyFacing,
                angularVelocity = enemyShip.angularVelocity,
                owner = enemyShip.owner,
                damageAmount = weapon.damagePerBurst * hitFraction,
                empAmount = weapon.empPerBurst * hitFraction,
                damageType = weapon.damageType,
                isMine = false,
                mineExplosionRange = 0f,
                acceleration = weapon.missileAccel,
                maxSpeed = weapon.missileMaxSpeed,
                maxTurnRate = weapon.missileTurnRate,
                turnAcceleration = weapon.missileTurnAccel,
                flightTime = -fireTime, // Keeps it dormant until firetime
                maxFlightTime = weapon.maxFlightTime,
                conservative = weapon.conservative,
                targetId = targetId
            )
        )
    }

    private fun fireProjectile(
        weapon: ProjectileWeaponSnapshot,
        fraction: Float,
        fireTime: Float,
        solution: FiringSolution
    ) {
        val travelTime = solution.distance / weapon.projectileSpeed
        val hitTimeRelative = fireTime + travelTime
        val hitTimeAbsolute = combatSim.snapshot.snapshotTime + hitTimeRelative

        if (hitTimeRelative <= Constants.PREDICTION_DURATION) {
            resultDamage.addInstance(
                time = hitTimeAbsolute,
                amount = weapon.damagePerBurst * fraction,
                scale = solution.hitChance,
                hitStrength = weapon.hitStrength,
                type = weapon.damageType,
                empAmount = weapon.empPerBurst * fraction,
                conservative = weapon.conservative,
            )
        }
    }

    private fun simulateMissile(
        missile: MissileSnapshot
    ) {
        val loc = Vector2f(missile.location)
        val vel = Vector2f(missile.velocity)
        var facing = missile.facing
        var angVel = missile.angularVelocity

        // Local state for the simulation loop
        var currentFlightTime = missile.flightTime

        for (i in 0 until Constants.TOTAL_FUTURE_STATES) {

            // skip calculation until its not neg
            if (currentFlightTime < 0f) {
                currentFlightTime += Constants.TIME_STEP
                continue
            }
            if (currentFlightTime >= missile.maxFlightTime) break // Missile fizzles out

            val targetLoc = targetTimeline.location(i)
            val targetTime = targetTimeline.timestamp(i)

            if (missile.isMine) {
                // Mines mostly drift along their velocity
                loc.x += vel.x * Constants.TIME_STEP
                loc.y += vel.y * Constants.TIME_STEP

                val distSq = MathUtils.getDistanceSquared(loc, targetLoc)
                val triggerRadius = targetShip.shieldRadius + missile.mineExplosionRange * 1.1f

                if (distSq <= triggerRadius * triggerRadius) {
                    resultDamage.addInstance(
                        targetTime,
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
                    val guideTimeline = combatSim.timelines[missile.targetId]
                    if (guideTimeline != null) guideTargetLoc = guideTimeline.location(i)
                }

                // 1. Angular Kinematics (Tracking)
                if (guideTargetLoc != null) {
                    val angleToTarget = VectorUtils.getAngle(loc, guideTargetLoc)
                    val rotationNeeded = MathUtils.getShortestRotation(facing, angleToTarget)

                    val effTurnAccel = missile.turnAcceleration.coerceAtLeast(1f)
                    val stoppingDist = (angVel * angVel) / (2f * effTurnAccel)
                    val movingTowards = (sign(rotationNeeded) == sign(angVel)) && abs(angVel) > 0.1f

                    if (movingTowards && abs(rotationNeeded) <= stoppingDist) {
                        val change = effTurnAccel * Constants.TIME_STEP
                        if (abs(angVel) <= change) angVel = 0f else angVel -= sign(angVel) * change
                    } else {
                        angVel += sign(rotationNeeded) * effTurnAccel * Constants.TIME_STEP
                    }
                } else {
                    // Lost track: dampen rotation
                    val change = missile.turnAcceleration * Constants.TIME_STEP
                    if (abs(angVel) <= change) angVel = 0f else angVel -= sign(angVel) * change
                }

                angVel = MathUtils.clamp(angVel, -missile.maxTurnRate, missile.maxTurnRate)
                facing = MathUtils.clampAngle(facing + angVel * Constants.TIME_STEP)

                // 2. Forward Kinematics (Acceleration)
                val accelVector = Misc.getUnitVectorAtDegreeAngle(facing)
                accelVector.scale(missile.acceleration * Constants.TIME_STEP)
                vel.translate(accelVector.x, accelVector.y)

                if (vel.length() > missile.maxSpeed) {
                    vel.normalise()
                    vel.scale(missile.maxSpeed)
                }

                loc.translate(vel.x * Constants.TIME_STEP, vel.y * Constants.TIME_STEP)

                // 3. Collision Check
                val missileBufferRadius = 50f
                val hitRadius = targetShip.shieldRadius + missileBufferRadius
                val distSq = loc.getDistanceSq(targetLoc)
                if (distSq <= hitRadius * hitRadius) {
                    resultDamage.addInstance(
                        targetTime,
                        missile.damageAmount, 1f,
                        missile.damageAmount,
                        missile.damageType,
                        missile.empAmount,
                        missile.conservative
                    )
                    break // Missile hit, destroy it from timeline
                }
            }
            currentFlightTime += Constants.TIME_STEP
        }
    }
}
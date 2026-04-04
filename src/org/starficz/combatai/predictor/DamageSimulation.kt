package org.starficz.combatai.predictor

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.Misc
import org.json.JSONObject
import org.lazywizard.lazylib.FastTrig
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

            val enemyTimeline = combatSim.timelines[enemyShip.id] ?: continue
            val weapons = combatSim.snapshot.weapons[enemyShip.id] ?: continue

            for (weapon in weapons) {
                simulateWeapon(weapon, enemyShip, enemyTimeline, shipSilenceTime)
            }
        }

        val missileQueue = ArrayDeque<MissileSnapshot>()

        for (missile in combatSim.snapshot.missiles + generatedMissiles) {
            if (missile.owner == targetShip.owner) continue
            if (!missile.isMine && missile.targetId != null && missile.targetId != targetId) continue

            missileQueue.add(missile)
        }

        while (missileQueue.isNotEmpty()) {
            val missile = missileQueue.removeFirst()
            simulateMissile(missile, missileQueue)
        }

        for (proj in combatSim.snapshot.projectiles) {
            if (proj.owner == targetShip.owner) continue
            simulateInFlightProjectile(proj)
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

        val launchVelocity = if (frameIdx > 0) {
            val prevLoc = enemyTimeline.location(frameIdx - 1)
            Vector2f(
                (currEnemyLoc.x - prevLoc.x) / Constants.TIME_STEP,
                (currEnemyLoc.y - prevLoc.y) / Constants.TIME_STEP
            )
        } else {
            enemyShip.velocity.copy()
        }

        generatedMissiles.add(
            MissileSnapshot(
                location = currMountLoc,
                velocity = launchVelocity,
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
                flightTime = -fireTime,
                maxFlightTime = weapon.maxFlightTime,
                flameoutTime = weapon.flameoutTime,
                fadeTime = weapon.fadeTime,
                armingTime = weapon.armingTime,
                isNoCollisionFading = weapon.isNoCollisionFading,
                isReduceDamageFading = weapon.isReduceDamageFading,
                conservative = weapon.conservative,
                targetId = targetId,
                demParams = weapon.demParams,
                initialDemState = DemState.WAIT,
                initialDemElapsed = 0f
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


    private class MissileState(
        val missile: MissileSnapshot,
        val loc: Vector2f = Vector2f(missile.location),
        val vel: Vector2f = Vector2f(missile.velocity),
        var facing: Float = missile.facing,
        var angVel: Float = missile.angularVelocity,
        var flightTime: Float = missile.flightTime,
        val demParams: DemParams? = missile.demParams,
        var demState: DemState = missile.initialDemState,
        var demStateElapsed: Float = missile.initialDemElapsed,
    )

    private fun simulateMissile(
        missile: MissileSnapshot,
        queue: ArrayDeque<MissileSnapshot>
    ) {
        val state = MissileState(missile)

        // TODO: Uncomment this block when 0.98.5 releases and getMissileSpec() is implemented
//        val behavior = missile.behaviorJSON
//        val isMirv = behavior?.optString("behavior") == "MIRV"
//        val splitRange = behavior?.optDouble("splitRange", 0.0)?.toFloat() ?: 0f
//        val minTimeToSplit = behavior?.optDouble("minTimeToSplit", 0.0)?.toFloat() ?: 0f

        for (i in 0 until Constants.TOTAL_FUTURE_STATES) {
            if (state.flightTime < 0f) {
                state.flightTime += Constants.TIME_STEP
                continue
            }

            // Total lifespan is active flight + coasting/fizzle time
            if (state.flightTime >= missile.maxFlightTime + missile.flameoutTime) break

            val targetLoc = targetTimeline.location(i)
            val targetTime = targetTimeline.timestamp(i)
            val distSq = state.loc.getDistanceSq(targetLoc)

            // TODO: Uncomment this block when 0.98.5 releases and getMissileSpec() is implemented
//            // --- MIRV SPLITTING LOGIC ---
//            if (isMirv && state.flightTime >= minTimeToSplit) {
//                if (distSq <= splitRange * splitRange) {
//                    spawnMirvSubmunitions(state.missile, state.loc, state.vel, state.facing, behavior!!, queue, i)
//                    break // Destroy the parent MIRV
//                }
//            }

            val destroyed = when {
                missile.isMine -> simulateMine(state, targetLoc, targetTime)
                state.demParams != null -> simulateDem(state, targetLoc, targetTime, i)
                else -> simulateNormalMissile(state, targetLoc, targetTime, i)
            }

            if (destroyed) break

            state.flightTime += Constants.TIME_STEP
        }
    }

    private fun simulateMine(state: MissileState, targetLoc: Vector2f, targetTime: Float): Boolean {
        // Mines mostly drift along their velocity
        updateKinematics(state, guideTargetLoc = null, driftOnly = true)

        val triggerRadius = targetShip.shieldRadius + state.missile.mineExplosionRange * 1.1f
        val distSq = state.loc.getDistanceSq(targetLoc)

        if (distSq <= triggerRadius * triggerRadius) {
            recordMissileHit(state.missile, targetTime, 1f)
            return true // Mine exploded
        }
        return false
    }

    private fun simulateDem(
        state: MissileState,
        targetLoc: Vector2f,
        targetTime: Float,
        frameIdx: Int
    ): Boolean {
        val params = state.demParams ?: return true

        // DEM distance logic in vanilla calculates from the missile to the edge of the target's shield/hull
        val distToSurface = state.loc.getDistance(targetLoc) - targetShip.shieldRadius

        if (state.demState == DemState.WAIT &&
            state.flightTime >= params.minDelay &&
            distToSurface < params.triggerDistance) {
            state.demState = DemState.TURN_TO_TARGET
        }

        when (state.demState) {
            DemState.WAIT -> {
                // Prior to triggering, a DEM acts like a completely standard tracking missile
                val destroyed = simulateNormalMissile(state, targetLoc, targetTime, frameIdx)
                return destroyed
            }
            DemState.TURN_TO_TARGET -> {
                updateDemKinematics(state, targetLoc, params)

                val angleToTarget = VectorUtils.getAngle(state.loc, targetLoc)
                val angleDiff = abs(MathUtils.getShortestRotation(state.facing, angleToTarget))

                if (angleDiff <= params.targetingLaserArc / 2f) {
                    state.demState = DemState.SIGNAL
                    state.demStateElapsed = 0f
                }
                return false
            }
            DemState.SIGNAL -> {
                updateDemKinematics(state, targetLoc, params)
                state.demStateElapsed += Constants.TIME_STEP

                if (state.demStateElapsed >= params.targetingTime) {
                    state.demState = DemState.FIRE
                    state.demStateElapsed = 0f
                }
                return false
            }
            DemState.FIRE -> {
                val interval = 1f/3
                val effFiringTime = params.firingTime.coerceAtLeast(0.01f)

                // Divide firing time into intervals to guarantee max 0.5s spacing.
                // Example: 1.0s / 0.5s = 2 intervals.
                val numIntervals = ceil(effFiringTime / interval).toInt().coerceAtLeast(1)

                // Number of ticks is always intervals + 1 to guarantee hitting exactly 0.0s and the end time.
                // Example: 2 intervals = 3 ticks (0.0s, 0.5s, 1.0s).
                val totalTicks = numIntervals + 1

                // The exact duration between ticks
                val actualTickInterval = effFiringTime / numIntervals

                val damagePerTick = state.missile.damageAmount / totalTicks
                val empPerTick = state.missile.empAmount / totalTicks

                // Beam payload hit strength is (DPS / 2)
                // Bomb-pumped particle hit strength is their individual per-tick damage
                val hitStrength = (state.missile.damageAmount / effFiringTime) / 2f


                // Immediately resolve all future ticks and add them to the timeline
                for (i in 0 until totalTicks) {
                    val tickTimeOffset = i * actualTickInterval

                    // Only add ticks that haven't already occurred in the past
                    if (tickTimeOffset >= state.demStateElapsed) {
                        // Time from right now until this specific tick fires
                        val timeUntilTick = tickTimeOffset - state.demStateElapsed
                        val hitTime = targetTime + timeUntilTick

                        if (hitTime <= combatSim.snapshot.snapshotTime + Constants.PREDICTION_DURATION) {
                            resultDamage.addInstance(
                                time = hitTime,
                                amount = damagePerTick,
                                scale = 1f,
                                hitStrength = hitStrength,
                                type = state.missile.damageType,
                                empAmount = empPerTick,
                                conservative = state.missile.conservative
                            )
                        }
                    }
                }

                // We have fully resolved this missile's damage. Delete it from the simulator queue.
                return true
            }
            DemState.DONE -> return true
        }
    }

    private fun simulateNormalMissile(state: MissileState, targetLoc: Vector2f, targetTime: Float, frameIdx: Int): Boolean {
        val missile = state.missile
        val isActiveFlight = state.flightTime <= missile.maxFlightTime
        val timeSinceFizzling = if (!isActiveFlight) state.flightTime - missile.maxFlightTime else 0f

        // Only track if the engine is still active
        var guideTargetLoc: Vector2f? = null
        if (isActiveFlight && missile.targetId != null) {
            val guideTimeline = combatSim.timelines[missile.targetId]
            if (guideTimeline != null) guideTargetLoc = guideTimeline.location(frameIdx)
        }

        // Apply tracking/acceleration if active, otherwise drift on momentum
        updateKinematics(state, guideTargetLoc, driftOnly = !isActiveFlight)

        val missileBufferRadius = 50f
        val hitRadius = targetShip.shieldRadius + missileBufferRadius
        val distSq = state.loc.getDistanceSq(targetLoc)

        // Collision Check
        if (distSq <= hitRadius * hitRadius) {

            if (state.flightTime < missile.armingTime) return false

            var damageScale = 1f

            if (!isActiveFlight) {
                if (timeSinceFizzling > (missile.flameoutTime - missile.fadeTime)) {
                    val fadeMult = ((missile.flameoutTime - timeSinceFizzling) / missile.fadeTime).coerceIn(0f, 1f)

                    if (missile.isNoCollisionFading && fadeMult < 1f) {
                        return true // stop tracking no collision objects
                    } else if (missile.isReduceDamageFading && fadeMult < 1f) {
                        damageScale *= fadeMult
                    }
                }
            }

            recordMissileHit(missile, targetTime, damageScale)
            return true // Missile hit, destroy it from timeline
        }
        return false
    }

    private fun updateDemKinematics(state: MissileState, targetLoc: Vector2f, params: DemParams) {
        val dist = state.loc.getDistance(targetLoc) - targetShip.shieldRadius

        // 1. Station keeping Tracking
        val angleToTarget = VectorUtils.getAngle(state.loc, targetLoc)
        val rotationNeeded = MathUtils.getShortestRotation(state.facing, angleToTarget)

        // DEMs gain a massive turn rate and turn acceleration buff when triggered
        val effTurnRate = state.missile.maxTurnRate + params.turnRateBoost
        val effTurnAccel = state.missile.turnAcceleration + (params.turnRateBoost * 2f)

        val stoppingDist = (state.angVel * state.angVel) / (2f * effTurnAccel)
        val movingTowards = (sign(rotationNeeded) == sign(state.angVel)) && abs(state.angVel) > 0.1f

        if (movingTowards && abs(rotationNeeded) <= stoppingDist) {
            val change = effTurnAccel * Constants.TIME_STEP
            if (abs(state.angVel) <= change) state.angVel = 0f else state.angVel -= sign(state.angVel) * change
        } else {
            state.angVel += sign(rotationNeeded) * effTurnAccel * Constants.TIME_STEP
        }

        state.angVel = MathUtils.clamp(state.angVel, -effTurnRate, effTurnRate)
        state.facing = MathUtils.clampAngle(state.facing + state.angVel * Constants.TIME_STEP)

        // 2. Movement Commands
        var accelDir = 0f
        var isBraking = false

        if (dist < params.preferredMin) {
            accelDir = -1f // Back up (Accelerate backwards)
        } else if (dist > params.preferredMax) {
            accelDir = 1f  // Approach target
        } else if (state.vel.length() > state.missile.maxSpeed * params.allowedDriftFraction) {
            isBraking = true // Neutralize drift velocity
        }

        val accelMag = state.missile.acceleration * Constants.TIME_STEP

        if (accelDir != 0f) {

            val facingRad = Math.toRadians(state.facing.toDouble())
            val accelX = FastTrig.cos(facingRad).toFloat() * accelMag * accelDir
            val accelY = FastTrig.sin(facingRad).toFloat() * accelMag * accelDir
            state.vel.translate(accelX, accelY)
        } else if (isBraking) {
            val velLen = state.vel.length()
            if (velLen > 0) {
                val decel = min(velLen, state.missile.acceleration * Constants.TIME_STEP)
                val velDir = Vector2f(state.vel).apply { normalise() }
                state.vel.translate(-velDir.x * decel, -velDir.y * decel)
            }
        }

        // 3. Apply bounds & velocity to position
        if (state.vel.length() > state.missile.maxSpeed) {
            state.vel.normalise()
            state.vel.scale(state.missile.maxSpeed)
        }

        state.loc.translate(state.vel.x * Constants.TIME_STEP, state.vel.y * Constants.TIME_STEP)
    }

    private fun updateKinematics(state: MissileState, guideTargetLoc: Vector2f?, driftOnly: Boolean) {
        if (driftOnly) {
            state.loc.translate(state.vel.x * Constants.TIME_STEP, state.vel.y * Constants.TIME_STEP)
            return
        }

        val missile = state.missile

        // 1. Angular Kinematics (Tracking)
        if (guideTargetLoc != null) {
            val angleToTarget = VectorUtils.getAngle(state.loc, guideTargetLoc)
            val rotationNeeded = MathUtils.getShortestRotation(state.facing, angleToTarget)

            val effTurnAccel = missile.turnAcceleration.coerceAtLeast(1f)
            val stoppingDist = (state.angVel * state.angVel) / (2f * effTurnAccel)
            val movingTowards = (sign(rotationNeeded) == sign(state.angVel)) && abs(state.angVel) > 0.1f

            if (movingTowards && abs(rotationNeeded) <= stoppingDist) {
                val change = effTurnAccel * Constants.TIME_STEP
                if (abs(state.angVel) <= change) state.angVel = 0f else state.angVel -= sign(state.angVel) * change
            } else {
                state.angVel += sign(rotationNeeded) * effTurnAccel * Constants.TIME_STEP
            }
        } else {
            // Lost track: dampen rotation
            val change = missile.turnAcceleration * Constants.TIME_STEP
            if (abs(state.angVel) <= change) state.angVel = 0f else state.angVel -= sign(state.angVel) * change
        }

        state.angVel = MathUtils.clamp(state.angVel, -missile.maxTurnRate, missile.maxTurnRate)
        state.facing = MathUtils.clampAngle(state.facing + state.angVel * Constants.TIME_STEP)

        // 2. Forward Kinematics (Acceleration)
        val facingRad = Math.toRadians(state.facing.toDouble())
        val accelMag = missile.acceleration * Constants.TIME_STEP
        val accelX = FastTrig.cos(facingRad).toFloat() * accelMag
        val accelY = FastTrig.sin(facingRad).toFloat() * accelMag
        state.vel.translate(accelX, accelY)

        if (state.vel.length() > missile.maxSpeed) {
            state.vel.normalise()
            state.vel.scale(missile.maxSpeed)
        }

        state.loc.translate(state.vel.x * Constants.TIME_STEP, state.vel.y * Constants.TIME_STEP)
    }

    private fun recordMissileHit(missile: MissileSnapshot, targetTime: Float, damageScale: Float) {
        resultDamage.addInstance(
            time = targetTime,
            amount = missile.damageAmount * damageScale,
            scale = 1f,
            hitStrength = missile.damageAmount * damageScale,
            type = missile.damageType,
            empAmount = missile.empAmount * damageScale,
            conservative = missile.conservative
        )
    }

//    private fun spawnMirvSubmunitions(
//        parent: MissileSnapshot,
//        loc: Vector2f,
//        vel: Vector2f,
//        facing: Float,
//        behavior: JSONObject,
//        queue: ArrayDeque<MissileSnapshot>,
//        splitFrame: Int
//    ) {
//        val numShots = behavior.optInt("numShots", 1)
//        val projId = behavior.optString("projectileSpec")
//
//        // Utilize the new API to grab the submunition stats!
//        val subSpec = Global.getSettings().getMissileSpec(projId)
//        val engineSpec = subSpec?.hullSpec?.engineSpec
//
//        val baseDamage = behavior.optDouble("damage", parent.damageAmount.toDouble()).toFloat()
//        val baseEmp = behavior.optDouble("emp", parent.empAmount.toDouble()).toFloat()
//
//        val maxSpeed = engineSpec?.maxSpeed ?: parent.maxSpeed
//        val accel = engineSpec?.acceleration ?: parent.acceleration
//        val turnRate = engineSpec?.maxTurnRate ?: parent.maxTurnRate
//        val turnAccel = engineSpec?.turnAcceleration ?: parent.turnAcceleration
//        val maxFlightTime = subSpec?.maxFlightTime ?: parent.maxFlightTime
//
//        // Attempt to parse the damage type or default to parent
//        val dmgTypeStr = behavior.optString("damageType", parent.damageType.name)
//        val damageType = try { DamageType.valueOf(dmgTypeStr) } catch (e: Exception) { parent.damageType }
//
//        // Calculate spread angles
//        val arc = behavior.optDouble("arc", 0.0).toFloat()
//        val spreadSpeed = behavior.optDouble("spreadSpeed", 0.0).toFloat()
//        val angleOffset = if (numShots > 1) arc / (numShots - 1) else 0f
//        val startAngle = facing - (arc / 2f)
//
//        // The delay ensures the submunitions start moving on the exact frame they were spawned
//        val newFlightTime = -(splitFrame * Constants.TIME_STEP)
//
//        for (i in 0 until numShots) {
//            val subFacing = if (numShots <= 1) facing else startAngle + (i * angleOffset)
//
//            // Apply spread velocity to the parent's base velocity
//            val spreadVel = Misc.getUnitVectorAtDegreeAngle(subFacing)
//            spreadVel.scale(spreadSpeed)
//            val newVel = Vector2f(vel.x + spreadVel.x, vel.y + spreadVel.y)
//
//            queue.add(
//                MissileSnapshot(
//                    location = Vector2f(loc),
//                    velocity = newVel,
//                    facing = subFacing,
//                    angularVelocity = 0f, // typically starts at 0 right after detaching
//                    owner = parent.owner,
//                    damageAmount = baseDamage,
//                    empAmount = baseEmp,
//                    damageType = damageType,
//                    isMine = false,
//                    mineExplosionRange = 0f,
//                    acceleration = accel,
//                    maxSpeed = maxSpeed,
//                    maxTurnRate = turnRate,
//                    turnAcceleration = turnAccel,
//                    flightTime = newFlightTime,
//                    maxFlightTime = maxFlightTime,
//                    conservative = parent.conservative,
//                    targetId = parent.targetId,
//                    behaviorJSON = null,
//                )
//            )
//        }
//    }

    private fun simulateInFlightProjectile(proj: ProjectileSnapshot) {
        val maxReach = (proj.velocity.length() + targetShip.maxSpeed) * proj.remainingFlightTime + targetShip.shieldRadius
        if (proj.location.getDistanceSq(targetShip.location) > maxReach * maxReach) return

        val rSq = (targetShip.shieldRadius + 10f) * (targetShip.shieldRadius + 10f)
        val timeMult = 1f / Constants.TIME_STEP

        for (i in 0 until Constants.TOTAL_FUTURE_STATES) {
            val tSim = i * Constants.TIME_STEP

            // Wait until the projectile actually activates
            if (tSim < proj.delay) continue

            val tFlight = tSim - proj.delay
            if (tFlight > proj.remainingFlightTime) break

            val sStart = if (i == 0) targetShip.location else targetTimeline.location(i - 1)
            val sEnd = targetTimeline.location(i)

            val pStart = Vector2f(
                proj.location.x + proj.velocity.x * tFlight,
                proj.location.y + proj.velocity.y * tFlight
            )

            // 1. Calculate relative vectors
            val vRelX = proj.velocity.x - (sEnd.x - sStart.x) * timeMult
            val vRelY = proj.velocity.y - (sEnd.y - sStart.y) * timeMult

            val dStartX = pStart.x - sStart.x
            val dStartY = pStart.y - sStart.y

            // 2. Are we already inside the radius?
            val c = (dStartX * dStartX + dStartY * dStartY) - rSq
            if (c <= 0f) {
                recordProjectileHit(proj, combatSim.snapshot.snapshotTime + tSim)
                return
            }

            // 3. EARLY EXIT
            val dotProduct = dStartX * vRelX + dStartY * vRelY
            if (dotProduct > 0f) continue

            // 4. Simplified Quadratic
            val a = vRelX * vRelX + vRelY * vRelY
            if (a < 0.0001f) continue

            val discriminant = (dotProduct * dotProduct) - (a * c)
            if (discriminant >= 0f) {
                val tau = (-dotProduct - sqrt(discriminant)) / a

                if (tau in 0f..Constants.TIME_STEP && (tFlight + tau) <= proj.remainingFlightTime) {
                    recordProjectileHit(proj, combatSim.snapshot.snapshotTime + tSim + tau)
                    return
                }
            }
        }
    }

    private fun recordProjectileHit(proj: ProjectileSnapshot, targetTime: Float) {
        resultDamage.addInstance(
            time = targetTime,
            amount = proj.damageAmount,
            scale = 1f,
            hitStrength = proj.hitStrength,
            type = proj.damageType,
            empAmount = proj.empAmount,
            conservative = false
        )
    }
}
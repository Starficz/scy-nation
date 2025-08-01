package org.scy

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType
import com.fs.starfarer.api.loading.MissileSpecAPI
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.combat.AIUtils
import org.lazywizard.lazylib.combat.CombatUtils
import org.lazywizard.lazylib.ext.json.optFloat
import org.lwjgl.util.vector.Vector2f
import org.magiclib.kotlin.getTargetingRadius
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object StarficzAIUtilsV2 {
    const val SINGLE_FRAME: Float = 1f / 60
    data class FutureHit(
        val timeToHit: Float,
        val rangeLeftAfterHit: Float,
        val angleOfHit: Float,
        val damageType: DamageType,
        val hitStrength: Float,
        val damage: Float,
        val empDamage: Float,
        val softFlux: Boolean,
        val enemyID: String
    )

    fun applyROFMulti(baseTime: Float, weapon: WeaponAPI?, stats: MutableShipStatsAPI): Float {
        if (weapon == null) return baseTime

        return when (weapon.type) {
            WeaponType.BALLISTIC -> baseTime / stats.ballisticRoFMult.modifiedValue
            WeaponType.ENERGY -> baseTime / stats.energyRoFMult.modifiedValue
            WeaponType.MISSILE -> baseTime / stats.missileRoFMult.modifiedValue
            else -> baseTime
        }
    }

    fun calculateDamageToShip(damage: Float, target: ShipAPI): Float {
        return when (target.hullSize!!) {
            ShipAPI.HullSize.CAPITAL_SHIP -> damage * target.mutableStats.damageToCapital.modifiedValue
            ShipAPI.HullSize.CRUISER -> damage * target.mutableStats.damageToCruisers.modifiedValue
            ShipAPI.HullSize.DESTROYER -> damage * target.mutableStats.damageToDestroyers.modifiedValue
            ShipAPI.HullSize.FRIGATE -> damage * target.mutableStats.damageToFrigates.modifiedValue
            ShipAPI.HullSize.FIGHTER -> damage * target.mutableStats.damageToFighters.modifiedValue
            ShipAPI.HullSize.DEFAULT -> damage
        }
    }

    fun incomingProjectileHits(ship: ShipAPI, testPoint: Vector2f, testVelocity: Vector2f, maxRange: Float = 2500f): List<FutureHit> {
        val engine = Global.getCombatEngine()
        val currentTime = engine.getTotalElapsedTime(false)
        val futureHits = mutableListOf<FutureHit>()

        val threats = engine.allObjectGrid.getCheckIterator(testPoint, maxRange * 2, maxRange * 2)
            .asSequence()
            .filterIsInstance<DamagingProjectileAPI>()
            .filterNot {
                it.source == ship && !(it is MissileAPI && it.isMine) || // filter out things you fired, unless its a mine
                it.owner == ship.owner && (it.collisionClass in setOf(
                    CollisionClass.PROJECTILE_NO_FF, // filter out no FF things
                    CollisionClass.HITS_SHIPS_ONLY_NO_FF, CollisionClass.MISSILE_NO_FF, CollisionClass.RAY_FIGHTER
                ))
            }

        for (threat in threats) {
            val shipRadius = ship.getTargetingRadius(threat.location, false)
            when (threat) {
                is MissileAPI -> { // handle Missiles (holy shit we need so much info)
                    processMissiles(
                        futureHits,
                        currentTime,
                        ship,
                        testPoint,
                        testVelocity,
                        shipRadius,
                        threat.spec,
                        threat.isFlare,
                        threat.isMine,
                        threat.isGuided,
                        threat.maxFlightTime,
                        threat.flightTime,
                        threat.isArmedWhileFizzling,
                        threat.isNoFlameoutOnFizzling,
                        threat.maxSpeed,
                        threat.maxTurnRate,
                        threat.facing,
                        threat.location,
                        threat.velocity,
                        threat.acceleration,
                        threat.damageAmount,
                        threat.damageType,
                        threat.empAmount,
                        threat.damage.isSoftFlux,
                        threat.source.id,
                        threat.isMirv,
                        threat.mirvNumWarheads,
                        threat.mirvWarheadDamage,
                        threat.mirvWarheadEMPDamage,
                        threat.untilMineExplosion,
                        threat.mineExplosionRange
                    )
                }

                else -> { // Non Guided projectiles (including missiles that have stopped tracking)
                    if (threat.weapon == null) continue
                    val range = threat.weapon.range
                    val maxDistance = range - threat.elapsed * threat.moveSpeed

                    // circle-line collision checks for unguided projectiles,

                    // subtract ship velocity to incorporate relative velocity
                    val relativeVelocity = threat.velocity - testVelocity
                    val futureProjectileLocation =
                        threat.location + VectorUtils.resize(Vector2f(relativeVelocity), maxDistance)
                    val hitDistance = MathUtils.getDistance(testPoint, threat.location) - shipRadius
                    var travelDistance = 0f
                    var intersectAngle = 0f
                    var hit = false
                    if (hitDistance < 0) {
                        intersectAngle = VectorUtils.getAngle(ship.location, threat.location)
                        hit = true
                    } else {
                        val collision = StarficzAIUtils.intersectCircle(
                            threat.location,
                            futureProjectileLocation,
                            testPoint,
                            ship.shieldRadiusEvenIfNoShield
                        )
                        if (collision != null) {
                            intersectAngle = collision.second
                            travelDistance = collision.third
                            hit = true
                        }
                    }
                    if (hit && threat.damageAmount > 0) {
                        val travelTime = travelDistance / relativeVelocity.length()
                        val damage = calculateDamageToShip(threat.damageAmount, ship)
                        futureHits.add(
                            FutureHit(
                                timeToHit = travelTime + currentTime,
                                rangeLeftAfterHit = maxDistance - travelDistance,
                                angleOfHit = intersectAngle,
                                damageType = threat.damageType,
                                hitStrength = damage,
                                damage = damage,
                                empDamage = threat.empAmount,
                                softFlux = threat.damage.isSoftFlux,
                                enemyID = threat.source.id,
                            )
                        )
                    }
                }
            }
        }

        return futureHits
    }

    fun generatePredictedWeaponHits(ship: ShipAPI, testPoint: Vector2f, testVelocity: Vector2f, maxRange: Float = 2500f, maxTime: Float = 20f): MutableList<FutureHit> {
        val engine = Global.getCombatEngine()
        val currentTime = engine.getTotalElapsedTime(false)
        val futureHits = mutableListOf<FutureHit>()
        val nearbyObjects = engine.allObjectGrid.getCheckIterator(testPoint, maxRange * 2, maxRange * 2)
            .asSequence().filterIsInstance<CombatEntityAPI>().toList()
        val nearbyOcclusions = nearbyObjects.filter { it is ShipAPI || it is CombatAsteroidAPI }
        val nearbyEnemies = nearbyOcclusions.filterIsInstance<ShipAPI>().filter {
            (it.owner == 1-ship.owner) && CombatUtils.isVisibleToSide(it, ship.owner)
        }

        for (enemy in nearbyEnemies) {
            val ventOverloadTime = max(enemy.fluxTracker.overloadTimeRemaining,
                if (enemy.fluxTracker.isVenting) enemy.fluxTracker.timeToVent else 0f
            )

            // ignore ship if shooting through other ship
            var occluded = false
            for (occlusion in nearbyOcclusions) {
                if (enemy === occlusion) continue
                if (ship === occlusion) continue
                if (occlusion is ShipAPI){ // ignore if occlusion is part of the same modular ship
                    if (occlusion in enemy.childModulesCopy) continue
                    if (occlusion === enemy.parentStation) continue
                    if (enemy.parentStation?.childModulesCopy?.contains(occlusion) == true) continue
                    if (occlusion.hullSize == ShipAPI.HullSize.FIGHTER) continue // ignore if fighter
                }

                val closestPoint = MathUtils.getNearestPointOnLine(occlusion.location, testPoint, enemy.location)
                // bias the size down 50 units, hack to compensate for the fact that this assumes everything is static
                val targetingRadius = occlusion.getTargetingRadius(closestPoint, occlusion.shield != null && occlusion.shield.isOn) - 50f
                if (MathUtils.getDistance(closestPoint, occlusion.location) < targetingRadius) {
                    occluded = true
                    break
                }
            }
            if (occluded) continue

            // deal with systems
            if (enemy.system?.specAPI?.damageType != null && enemy.system?.specAPI?.damage?.let { it > 0 } == true) {
                val systemSpec = enemy.system.specAPI
                // if weapon out of range, add time for ship to reach you
                var distanceFromWeapon = MathUtils.getDistance(enemy.location, testPoint)
                val targetingRadius = ship.getTargetingRadius(enemy.location, false)
                var outOfRangeTime = ventOverloadTime
                val actualRange = systemSpec.getRange(enemy.mutableStats) + 25f

                if (distanceFromWeapon > (actualRange + targetingRadius)) {
                    val dirVec = VectorUtils.getDirectionalVector(enemy.location, testPoint)
                    val closingSpeed = enemy.maxSpeed - Vector2f.dot(testVelocity, dirVec)

                    outOfRangeTime = if (closingSpeed < 0) maxTime + 1f
                                     else (distanceFromWeapon - (actualRange + targetingRadius)) / closingSpeed
                    distanceFromWeapon = actualRange
                }
                var predictionTime = max(outOfRangeTime, enemy.system.cooldownRemaining)
                val angleOfHit = VectorUtils.getAngle(testPoint, enemy.location)
                while (predictionTime < maxTime) {
                    futureHits.add(
                        FutureHit(
                            timeToHit = predictionTime + currentTime,
                            rangeLeftAfterHit = actualRange - distanceFromWeapon,
                            angleOfHit = angleOfHit,
                            damageType = systemSpec.damageType,
                            hitStrength = systemSpec.damage,
                            damage = systemSpec.damage,
                            empDamage = systemSpec.empDamage,
                            softFlux = false, // assume false for worst case
                            enemyID = enemy.id,
                        )
                    )
                    predictionTime += 1f // assume 1 hit/s best we can really do for systems with arbitrary code
                }
            }

            //now we can deal with weapons
            for (weapon: WeaponAPI in enemy.allWeapons){
                if(weapon.isDecorative) continue

                // TODO: actually calculate regenerating ammo correctly
                if (weapon.usesAmmo()) { // if out of ammo, return
                    val ammoTracker = weapon.ammoTracker
                    val reloadTimeLeft = (1-ammoTracker.reloadProgress) * ammoTracker.reloadSize/ammoTracker.ammoPerSecond
                    if (ammoTracker.ammo == 0 && reloadTimeLeft > 10f) continue
                }

                // pre calc angle and true damage, used many times later on
                val shipToWeaponAngle = VectorUtils.getAngle(testPoint, weapon.location)
                val trueSingleInstanceDamage = calculateDamageToShip(weapon.damage.damage, ship)
                val trueSingleInstanceEMPDamage = calculateDamageToShip(max(weapon.derivedStats.empPerShot, weapon.derivedStats.empPerSecond), ship)
                if (trueSingleInstanceDamage <= 0 && trueSingleInstanceEMPDamage <= 0) continue
                var linkedBarrels = Math.round((weapon.derivedStats.damagePerShot / weapon.damage.damage)).toFloat()
                if (linkedBarrels == 0f) linkedBarrels = weapon.spec.turretFireOffsets.size.toFloat() // beam fallback

                // Calculate all the delays
                // if weapon out of range, add time for ship to reach you
                var distanceFromWeapon = MathUtils.getDistance(weapon.location, testPoint)
                val shipRadius = ship.getTargetingRadius(enemy.location, false)
                var outOfRangeTime = 0f
                var inRangeTime = Float.POSITIVE_INFINITY
                val actualRange = weapon.range + weapon.projectileFadeRange * 0.8f + 25f

                val closingSpeed = calculateClosingOrSeparationSpeed(enemy.location, enemy.velocity, testPoint, testVelocity)

                if (distanceFromWeapon > (actualRange + shipRadius)) {
                    if(closingSpeed < 0){
                        outOfRangeTime = (distanceFromWeapon - (actualRange + shipRadius)) / -closingSpeed
                        if (outOfRangeTime > maxTime) continue
                        distanceFromWeapon = actualRange
                    } else continue
                }
                else if(distanceFromWeapon < (actualRange + shipRadius) && closingSpeed > 0){
                    inRangeTime = ((actualRange + shipRadius) - distanceFromWeapon) / closingSpeed
                }

                val rangeLeft = actualRange - distanceFromWeapon


                // calculate disable time if applicable
                val disabledTime = if (weapon.isDisabled) weapon.disabledDuration else 0f

                // if not guided, calculate aim time if in arc, otherwise add time for ships to rotate (overestimates by allowing all weapons to hit, but better to over then underestimate)
                var aimTime = 0f
                val guidedWeapon = (weapon.hasAIHint(WeaponAPI.AIHints.DO_NOT_AIM) || weapon.hasAIHint(WeaponAPI.AIHints.GUIDED_POOR))
                if (!guidedWeapon) {
                    val targetPoint = AIUtils.getBestInterceptPoint(
                        weapon.location,
                        min(weapon.projectileSpeed, 1000000f),
                        testPoint,
                        testVelocity
                    ) ?: continue

                    // Total rotation needed for the weapon to face the target in world space
                    val rotationNeeded = MathUtils.getShortestRotation(weapon.currAngle, 180 + shipToWeaponAngle)
                    val absRotationNeeded = abs(rotationNeeded)

                    // Angle from the target to the nearest edge of the weapon's firing arc.
                    // This is how much rotation the SHIP must provide after the weapon reaches its limit.
                    val arcDistance = weapon.distanceFromArc(targetPoint)

                    // Angle the WEAPON can travel before it hits its own arc limit.
                    val angleToLimit = absRotationNeeded - arcDistance

                    // A check to see if the turret will hit its arc limit before the target is acquired.
                    // This is true if the total rotation needed is greater than what the turret can do on its own.
                    val hitLimit = absRotationNeeded > angleToLimit

                    // Handle potential division by zero if turn rates are 0
                    if ((weapon.turnRate + enemy.maxTurnRate) <= 0f) {
                        aimTime = Float.POSITIVE_INFINITY // Can't turn to aim
                    } else if (!hitLimit) {
                        // CORRECT: Limit is not hit. Time is total rotation divided by combined turn speed.
                        aimTime = absRotationNeeded / (weapon.turnRate + enemy.maxTurnRate)
                    } else {
                        // CORRECT: Limit is hit. This models an optimal, simultaneous turn.
                        // The previous calculation was wrong because it modeled a slow, sequential turn.
                        if (enemy.maxTurnRate <= 0f) {
                            aimTime = Float.POSITIVE_INFINITY // Weapon is at limit and ship can't turn
                        } else {
                            // This formula correctly calculates the time based on an optimal maneuver.
                            // It's the time it takes the ship to cover the angular distance that lies
                            // beyond the weapon's hardware limits.
                            aimTime = (absRotationNeeded - angleToLimit) / enemy.maxTurnRate
                        }
                    }
                }

                val delayUntilOnTarget = max(disabledTime + max(ventOverloadTime, aimTime), outOfRangeTime)
                val projectileSpec = weapon.spec.projectileSpec
                val travelTime = if(projectileSpec is MissileSpecAPI){ // calculate missile travel time
                    if(guidedWeapon) {
                        guidedMissileTravelTime(
                            missileSpec = projectileSpec,
                            maxSpeed = projectileSpec.hullSpec.engineSpec.maxSpeed,
                            maxTurnRate = projectileSpec.hullSpec.engineSpec.maxTurnRate,
                            missileStartingAngle = weapon.currAngle,
                            missileStartingLocation = weapon.location,
                            targetLocation = testPoint,
                            targetVelocity = testVelocity,
                            targetRadius = shipRadius,
                            maxTime = projectileSpec.maxFlightTime
                        )
                    } else{
                        calculate1DInterceptTime(
                            distanceFromWeapon,
                            -calculateClosingOrSeparationSpeed(enemy.location, enemy.velocity, testPoint, testVelocity),
                            projectileSpec.launchSpeed,
                            projectileSpec.hullSpec.engineSpec.maxSpeed,
                            projectileSpec.hullSpec.engineSpec.acceleration,
                        )
                    }
                }
                else distanceFromWeapon / min(weapon.projectileSpeed, 1000000f) // everything else is really easy

                // finally the pre calcs are done and we can handle the actual future hits

                // normal beams
                if (weapon.isBeam && !weapon.isBurstBeam) {
                    var currentPredictionTime = delayUntilOnTarget
                    while (currentPredictionTime < maxTime - travelTime && currentPredictionTime < inRangeTime) {
                        futureHits.add(
                            FutureHit(
                                timeToHit = currentPredictionTime + travelTime + currentTime,
                                rangeLeftAfterHit = rangeLeft,
                                angleOfHit = shipToWeaponAngle,
                                damageType = weapon.damageType,
                                hitStrength = trueSingleInstanceDamage / (linkedBarrels * 2),
                                damage = trueSingleInstanceDamage / 10,
                                empDamage = trueSingleInstanceEMPDamage / 10,
                                softFlux = weapon.damage.isSoftFlux,
                                enemyID =  enemy.id,
                            )
                        )
                        currentPredictionTime += 0.1f
                    }
                }
                // burst beams
                else if (weapon.isBurstBeam) {
                    // derive the actual times spent in each phase from all the whack ass API calls
                    var chargeupTime = 0f
                    var activeTime = 0f
                    var chargedownTime = 0f
                    var cooldownTime = 0f
                    if (!weapon.isFiring) { // weapon is in cooldown/idle
                        cooldownTime = weapon.cooldownRemaining
                    } else if (weapon.cooldownRemaining > 0) { // weapon is in chargedown, chargedown and cooldown overlap by Starsector's standards (Blame Alex)
                        cooldownTime = weapon.cooldown - weapon.spec.beamChargedownTime
                        chargedownTime = weapon.cooldownRemaining - cooldownTime
                    } else if (weapon.burstFireTimeRemaining < weapon.spec.burstDuration) { // weapon is in active
                        activeTime = weapon.burstFireTimeRemaining
                        chargedownTime = weapon.spec.beamChargedownTime
                        cooldownTime = weapon.cooldown - chargedownTime
                    } else if (weapon.burstFireTimeRemaining > weapon.spec.burstDuration) {
                        activeTime = weapon.spec.burstDuration
                        chargeupTime = weapon.burstFireTimeRemaining - activeTime
                        chargedownTime = weapon.spec.beamChargedownTime
                        cooldownTime = weapon.cooldown - chargedownTime
                    }

                    // apply ROF multis
                    //TODO: check if ROF effects active time of burst beams (i 95% sure they do)
                    chargeupTime = applyROFMulti(chargeupTime, weapon, enemy.mutableStats)
                    chargedownTime = applyROFMulti(chargedownTime, weapon, enemy.mutableStats)
                    cooldownTime = applyROFMulti(cooldownTime, weapon, enemy.mutableStats)

                    var currentPredictionTime = disabledTime
                    while (currentPredictionTime < maxTime - travelTime && currentPredictionTime < inRangeTime) {
                        val lastCurrentPredictionTime = currentPredictionTime
                        while (chargeupTime > 0 && currentPredictionTime < maxTime) { // resolve chargeup damage
                            if (currentPredictionTime > delayUntilOnTarget) {
                                futureHits.add(
                                    FutureHit(
                                        timeToHit = currentPredictionTime + travelTime + currentTime,
                                        rangeLeftAfterHit = rangeLeft,
                                        angleOfHit = shipToWeaponAngle,
                                        damageType = weapon.damageType,
                                        hitStrength = trueSingleInstanceDamage / (linkedBarrels * 3),
                                        damage = trueSingleInstanceDamage / 30,
                                        empDamage = trueSingleInstanceEMPDamage / 30,
                                        softFlux = weapon.damage.isSoftFlux,
                                        enemyID =  enemy.id,
                                    )
                                )
                            }
                            chargeupTime -= 0.1f
                            currentPredictionTime += 0.1f
                        }

                        activeTime += chargeupTime // carry over borrowed time
                        while (activeTime > 0 && currentPredictionTime < maxTime - travelTime && currentPredictionTime < inRangeTime) { // resolve active damage
                            if (currentPredictionTime > delayUntilOnTarget) {
                                futureHits.add(
                                    FutureHit(
                                        timeToHit = currentPredictionTime + travelTime + currentTime,
                                        rangeLeftAfterHit = rangeLeft,
                                        angleOfHit = shipToWeaponAngle,
                                        damageType = weapon.damageType,
                                        hitStrength = trueSingleInstanceDamage / linkedBarrels,
                                        damage = trueSingleInstanceDamage / 10,
                                        empDamage = trueSingleInstanceEMPDamage / 10,
                                        softFlux = weapon.damage.isSoftFlux,
                                        enemyID =  enemy.id,
                                    )
                                )
                            }
                            activeTime -= 0.1f
                            currentPredictionTime += 0.1f
                        }

                        chargedownTime += activeTime // carry over borrowed time
                        while (chargedownTime > 0 && currentPredictionTime < maxTime - travelTime && currentPredictionTime < inRangeTime) { // resolve chargedown damage
                            if (currentPredictionTime > delayUntilOnTarget) {
                                futureHits.add(
                                    FutureHit(
                                        timeToHit = currentPredictionTime + travelTime + currentTime,
                                        rangeLeftAfterHit = rangeLeft,
                                        angleOfHit = shipToWeaponAngle,
                                        damageType = weapon.damageType,
                                        hitStrength = trueSingleInstanceDamage / (linkedBarrels * 3),
                                        damage = trueSingleInstanceDamage / 30,
                                        empDamage = trueSingleInstanceEMPDamage / 30,
                                        softFlux = weapon.damage.isSoftFlux,
                                        enemyID =  enemy.id,
                                    )
                                )
                            }
                            chargedownTime -= 0.1f
                            currentPredictionTime += 0.1f
                        }

                        cooldownTime += chargedownTime // carry over borrowed time
                        currentPredictionTime += cooldownTime
                        currentPredictionTime = max(currentPredictionTime, delayUntilOnTarget) // wait for weapon to finish aiming if not yet aimed

                        currentPredictionTime += if (currentPredictionTime <= lastCurrentPredictionTime + SINGLE_FRAME) SINGLE_FRAME else 0f // make sure to not get stuck in an infinite
                        // reset times
                        chargeupTime = applyROFMulti(weapon.spec.beamChargeupTime, weapon, enemy.mutableStats)
                        activeTime = weapon.spec.burstDuration //TODO: check if ROF effects active time of burst beams
                        chargedownTime = applyROFMulti(weapon.spec.beamChargedownTime, weapon, enemy.mutableStats)
                        cooldownTime = applyROFMulti(weapon.cooldown - chargedownTime, weapon, enemy.mutableStats)
                    }
                }
                else if (weapon.spec.burstSize == 1) { // non burst projectile weapons
                    // derive the actual times spent in each phase from all the whack ass API calls
                    var chargeupTime = 0f
                    var cooldownTime = 0f
                    if (weapon.cooldownRemaining == 0f && weapon.isFiring) {
                        chargeupTime = (1f - weapon.chargeLevel) * weapon.spec.chargeTime
                        cooldownTime = weapon.cooldown
                    } else if (weapon.isFiring) {
                        cooldownTime = weapon.cooldownRemaining
                    }

                    // apply ROF multis
                    chargeupTime = applyROFMulti(chargeupTime, weapon, enemy.mutableStats)
                    cooldownTime = applyROFMulti(cooldownTime, weapon, enemy.mutableStats)

                    var currentPredictionTime = disabledTime
                    while (currentPredictionTime < maxTime - travelTime && currentPredictionTime < inRangeTime) {
                        currentPredictionTime += max(0f, chargeupTime)
                        if (currentPredictionTime > delayUntilOnTarget) {
                            futureHits.add(
                                FutureHit(
                                    timeToHit = currentPredictionTime + travelTime + currentTime,
                                    rangeLeftAfterHit = rangeLeft,
                                    angleOfHit = shipToWeaponAngle,
                                    damageType = weapon.damageType,
                                    hitStrength = trueSingleInstanceDamage / linkedBarrels,
                                    damage = trueSingleInstanceDamage * linkedBarrels,
                                    empDamage = trueSingleInstanceEMPDamage * linkedBarrels,
                                    softFlux = weapon.damage.isSoftFlux,
                                    enemyID =  enemy.id,
                                )
                            )
                        }
                        currentPredictionTime += max(0f, cooldownTime)
                        currentPredictionTime = max(currentPredictionTime, delayUntilOnTarget) // wait for weapon to finish aiming if not yet aimed

                        currentPredictionTime += if ((chargeupTime + cooldownTime) <= SINGLE_FRAME) SINGLE_FRAME else 0f // make sure to not get stuck in an infinite

                        // reset chargeup/cooldown to idle weapon stats
                        chargeupTime = applyROFMulti(weapon.spec.chargeTime, weapon, enemy.mutableStats)
                        cooldownTime = applyROFMulti(weapon.cooldown, weapon, enemy.mutableStats)
                    }
                }
                else { // burst projectile weapons
                    // derive the actual times spent in each phase from all the whack ass API calls
                    var chargeupTime = 0f
                    var burstTime = 0f
                    var cooldownTime = 0f
                    var burstDelay = (weapon.spec as ProjectileWeaponSpecAPI).burstDelay
                    if (weapon.cooldownRemaining == 0f && !weapon.isInBurst && weapon.isFiring) {
                        chargeupTime = (1f - weapon.chargeLevel) * weapon.spec.chargeTime
                        burstTime = weapon.derivedStats.burstFireDuration
                        cooldownTime = weapon.cooldown
                    } else if (weapon.isInBurst && weapon.isFiring) {
                        chargeupTime = (weapon.cooldownRemaining / weapon.cooldown) * burstDelay
                        burstTime = weapon.burstFireTimeRemaining
                        cooldownTime = weapon.cooldown
                    } else if (weapon.cooldownRemaining != 0f && !weapon.isInBurst && weapon.isFiring) {
                        cooldownTime = weapon.cooldownRemaining
                    }

                    // apply ROF multis
                    chargeupTime = applyROFMulti(chargeupTime, weapon, enemy.mutableStats)
                    burstTime = applyROFMulti(burstTime, weapon, enemy.mutableStats)
                    burstDelay = applyROFMulti(burstDelay, weapon, enemy.mutableStats)
                    cooldownTime = applyROFMulti(cooldownTime, weapon, enemy.mutableStats)

                    var currentPredictionTime = disabledTime
                    while (currentPredictionTime < maxTime - travelTime && currentPredictionTime < inRangeTime) {
                        currentPredictionTime += max(0f, chargeupTime)
                        while (burstTime > 0.01f) { // avoid floating point jank
                            if (currentPredictionTime > delayUntilOnTarget) {
                                futureHits.add(
                                    FutureHit(
                                        timeToHit = currentPredictionTime + travelTime + currentTime,
                                        rangeLeftAfterHit = rangeLeft,
                                        angleOfHit = shipToWeaponAngle,
                                        damageType = weapon.damageType,
                                        hitStrength = trueSingleInstanceDamage / linkedBarrels,
                                        damage = trueSingleInstanceDamage * linkedBarrels,
                                        empDamage = trueSingleInstanceEMPDamage * linkedBarrels,
                                        softFlux = weapon.damage.isSoftFlux,
                                        enemyID =  enemy.id,
                                    )
                                )
                            }
                            burstTime -= max(SINGLE_FRAME, burstDelay)
                            currentPredictionTime += max(SINGLE_FRAME, burstDelay)
                            if (currentPredictionTime > (maxTime - travelTime) || currentPredictionTime > inRangeTime) break
                        }
                        currentPredictionTime += max(0f, cooldownTime)
                        currentPredictionTime = max(currentPredictionTime, delayUntilOnTarget) // wait for weapon to finish aiming if not yet aimed

                        currentPredictionTime += if ((chargeupTime + cooldownTime) <= SINGLE_FRAME) SINGLE_FRAME else 0f // make sure to not get stuck in an infinite

                        // reset chargeup/cooldown to idle weapon stats
                        chargeupTime = applyROFMulti(weapon.spec.chargeTime, weapon, enemy.mutableStats)
                        burstTime = applyROFMulti(weapon.derivedStats.burstFireDuration, weapon, enemy.mutableStats)
                        cooldownTime = applyROFMulti(weapon.cooldown, weapon, enemy.mutableStats)
                    }
                }
            }
        }
        return futureHits
    }

    fun processMissiles(
        futureHits: MutableList<FutureHit>,
        currentTime: Float,
        ship: ShipAPI,
        testPoint: Vector2f,
        testVelocity: Vector2f,
        shipRadius: Float,
        missileSpec: MissileSpecAPI,
        isFlare: Boolean,
        isMine: Boolean,
        isGuided: Boolean,
        maxFlightTime: Float,
        flightTime: Float,
        armedWhileFizzling: Boolean,
        noFlameoutOnFizzling: Boolean,
        missileMaxSpeed: Float,
        missileMaxTurnRate: Float,
        missileStartingAngle: Float,
        missileStartingLocation: Vector2f,
        missileVelocity: Vector2f,
        missileAccel: Float,
        missileDamageAmount: Float,
        missileDamageType: DamageType,
        missileEmpAmount: Float,
        isSoftFlux: Boolean,
        enemyID: String,
        isMirv: Boolean,
        mirvNumWarheads: Int,
        mirvWarheadDamage: Float,
        mirvWarheadEMPDamage: Float,
        untilMineExplosion: Float,
        mineExplosionRange: Float
    ) {
        if (isFlare) return
        val fizzleTime = if (armedWhileFizzling) missileSpec.flameoutTime else 0f
        val flightTimeLeft = maxFlightTime - flightTime + fizzleTime

        // ignore missiles that arnt dangerous anymore
        if (flightTimeLeft <= 0) return

        val currentlyGuided = (isGuided || missileSpec.behaviorSpec?.params?.optString("behavior") == "CUSTOM")
                && (flightTime < maxFlightTime || noFlameoutOnFizzling) && !isMine

        if (currentlyGuided) {
            val collisionTime = guidedMissileTravelTime(
                missileSpec = missileSpec,
                maxSpeed = missileMaxSpeed,
                maxTurnRate = missileMaxTurnRate,
                missileStartingAngle = missileStartingAngle,
                missileStartingLocation = missileStartingLocation,
                targetLocation = testPoint,
                targetVelocity = testVelocity,
                targetRadius = shipRadius,
                maxTime = flightTimeLeft
            )
            if (collisionTime < flightTimeLeft) {
                val damage = calculateDamageToShip(if (isMirv) mirvWarheadDamage else missileDamageAmount, ship)
                val damageInstances = if (isMirv) mirvNumWarheads else 1
                for (instance in 1..damageInstances) {
                    futureHits.add(
                        FutureHit(
                            timeToHit = collisionTime + currentTime,
                            rangeLeftAfterHit = missileVelocity.length() * (flightTimeLeft - collisionTime),
                            angleOfHit = Misc.getAngleInDegrees(testPoint, missileStartingLocation),
                            damageType = missileDamageType,
                            hitStrength = damage,
                            damage = damage,
                            empDamage = if (isMirv) mirvWarheadEMPDamage else missileEmpAmount,
                            softFlux = isSoftFlux,
                            enemyID = enemyID,
                        )
                    )
                }
            }
        } else { // handle unguided missiles
            // calculate the collision time
            val collisionTime = calculateCollisionTime(
                testVelocity,
                testPoint,
                shipRadius,
                missileStartingLocation,
                missileVelocity,
                Misc.getUnitVectorAtDegreeAngle(missileStartingAngle) * missileAccel,
                missileMaxSpeed,
                missileSpec.hullSpec.collisionRadius,
            )
            var rangeLeft = 0f
            var timeToHit = 0f
            var hits = false
            // if it does collide, and it collides while the missile is still dangerous add FutureHit
            if (collisionTime != null && collisionTime < flightTimeLeft) {
                rangeLeft = missileVelocity.length() * (flightTimeLeft - collisionTime)
                timeToHit = collisionTime + currentTime
                hits = true
            }
            // if mine, do a final check for the final mine explosion
            if (isMine && !hits) {
                val mineLocation = missileStartingLocation + (missileVelocity * untilMineExplosion)
                val shipLocation = testPoint + (testVelocity * untilMineExplosion)
                val distance = MathUtils.getDistance(mineLocation, shipLocation)
                if (distance < shipRadius + mineExplosionRange) {
                    rangeLeft = shipRadius + mineExplosionRange - distance
                    timeToHit = untilMineExplosion
                    hits = true
                }
            }

            // add the hit if hits
            if (hits) {
                val damage = calculateDamageToShip(missileDamageAmount, ship)
                futureHits.add(
                    FutureHit(
                        timeToHit = timeToHit,
                        rangeLeftAfterHit = rangeLeft,
                        angleOfHit = Misc.getAngleInDegrees(testPoint, missileStartingLocation),
                        damageType = missileDamageType,
                        hitStrength = damage,
                        damage = damage,
                        empDamage = missileEmpAmount,
                        softFlux = isSoftFlux,
                        enemyID = enemyID,
                    )
                )
            }
        }
    }


    fun guidedMissileTravelTime(
        missileSpec: MissileSpecAPI,
        maxSpeed: Float,
        maxTurnRate: Float,
        missileStartingAngle: Float,
        missileStartingLocation: Vector2f,
        targetLocation: Vector2f,
        targetVelocity: Vector2f,
        targetRadius: Float,
        maxTime: Float
    ): Float {

        val params = missileSpec.behaviorSpec?.params
        var travelTime: Float? = null
        if (params?.optString("behavior") == "MIRV") {
            val splitRange = params.optFloat("splitRange")
            if (!splitRange.isNaN() && splitRange > 0) {
                val effectiveRadius = targetRadius + splitRange
                travelTime = calculateTravelTimeIteratively(
                    maxSpeed,
                    maxTurnRate,
                    missileStartingAngle,
                    missileStartingLocation,
                    targetLocation,
                    targetVelocity,
                    effectiveRadius,
                    3,
                    maxTime
                )
                travelTime += splitRange / (maxSpeed * 3) //TODO: apparently theres just no way to get the actual speed for MIRVS
            }
        }
        if (params?.optString("behavior") == "CUSTOM") { // dem check
            val triggerDistance = params.optJSONArray("triggerDistance")?.optFloat(0)
            if (triggerDistance != null && !triggerDistance.isNaN() && triggerDistance > 0) {
                val effectiveRadius = targetRadius + triggerDistance

                if (MathUtils.isWithinRange(missileStartingLocation, targetLocation, effectiveRadius)) {
                    return 0.3f // always about to hit cus I have no clue how to get the laser status lmao
                }

                travelTime = calculateTravelTimeIteratively(
                    maxSpeed,
                    maxTurnRate,
                    missileStartingAngle,
                    missileStartingLocation,
                    targetLocation,
                    targetVelocity,
                    effectiveRadius,
                    3,
                    maxTime
                )
                travelTime += params.optFloat("targetingTime") // dem targeting time
            }
        }
        if (travelTime == null) {
            val effectiveRadius = targetRadius + missileSpec.hullSpec.collisionRadius
            travelTime = calculateTravelTimeIteratively(
                maxSpeed,
                maxTurnRate,
                missileStartingAngle,
                missileStartingLocation,
                targetLocation,
                targetVelocity,
                effectiveRadius,
                3,
                maxTime
            )
        }

        return travelTime
    }

    fun calculateTravelTimeIteratively(
        maxSpeed: Float,
        maxTurnRate: Float,
        missileStartingAngle: Float,
        missileStartingLocation: Vector2f,
        targetLocation: Vector2f,
        targetVelocity: Vector2f,
        effectiveRadius: Float,
        numberOfIterations: Int,
        maxTime: Float
    ): Float {
        var time = missileTravelTimeSingleIteration(
            maxSpeed,
            maxTurnRate,
            missileStartingAngle,
            missileStartingLocation,
            targetLocation,
            effectiveRadius
        )
        if (time > maxTime) return Float.MAX_VALUE
        for (iterCount in 2..numberOfIterations) {
            val predictedTargetLocation = targetLocation + targetVelocity * time
            time = missileTravelTimeSingleIteration(
                maxSpeed,
                maxTurnRate,
                missileStartingAngle,
                missileStartingLocation,
                predictedTargetLocation,
                effectiveRadius
            )
            if (time > maxTime) return Float.MAX_VALUE
        }
        return time
    }

    fun missileTravelTimeSingleIteration(
        maxSpeed: Float, maxTurnRate: Float,
        missileStartingAngle: Float, missileStartingLocation: Vector2f, targetLocation: Vector2f, targetRadius: Float
    ): Float {
        // for guided, do some complex math to figure out the time it takes to hit
        val missileTurningRadius = (maxSpeed / (maxTurnRate * Math.PI / 180)).toFloat()
        var missileCurrentAngle = missileStartingAngle
        var missileCurrentLocation = missileStartingLocation
        var missileCurrentDistance = MathUtils.getDistance(missileCurrentLocation, targetLocation)
        var missileTargetAngle = VectorUtils.getAngle(missileCurrentLocation, targetLocation)
        var missileRotationNeeded = MathUtils.getShortestRotation(missileCurrentAngle, missileTargetAngle)
        val missileRotationCenter = MathUtils.getPointOnCircumference(
            missileStartingLocation, missileTurningRadius,
            missileCurrentAngle + (if (missileRotationNeeded > 0) 90 else -90)
        )

        var missileRotationSeconds = 0f
        while (missileCurrentDistance > targetRadius && missileRotationSeconds < 30f && abs(missileRotationNeeded) > 1f) {
            missileRotationSeconds += abs(missileRotationNeeded) / maxTurnRate
            missileCurrentAngle = missileTargetAngle
            missileCurrentLocation = MathUtils.getPointOnCircumference(
                missileRotationCenter,
                missileTurningRadius,
                missileCurrentAngle + (if (missileRotationNeeded > 0) -90 else 90)
            )

            missileCurrentDistance = MathUtils.getDistance(missileCurrentLocation, targetLocation)
            missileTargetAngle = VectorUtils.getAngle(missileCurrentLocation, targetLocation)
            missileRotationNeeded = MathUtils.getShortestRotation(missileCurrentAngle, missileTargetAngle)
        }

        val missileStraightSeconds =
            max(MathUtils.getDistance(missileCurrentLocation, targetLocation) - targetRadius, 0f) / maxSpeed

        return missileRotationSeconds + missileStraightSeconds
    }
}
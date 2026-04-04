package org.starficz.combatai.predictor

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.FastTrig
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.ext.getAngle
import org.lazywizard.lazylib.ext.plusAssign
import org.lwjgl.util.vector.Vector2f
import org.magiclib.kotlin.normalizeAngle
import org.scy.dot
import org.scy.normalized
import org.scy.times
import kotlin.math.abs
import kotlin.math.sign

class CombatSimulation(
    val snapshot: CombatSnapshot,
    val timelines: Map<String, ShipTimeline>
) {
    constructor(snapshot: CombatSnapshot) : this(
        snapshot = snapshot,
        timelines = snapshot.ships.associate { it.id to ShipTimeline(snapshot.snapshotTime) }
    ) {
        snapshot.ships.forEach { updateSingleShipInternal(it, null, null) }
        updateFacings()
        updateModules()
    }

    fun branch(): CombatSimulation {
        val clonedTimelines = timelines.mapValues { (_, original) ->
            ShipTimeline(snapshot.snapshotTime).apply { copyFrom(original) }
        }
        return CombatSimulation(snapshot, clonedTimelines)
    }

    fun updateShip(shipId: String, accelDir: Vector2f, mobility: MobilityProfile?) {
        val ship = snapshot.ships.find { it.id == shipId } ?: return
        updateSingleShipInternal(ship, accelDir, mobility)
        updateFacings()
        updateModules()
    }

    /**
     * Internal Math: Calculates pure positional kinematics without triggering relational updates.
     */
    private fun updateSingleShipInternal(
        ship: ShipStateSnapshot,
        accelDir: Vector2f?,
        mobility: MobilityProfile?
    ) {
        val timeline = timelines[ship.id] ?: return
        if (ship.parentId != null) return

        val fwdUnitVector = Misc.getUnitVectorAtDegreeAngle(ship.facing)
        val leftUnitVector = Misc.getUnitVectorAtDegreeAngle(ship.facing + 90f)

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
                val limitByFwd = if (kotlin.math.abs(fwdComponent) > 0.0001f) maxFwdThrust / kotlin.math.abs(fwdComponent) else Float.MAX_VALUE
                val limitByLat = if (kotlin.math.abs(latComponent) > 0.0001f) strafeAccel / kotlin.math.abs(latComponent) else Float.MAX_VALUE

                accelNormalized.scale(kotlin.math.min(limitByFwd, limitByLat))
                shipAccel.set(accelNormalized)
            }
            return shipAccel
        }

        val spd1 = mobility?.maxSpeedOverride1 ?: ship.maxSpeed
        val duration1 = mobility?.phase1Duration ?: Float.MAX_VALUE
        val spd2 = mobility?.maxSpeedOverride2 ?: ship.maxSpeed

        val shipAccel1 = calcAccel(mobility?.accelOverride1, mobility?.decelOverride1)
        val shipAccel2 = calcAccel(mobility?.accelOverride2, mobility?.decelOverride2)

        var locX = ship.location.x
        var locY = ship.location.y
        var velX = ship.velocity.x
        var velY = ship.velocity.y

        for (i in 0 until timeline.size) {
            val elapsedTime = (i + 1) * Constants.TIME_STEP
            val inPhase1 = elapsedTime <= duration1
            val currentMaxSpeed = if (inPhase1) spd1 else spd2
            val currentAccel = if (inPhase1) shipAccel1 else shipAccel2

            locX += velX * Constants.TIME_STEP
            locY += velY * Constants.TIME_STEP

            if (elapsedTime < Constants.ENGINE_COAST_ASSUMPTION || accelDir != null) {
                velX += currentAccel.x * Constants.TIME_STEP
                velY += currentAccel.y * Constants.TIME_STEP
            }

            val speedSq = velX * velX + velY * velY
            if (speedSq > currentMaxSpeed * currentMaxSpeed) {
                val speed = kotlin.math.sqrt(speedSq)
                velX = (velX / speed) * currentMaxSpeed
                velY = (velY / speed) * currentMaxSpeed
            }

            timeline.x[i] = locX
            timeline.y[i] = locY
        }
    }

    private fun updateFacings() {
        val dataMap = snapshot.ships.associateBy { it.id }

        for ((shipId, timeline) in timelines) {
            val ship = dataMap[shipId] ?: continue
            if (ship.parentId != null) continue

            var currentAngVel = ship.angularVelocity
            var currentFacing = ship.facing

            for (i in 0 until timeline.size) {
                val myLocX = timeline.x[i]
                val myLocY = timeline.y[i]

                var targetLocX: Float? = null
                var targetLocY: Float? = null
                var minDistanceSq = Float.MAX_VALUE

                if (ship.turnAcceleration >= 0.01f) {
                    for ((otherShipId, otherTimeline) in timelines) {
                        val otherShip = dataMap[otherShipId] ?: continue
                        if (ship.owner == otherShip.owner || otherShip.parentId != null) continue

                        val otherLocX = otherTimeline.x[i]
                        val otherLocY = otherTimeline.y[i]

                        val dx = myLocX - otherLocX
                        val dy = myLocY - otherLocY
                        val distSq = dx * dx + dy * dy

                        if (distSq < minDistanceSq) {
                            minDistanceSq = distSq
                            targetLocX = otherLocX
                            targetLocY = otherLocY
                        }
                    }
                }

                if (targetLocX != null && targetLocY != null) {
                    val dx = targetLocX - myLocX
                    val dy = targetLocY - myLocY
                    val angleToTarget = Math.toDegrees(FastTrig.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    val rotationNeeded = MathUtils.getShortestRotation(currentFacing, angleToTarget)
                    val stoppingDist = (currentAngVel * currentAngVel) / (2f * ship.turnAcceleration)
                    val movingTowards = (sign(rotationNeeded) == sign(currentAngVel)) && abs(currentAngVel) > 0.1f

                    if (movingTowards && abs(rotationNeeded) <= stoppingDist) {
                        val change = ship.turnAcceleration * Constants.TIME_STEP
                        if (abs(currentAngVel) <= change) currentAngVel = 0f
                        else currentAngVel -= sign(currentAngVel) * change
                    } else {
                        currentAngVel += sign(rotationNeeded) * ship.turnAcceleration * Constants.TIME_STEP
                    }
                } else {
                    if (abs(currentAngVel) > 0) {
                        val change = ship.turnAcceleration * Constants.TIME_STEP
                        if (abs(currentAngVel) <= change) currentAngVel = 0f
                        else currentAngVel -= sign(currentAngVel) * change
                    }
                }

                currentAngVel = currentAngVel.coerceIn(-ship.maxTurnRate, ship.maxTurnRate)
                currentFacing = (currentFacing + (currentAngVel * Constants.TIME_STEP)).normalizeAngle()

                timeline.facings[i] = currentFacing
            }
        }
    }

    private fun updateModules() {
        for (module in snapshot.ships) {
            if (module.parentId == null) continue

            val parentTimeline = timelines[module.parentId] ?: continue
            val moduleTimeline = timelines[module.id] ?: continue

            for (i in 0 until moduleTimeline.size) {
                val pFacing = parentTimeline.facings[i]
                val pX = parentTimeline.x[i]
                val pY = parentTimeline.y[i]

                val angle = pFacing + module.moduleOffsetAngle
                val angleRad = Math.toRadians(angle.toDouble())

                moduleTimeline.x[i] = pX + (module.moduleOffsetDist * FastTrig.cos(angleRad)).toFloat()
                moduleTimeline.y[i] = pY + (module.moduleOffsetDist * FastTrig.sin(angleRad)).toFloat()
                moduleTimeline.facings[i] = (pFacing + module.moduleFacingOffset).normalizeAngle()
            }
        }
    }
}
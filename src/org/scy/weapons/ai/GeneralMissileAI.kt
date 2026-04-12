package org.scy.weapons.ai

import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.combat.AIUtils
import org.lwjgl.util.vector.Vector2f
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max

object GeneralMissileAI {

    /**
     * Container for the primitive variables that need to persist between frames.
     * Instantiated once per weaving missile AI.
     */
    class WeavingState (
        var offsetRange: Float = 0.8f,
    ){
        var offsetMult: Float = MathUtils.getRandomNumberInRange(-offsetRange, offsetRange)
        var cachedIntercept: Vector2f? = null
        var cachedTimeToIntercept: Float? = null
    }

    /**
     * Reusable weaving guidance function.
     * Requires state and intervals to be managed and passed in by the caller.
     */
    fun guideWeavingMissile(
        missile: MissileAPI,
        target: CombatEntityAPI,
        dt: Float,
        navInterval: IntervalUtil,
        offsetInterval: IntervalUtil,
        state: WeavingState,
        proportionalNavN: Float = 3f,
        minSpeed: Float = 1f
    ) {
        // --- Update Evasive Offset ---
        offsetInterval.advance(dt)
        if (offsetInterval.intervalElapsed()) {
            state.offsetMult = MathUtils.getRandomNumberInRange(-state.offsetRange, state.offsetRange)
        }

        // ── MACRO NAVIGATION: Expensive Math (Staggered Execution) ──────────
        navInterval.advance(dt)
        if (navInterval.intervalElapsed() || state.cachedIntercept == null) {
            navInterval.randomize()

            val vmax = missile.maxSpeed
            val pm = missile.location

            val baseIntercept = AIUtils.getBestInterceptPoint(pm, vmax, target.location, target.velocity)
                ?: target.location

            // --- Apply Perpendicular Offset ---
            val targetRadius = target.shield?.radius ?: target.collisionRadius
            val offsetMagnitude = targetRadius * state.offsetMult

            val angleToInterceptPoint = VectorUtils.getAngle(pm, baseIntercept)
            val intercept = MathUtils.getPointOnCircumference(baseIntercept, offsetMagnitude, angleToInterceptPoint + 90f)

            val distToIntercept = MathUtils.getDistance(pm, intercept)

            state.cachedIntercept = intercept
            state.cachedTimeToIntercept = distToIntercept / max(vmax, 1f)
        }

        // ── MICRO STEERING: Delegate to shared function (Executed Every Frame) ──
        steerToIntercept(
            missile = missile,
            interceptPoint = state.cachedIntercept!!,
            timeToIntercept = state.cachedTimeToIntercept,
            proportionalNavN = proportionalNavN,
            minSpeed = minSpeed
        )
    }

    /**
     * Shared Proportional Navigation and Bang-bang controller.
     * Used natively by precise point-defense, or fed cached coordinates by weaving missiles.
     */
    fun steerToIntercept(
        missile: MissileAPI,
        interceptPoint: Vector2f,
        timeToIntercept: Float?,
        proportionalNavN: Float = 3f,
        minSpeed: Float = 1f
    ) {
        val speed = missile.velocity.length()
        val speedRatio = (speed / missile.maxSpeed).coerceIn(0f, 1f)
        val phiDes = VectorUtils.getAngle(missile.location, interceptPoint)

        // Proportional Navigation Math with psiRad adjustment
        val thetaDes: Float = if (speed < minSpeed || timeToIntercept == null) {
            phiDes
        } else {
            val phiV = VectorUtils.getFacing(missile.velocity)
            val betaDeg = MathUtils.getShortestRotation(phiV, phiDes)
            val betaRad = Math.toRadians(betaDeg.toDouble()).toFloat()
            val omegaV = missile.acceleration / speed

            val interceptTime = timeToIntercept.coerceAtLeast(0.1f)
            val arg = (proportionalNavN * (2 - speedRatio) * betaRad) / (interceptTime * omegaV)
            val psiRad = asin(arg.coerceIn(-1f, 1f))

            phiV + Math.toDegrees(psiRad.toDouble()).toFloat()
        }

        // Bang-bang turn controller logic
        val omega = missile.angularVelocity
        val aRot = missile.turnAcceleration
        val theta = missile.facing

        val error = MathUtils.getShortestRotation(theta, thetaDes)
        val sigma = error - omega * abs(omega) / (2f * aRot)
        val deadband = aRot * 0.016f * 0.016f

        if (sigma > deadband) {
            missile.giveCommand(ShipCommand.TURN_LEFT)
        } else if (sigma < -deadband) {
            missile.giveCommand(ShipCommand.TURN_RIGHT)
        }

        // Acceleration control
        val headingErrorToThetaDes = abs(MathUtils.getShortestRotation(theta, thetaDes))
        val threshold = 30f + 30f * speedRatio
        // As long as the missile is within 60 degrees of the mathematically ideal heading, burn.
        // (You can safely use a static threshold here, 60 is a very generous cone)
        if (headingErrorToThetaDes < threshold) {
            missile.giveCommand(ShipCommand.ACCELERATE)
        }
    }
}
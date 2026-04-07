package org.starficz.combatai.evaluators

import com.fs.starfarer.api.combat.ShipAPI
import org.lwjgl.util.vector.Vector2f
import org.scy.getModifiedValueWithout
import org.scy.getOnTimeRemaining
import org.starficz.combatai.predictor.Constants.PREDICTION_DURATION
import org.starficz.combatai.predictor.DamageTimeline
import org.starficz.combatai.predictor.MobilityProfile
import org.starficz.combatai.predictor.PredictorManager

data class EvaluatorResult(
    val backoffDamage: DamageTimeline?,
    val useSystemToBackoff: Boolean
)

interface SystemEvaluator {
    val enforceOverride: Boolean

    fun canHandle(ship: ShipAPI): Boolean

    fun evaluate(
        ship: ShipAPI,
        predictor: PredictorManager,
        backoffVector: Vector2f,
        currentTime: Float,
        backoffLevel: Float
    ): EvaluatorResult
}

abstract class BaseMobilityEvaluator : SystemEvaluator {
    abstract val systemId: String

    // Automatically derives the effect ID used in MutableStats
    open val effectId: String get() = "$systemId effect"

    override fun canHandle(ship: ShipAPI): Boolean {
        return ship.system?.specAPI?.id == systemId
    }

    // no sys also includes the profile of the ship when the system is already active
    protected fun getNoSysProfile(ship: ShipAPI): MobilityProfile {
        val stats = ship.mutableStats

        if (ship.system.isActive && !ship.system.specAPI.isToggle) {
            return MobilityProfile(
                stats.maxSpeed.modifiedValue,
                stats.acceleration.modifiedValue,
                stats.deceleration.modifiedValue,
                ship.system.getOnTimeRemaining(),
                stats.maxSpeed.getModifiedValueWithout(effectId),
                stats.acceleration.getModifiedValueWithout(effectId),
                stats.deceleration.getModifiedValueWithout(effectId)
            )
        }
        else {
            return MobilityProfile(
                stats.maxSpeed.getModifiedValueWithout(effectId),
                stats.acceleration.getModifiedValueWithout(effectId),
                stats.deceleration.getModifiedValueWithout(effectId)
            )
        }
    }
}

abstract class ActiveMobilityEvaluator : BaseMobilityEvaluator() {
    abstract fun getActiveProfile(ship: ShipAPI, base: MobilityProfile): MobilityProfile

    override fun evaluate(
        ship: ShipAPI,
        predictor: PredictorManager,
        backoffVector: Vector2f,
        currentTime: Float,
        backoffLevel: Float
    ): EvaluatorResult {
        val noSys = getNoSysProfile(ship)
        val withSys = getActiveProfile(ship, noSys)

        predictor.queueRequest(ship, backoffVector, noSys)
        predictor.queueRequest(ship, backoffVector, withSys)
        val noSysDamage = predictor.getResult(ship, backoffVector, noSys)
        val withSysDamage = predictor.getResult(ship, backoffVector, withSys)

        val backoffDamage = if (ship.system.isOn || ship.system.isCoolingDown) noSysDamage else withSysDamage

        val sysFlux = withSysDamage?.fluxToShield(currentTime, PREDICTION_DURATION, ship) ?: 0f
        val sysRatio = (sysFlux + ship.currFlux) / ship.maxFlux

        return EvaluatorResult(backoffDamage, sysRatio > backoffLevel * 1.1f)
    }
}

abstract class ToggleMobilityEvaluator : BaseMobilityEvaluator() {
    abstract fun getOnProfile(ship: ShipAPI, base: MobilityProfile): MobilityProfile

    override fun evaluate(
        ship: ShipAPI,
        predictor: PredictorManager,
        backoffVector: Vector2f,
        currentTime: Float,
        backoffLevel: Float
    ): EvaluatorResult {
        val noSys = getNoSysProfile(ship)
        val withSys = getOnProfile(ship, noSys)

        predictor.queueRequest(ship, backoffVector, withSys)
        val withSysDamage = predictor.getResult(ship, backoffVector, withSys)

        val sysFlux = withSysDamage?.fluxToShield(currentTime, PREDICTION_DURATION, ship) ?: 0f
        val sysRatio = (sysFlux + ship.currFlux) / ship.maxFlux

        return EvaluatorResult(withSysDamage, sysRatio > backoffLevel * 1.1f)
    }
}
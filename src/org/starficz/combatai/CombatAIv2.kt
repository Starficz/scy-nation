package org.starficz.combatai

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.impl.campaign.ids.Personalities
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.ext.getDirectionalVector
import org.lwjgl.util.vector.Vector2f
import org.scy.StarficzAIUtils
import org.scy.armorAtCell
import org.scy.weakestArmorRegion
import org.starficz.combatai.evaluators.*
import org.starficz.combatai.predictor.Constants.PREDICTION_DURATION
import org.starficz.combatai.predictor.DamageTimeline
import org.starficz.combatai.predictor.PredictorManager


class CombatAIv2(val ship: ShipAPI) : AdvanceableListener {
    companion object {
        const val BUFFER_TIME = 0.2f
        const val MIN_VENT_LEVEL = 0.2f
        const val SAFE_SHIELD_ARC = 0.2f
        const val MAX_ARMOR_DAMAGE_LEVEL_TO_VENT = 0.1f
        const val MAX_HULL_DAMAGE_LEVEL_TO_VENT = 0.05f
        const val STOP_VENTING_BELOW_HULL_LEVEL = 0.5f
        const val MAX_FLUX_LEVEL_RAISE_TO_HARASS = 0.3f

        const val BASE_DAMAGE = "BASE_DAMAGE"
        const val BACKOFF_DAMAGE = "BACKOFF_DAMAGE"
        const val BEHAVIOR_STATE = "BEHAVIOR_STATE"
        const val USE_MOVEMENT_SYSTEM_TO_BACKOFF = "USE_MOVEMENT_SYSTEM_BACKOFF"

        val EVALUATORS: List<SystemEvaluator> = listOf(
            ScyThrustersEvaluator(),
            ScyArmorSwitchEvaluator(),
            ManeuveringJetsEvaluator(),
            PlasmaJetsEvaluator()
        )

        data class AiConfig(val ventLevel: Float, val harassLevel: Float, val backoffLevel: Float)
        fun getConfigForPersonality(personalityId: String?): AiConfig {
            return when (personalityId) {
                Personalities.TIMID         -> AiConfig(0.2f, 0.2f, 0.7f)
                Personalities.CAUTIOUS      -> AiConfig(0.3f, 0.4f, 0.8f)
                Personalities.STEADY        -> AiConfig(0.4f, 0.6f, 0.85f)
                Personalities.AGGRESSIVE    -> AiConfig(0.5f, 0.7f, 0.9f)
                Personalities.RECKLESS      -> AiConfig(0.6f, 0.8f, 1.0f)
                else                        -> AiConfig(0.4f, 0.6f, 0.9f)
            }
        }
    }

    enum class BehaviorState {
        ADVANCE,
        STANDOFF,
        BACKOFF,
        DISENGAGE,
        VENTING,
    }

    private val interval = IntervalUtil(0.05f, 0.1f)
    private var currentSituation: TacticalVariables? = null
    private val config = getConfigForPersonality(ship.captain?.personalityAPI?.id)

    private val ventLogic = VentLogic()
    private val mobilitySystemLogic = MobilitySystemLogic()
    private val behaviorLogic = BehaviorLogic(config)

    init { Global.getCombatEngine().customData[PredictorManager.FLAG_KEY] = true }

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        interval.advance(amount)

        if (interval.intervalElapsed()) {
            currentSituation = TacticalVariables(ship, engine, EVALUATORS, config.backoffLevel)

            ship.setCustomData(BASE_DAMAGE, currentSituation?.baseDamage)
            ship.setCustomData(BACKOFF_DAMAGE, currentSituation?.backoffDamage)
        }

        val snap = currentSituation ?: return

        if (snap.isVanillaFallback) {
            ship.setCustomData(BEHAVIOR_STATE, "VANILLA_FALLBACK")
            return
        }

        ventLogic.execute(ship, snap)
        behaviorLogic.execute(ship, snap, ventLogic.safeToVent)
        mobilitySystemLogic.execute(ship, snap, behaviorLogic.currentState)
    }

    class TacticalVariables(
        val ship: ShipAPI,
        engine: CombatEngineAPI,
        evaluators: List<SystemEvaluator>,
        backoffLevel: Float
    ) {
        val currentTime = engine.getTotalElapsedTime(false)
        private val predictor = PredictorManager.getInstance(engine)

        // 1. Spatial Awareness (Order matters!)
        val safePoint: Vector2f? = StarficzAIUtils.getBackingOffStrafePoint(ship)
        val isVanillaFallback = safePoint == null
        val backoffVector: Vector2f = safePoint?.let { ship.location.getDirectionalVector(it) } ?: Misc.ZERO

        val getCloser = run {
            val range = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.MANEUVER_RANGE_FROM_TARGET) as? Float ?: 0f
            ship.shipTarget?.let { MathUtils.getDistanceSquared(it, ship) > range*range } ?: true
        }

        // 2. Base Predictions
        val baseDamage: DamageTimeline? = run {
            predictor.queueRequest(ship, Misc.ZERO)
            predictor.getResult(ship, Misc.ZERO)
        }

        val baseRatio: Float = run {
            val flux = baseDamage?.fluxToShield(currentTime, PREDICTION_DURATION, ship) ?: 0f
            (flux + ship.currFlux) / ship.maxFlux
        }

        val wantToPoke = baseRatio < MAX_FLUX_LEVEL_RAISE_TO_HARASS

        // 3. System & Backoff Predictions
        val systemEvaluation: EvaluatorResult? = run {
            val eval = evaluators.find { it.canHandle(ship) }
            eval?.evaluate(ship, predictor, backoffVector, currentTime, backoffLevel)
        }
        val enforceOverride = evaluators.find { it.canHandle(ship) }?.enforceOverride ?: false

        val backoffDamage: DamageTimeline? = run {
            if (systemEvaluation != null) {
                systemEvaluation.backoffDamage
            } else {
                predictor.queueRequest(ship, backoffVector)
                predictor.getResult(ship, backoffVector)
            }
        }

        val backoffRatio: Float = run {
            val flux = backoffDamage?.fluxToShield(currentTime, PREDICTION_DURATION, ship) ?: 0f
            (flux + ship.currFlux) / ship.maxFlux
        }
    }

    class VentLogic {
        var ventNow = false
        var safeToVent = false

        fun execute(ship: ShipAPI, snap: TacticalVariables) {
            // calc danger time if going to vent
            val timeToRaiseShields = ship.shield?.let {
                it.unfoldTime * (SAFE_SHIELD_ARC / it.arc).coerceAtMost(1f)
            } ?: 0f
            val dangerTime = ship.fluxTracker.timeToVent + timeToRaiseShields + BUFFER_TIME

            val hasModularArmor = ship.childModulesCopy.count() == ship.hullSpec.allWeaponSlotsCopy.count { it.isStationModule }

            // find actual armor and calc if venting now is safer than trying to tank
            val armor = if (hasModularArmor) ship.armorGrid.armorRating * 1.5f
                        else ship.armorGrid.armorAtCell(ship.armorGrid.weakestArmorRegion()!!) ?: 0f
            val aggressiveVentResult = snap.backoffDamage?.compareVentingVsNotVenting(
                snap.currentTime, dangerTime, (1 - ship.hardFluxLevel) * ship.maxFlux, ship, startingArmor = armor
            )

            // calc "safe" vent limits and if we are below those
            val armorLimit = run {
                val armorRating = (ship.armorGrid.armorRating * if (hasModularArmor) 1.5f else 1f)
                armorRating * MAX_ARMOR_DAMAGE_LEVEL_TO_VENT
            }

            val hullLimit = run {
                val hullLevelLimit = if (ship.hullLevel < STOP_VENTING_BELOW_HULL_LEVEL) 0.01f
                else MAX_HULL_DAMAGE_LEVEL_TO_VENT

                ship.maxHitpoints * hullLevelLimit
            }

            val (ventArmor, ventHull) = snap.backoffDamage?.damageToArmorAndHull(snap.currentTime, dangerTime, ship)
                ?: Pair(Float.MAX_VALUE, Float.MAX_VALUE)

            // vent if safe, or safer then not venting
            safeToVent = ventArmor < armorLimit && ventHull < hullLimit
            ventNow = safeToVent || (aggressiveVentResult?.isVentingSafer == true && ship.hullLevel > STOP_VENTING_BELOW_HULL_LEVEL)

            if (ventNow && ship.fluxLevel > MIN_VENT_LEVEL && !ship.fluxTracker.isVenting) {
                ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
            }
        }
    }

    class MobilitySystemLogic {
        fun execute(ship: ShipAPI, snap: TacticalVariables, currentState: BehaviorState) {
            val eval = snap.systemEvaluation ?: return

            ship.setCustomData(USE_MOVEMENT_SYSTEM_TO_BACKOFF, eval.useSystemToBackoff)
            if (!snap.enforceOverride) return

            val isBackingOff = currentState == BehaviorState.BACKOFF ||
                               currentState == BehaviorState.DISENGAGE

            val shouldFire = when {
                isBackingOff && eval.useSystemToBackoff -> true
                !isBackingOff && snap.getCloser && snap.wantToPoke -> true
                else -> false
            }

            val sys = ship.system ?: return
            val isReady = sys.state == ShipSystemAPI.SystemState.IDLE || (sys.specAPI.isToggle && !sys.isOn)

            if (shouldFire && isReady) {
                ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)
            } else if (!shouldFire) {
                if (sys.specAPI.isToggle && sys.isOn) ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)
                else ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
            }
        }
    }

    class BehaviorLogic(val config: AiConfig) {
        var currentState = BehaviorState.STANDOFF

        fun execute(ship: ShipAPI, snap: TacticalVariables, safeToVent: Boolean) {
            currentState = when {
                ship.fluxTracker.isVenting -> BehaviorState.VENTING
                ship.fluxLevel > config.ventLevel && currentState == BehaviorState.DISENGAGE -> BehaviorState.DISENGAGE
                snap.backoffRatio > 1f -> BehaviorState.DISENGAGE
                snap.backoffRatio > config.backoffLevel -> BehaviorState.BACKOFF
                snap.baseRatio < config.harassLevel -> BehaviorState.ADVANCE
                else -> BehaviorState.STANDOFF
            }

            ship.setCustomData(BEHAVIOR_STATE, currentState)
            applyFlags(ship, snap.getCloser, safeToVent)
        }

        private fun applyFlags(ship: ShipAPI, getCloser: Boolean, safeToVent: Boolean) {
            val flagDuration = 0.1f

            ship.aiFlags.apply {
                when (currentState) {
                    BehaviorState.ADVANCE -> {
                        setFlag(ShipwideAIFlags.AIFlags.HARASS_MOVE_IN, flagDuration)
                        setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, flagDuration)
                        setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF_EVEN_WHILE_VENTING, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.HARASS_MOVE_IN_COOLDOWN)
                        unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF)
                        (getCustom(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE) as? Float)?.let {
                            if (it >= 2400f) unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE)
                        }
                    }
                    BehaviorState.STANDOFF -> {
                        if (getCloser) setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF)
                        unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE)
                        (getCustom(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE) as? Float)?.let {
                            if (it >= 2400f) unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE)
                        }
                    }
                    BehaviorState.BACKOFF -> {
                        setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, flagDuration)
                        setFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF)
                    }
                    BehaviorState.DISENGAGE -> {
                        setFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE, flagDuration, 2500f)
                        setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, flagDuration)
                        setFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF)
                    }
                    BehaviorState.VENTING -> {
                        if (!safeToVent) {
                            setFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE, flagDuration, 2500f)
                            setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, flagDuration)
                            setFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE, flagDuration)
                            unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF)
                            unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF_EVEN_WHILE_VENTING)
                        } else {
                            if (getCloser) {
                                setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, flagDuration)
                                setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF_EVEN_WHILE_VENTING, flagDuration)
                            }
                            unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF)
                            unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE)
                            (getCustom(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE) as? Float)?.let {
                                if (it >= 2400f) unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE)
                            }
                        }
                    }
                }
            }
        }
    }
}


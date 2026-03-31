package org.scy.combatai

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipwideAIFlags
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.impl.campaign.ids.Personalities
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.scy.StarficzAIUtils
import org.scy.armorAtCell
import org.scy.combatai.predictor.Constants.PREDICTION_DURATION
import org.scy.combatai.predictor.PredictorManager
import org.scy.combatai.predictor.MobilityProfile
import org.scy.getModifiedValueWithout
import org.scy.weakestArmorRegion

class ScyAiV2(val ship: ShipAPI) : AdvanceableListener {

    enum class BehaviorState {
        ADVANCE,
        STANDOFF,
        BACKOFF,
        DISENGAGE,
        VENTING,
    }

    private val interval = IntervalUtil(0.05f, 0.1f)

    private val harassLevel: Float
    private val backoffLevel: Float
    private val ventLevel: Float

    // Internal AI State
    private var currentState = BehaviorState.STANDOFF
    private var ventNow = false
    private var safeToVent = false
    private var getCloser = false

    init {
        val (vent, harass, backoff) = when (ship.captain?.personalityAPI?.id) {
            Personalities.TIMID -> Triple(0.2f, 0.2f, 0.7f)
            Personalities.CAUTIOUS -> Triple(0.3f, 0.4f, 0.8f)
            Personalities.STEADY -> Triple(0.4f, 0.6f, 0.85f)
            Personalities.AGGRESSIVE -> Triple(0.5f, 0.7f, 0.9f)
            Personalities.RECKLESS -> Triple(0.6f, 0.8f, 1.0f)
            else -> Triple(0.4f, 0.6f, 0.9f)
        }
        ventLevel = vent
        harassLevel = harass
        backoffLevel = backoff
        // we need custom damage predictor
        Global.getCombatEngine().customData["NeedsDamagePredictor"] = true
    }

    override fun advance(amount: Float) {
        interval.advance(amount)

        if (interval.intervalElapsed()) {
            val engine = Global.getCombatEngine()
            if (engine != null) {
                val predictor = PredictorManager.getInstance(engine)
                evaluateState(predictor)
            } else {
                currentState = BehaviorState.STANDOFF
            }
        }

        enforceState()

        if (ventNow && ship.fluxLevel > 0.2f && !ship.fluxTracker.isVenting) {
            ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
        }
    }

    private fun evaluateState(predictor: PredictorManager) {
        val optimalRange = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.MANEUVER_RANGE_FROM_TARGET) as? Float ?: 0f
        getCloser = ship.shipTarget?.let{ MathUtils.getDistance(it, ship) > optimalRange } ?: false

        val safePoint = StarficzAIUtils.getBackingOffStrafePoint(ship)

        if (safePoint == null) {
            currentState = BehaviorState.ADVANCE
            return
        }

        predictor.queueRequest(ship, Misc.ZERO)
        val baseDamage = predictor.getResult(ship, Misc.ZERO) ?: return
        val currentTime = Global.getCombatEngine().getTotalElapsedTime(false)
        val baseFluxGained = baseDamage.fluxToShield(currentTime,  PREDICTION_DURATION, ship)
        val baseRatio = (baseFluxGained + ship.currFlux) / ship.maxFlux

        val backupVector = VectorUtils.getDirectionalVector(ship.location, safePoint)
        val systemId = ship.system?.specAPI?.id ?: ""
        val isSecondaryThrusters = systemId == "SCY_secondaryThrusters"
        val isArmorSwitch = systemId == "SCY_armorSwitch"

        // Evaluate mobility systems and return the standard (no-system) backoff damage to be used globally
        val backoffDamage = if (isSecondaryThrusters || isArmorSwitch) {
            val systemModEffectID = "$systemId effect" // string copied from obf code
            val noSysSpeed = ship.mutableStats.maxSpeed.getModifiedValueWithout(systemModEffectID)
            val noSysAccel = ship.mutableStats.acceleration.getModifiedValueWithout(systemModEffectID)
            val noSysDecel = ship.mutableStats.deceleration.getModifiedValueWithout(systemModEffectID)

            val profileNoSys = MobilityProfile(noSysSpeed, noSysAccel, noSysDecel)

            val profileSys = if (isSecondaryThrusters) {
                // Normal Ability (Active + Cooldown)
                val sys = ship.system
                val activeTime = sys.chargeUpDur + sys.chargeActiveDur + sys.chargeDownDur
                MobilityProfile(
                    maxSpeedOverride1 = noSysSpeed + 100f,
                    accelOverride1 = noSysAccel * 2.5f,
                    decelOverride1 = noSysDecel * 2.5f,
                    phase1Duration = activeTime,
                    maxSpeedOverride2 = noSysSpeed,
                    accelOverride2 = noSysAccel,
                    decelOverride2 = noSysDecel
                )
            } else {
                // Toggle Ability (Always Active when on)
                MobilityProfile(
                    maxSpeedOverride1 = noSysSpeed + 40f,
                    accelOverride1 = noSysAccel * 2.0f,
                    decelOverride1 = noSysDecel * 2.0f
                )
            }

            predictor.queueRequest(ship, backupVector, profileNoSys)
            predictor.queueRequest(ship, backupVector, profileSys)

            val noSysDamageResult = predictor.getResult(ship, backupVector, profileNoSys)
            val sysDamageResult = predictor.getResult(ship, backupVector, profileSys)

            // Evaluate if we need the system to survive the backoff
            val backoffSysFlux = sysDamageResult?.fluxToShield(currentTime, PREDICTION_DURATION, ship)
                ?: noSysDamageResult?.fluxToShield(currentTime, PREDICTION_DURATION, ship)
                ?: 0f

            val sysRatio = (backoffSysFlux + ship.currFlux) / ship.maxFlux
            ship.setCustomData("SCY_useMobilitySystemToBackoff", sysRatio > backoffLevel*1.1f)

            if (isSecondaryThrusters && (ship.system.isOn || ship.system.isCoolingDown)) noSysDamageResult
            else sysDamageResult
        } else {
            predictor.queueRequest(ship, backupVector)
            predictor.getResult(ship, backupVector)
        }
        val timeToRaiseShields =  ship.shield?.let{ it.unfoldTime * (60f/it.arc).coerceAtMost(1f) } ?: 0f

        val backoffFlux = backoffDamage?.fluxToShield(currentTime, PREDICTION_DURATION, ship) ?: 0f
        val dangerTime =  ship.fluxTracker.timeToVent + timeToRaiseShields + 0.2f
        val (ventArmorDamageTaken, ventHullDamageTaken) =
            backoffDamage?.damageToArmorAndHull(currentTime, dangerTime, ship)
                ?: Pair(Float.MAX_VALUE, Float.MAX_VALUE)
        val backoffRatio = (backoffFlux + ship.currFlux) / ship.maxFlux

        val armor = if (ship.childModulesCopy.isNotEmpty()) ship.armorGrid.armorRating * 1.5f
        else ship.armorGrid.armorAtCell(ship.armorGrid.weakestArmorRegion()!!) ?: 0f

        val ventNowResult = backoffDamage?.compareVentingVsNotVenting(
            currentTime, dangerTime, (1-ship.fluxLevel)*ship.maxFlux, ship, startingArmor = armor)

        val armorDamageVentLimit = (ship.armorGrid.armorRating * if (ship.childModulesCopy.isNotEmpty()) 1.5f else 1f)/10
        safeToVent = ventArmorDamageTaken < armorDamageVentLimit && ventHullDamageTaken < ship.maxHitpoints/50
        ventNow = safeToVent || ventNowResult?.isVentingSafer == true

        currentState = when {
            ship.fluxTracker.isVenting -> BehaviorState.VENTING
            currentState == BehaviorState.DISENGAGE && ship.fluxLevel > ventLevel -> BehaviorState.DISENGAGE
            backoffRatio > 1f -> BehaviorState.DISENGAGE
            backoffRatio > backoffLevel -> BehaviorState.BACKOFF
            baseRatio < harassLevel -> BehaviorState.ADVANCE
            else -> BehaviorState.STANDOFF
        }

        ship.setCustomData("SCY_baseDamage", baseDamage)
        ship.setCustomData("SCY_currentState", currentState)
    }

    private fun enforceState() {
        val flagDuration = 0.05f

        ship.aiFlags.apply {
            when (currentState) {
                BehaviorState.ADVANCE -> {
                    setFlag(ShipwideAIFlags.AIFlags.HARASS_MOVE_IN, flagDuration)
                    setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, flagDuration)
                    setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF_EVEN_WHILE_VENTING, flagDuration)
                    unsetFlag(ShipwideAIFlags.AIFlags.HARASS_MOVE_IN_COOLDOWN)
                    unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF)
                    (getCustom(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE) as? Float)?.let {
                        if (it >= 2500f) unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE)
                    }
                }
                BehaviorState.STANDOFF -> {
                    if (getCloser) setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, flagDuration)
                    unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF)
                    unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE)
                    (getCustom(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE) as? Float)?.let {
                        if (it >= 2500f) unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE)
                    }
                }
                BehaviorState.BACKOFF-> {
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
                BehaviorState.VENTING ->{
                    if (!safeToVent) {
                        setFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE, flagDuration, 2500f)
                        setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, flagDuration)
                        setFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF)
                    } else {
                        setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF)
                        unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE)
                        (getCustom(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE) as? Float)?.let {
                            if (it >= 2500f) unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE)
                        }
                    }
                }
            }
        }
    }
}
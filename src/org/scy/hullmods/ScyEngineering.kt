package org.scy.hullmods

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.ShipAPI.HullSize
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.impl.campaign.ids.Personalities
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.magiclib.kotlin.getGoSlowBurnLevel
import org.magiclib.util.MagicIncompatibleHullmods
import org.scy.*
import org.scy.plugins.FlightPathPredictor
import org.scy.plugins.FlightPathPredictorManager
import org.scy.plugins.MobilityProfile

class ScyEngineering: BaseHullMod() {
    private val VENT_MULT = 3f
    private val CAP_MULT = 2f
    private val SLOW_SUPPLIES_PERCENT = -50f
    private val SLOW_PROFILE_PERCENT = -25f
    private val BURN_PROFILE_PERCENT = 25f
    private val ENGINE_HEALTH_PERCENT = 25f
    private val VENTING_BONUS = hashMapOf(
        HullSize.FIGHTER to 5f,
        HullSize.FRIGATE to 5f,
        HullSize.DESTROYER to 3f,
        HullSize.CRUISER to 2f,
        HullSize.CAPITAL_SHIP to 1f
    )

    override fun getDisplaySortOrder(): Int {
        return 0
    }

    override fun applyEffectsBeforeShipCreation(hullSize: HullSize?, stats: MutableShipStatsAPI?, id: String?) {
        if(stats == null) return
        stats.ventRateMult.modifyMult(id, VENT_MULT)
        if (hullSize != HullSize.FIGHTER) stats.fluxCapacity.modifyMult(id, CAP_MULT)
        stats.engineHealthBonus.modifyPercent(id, ENGINE_HEALTH_PERCENT)
        stats.combatEngineRepairTimeMult.modifyPercent(id, -ENGINE_HEALTH_PERCENT)
    }

    override fun applyEffectsAfterShipCreation(ship: ShipAPI?, id: String?) {
        if(ship == null) return
        for (tmp in SCY_settingsData.engineering_noncompatible) {
            if (ship.variant.hullMods.contains(tmp)) {
                MagicIncompatibleHullmods.removeHullmodWithWarning(ship.variant, tmp, "SCY_engineering")
            }
        }

        ship.mutableStats.ventRateMult.modifyPercent(id, ship.variant.numFluxCapacitors * VENTING_BONUS[ship.hullSize]!!)
    }

    override fun advanceInCampaign(member: FleetMemberAPI?, amount: Float) {
        if(member == null) return
        if(member.fleetData?.fleet?.let{ it.currBurnLevel <= it.getGoSlowBurnLevel() } == true){
            member.stats.sensorProfile.modifyPercent("scy_engineering", SLOW_PROFILE_PERCENT)
            member.stats.suppliesPerMonth.modifyPercent("scy_engineering", SLOW_SUPPLIES_PERCENT)
        } else{
            member.stats.sensorProfile.modifyPercent("scy_engineering", BURN_PROFILE_PERCENT)
            member.stats.suppliesPerMonth.unmodify("scy_engineering")
        }
    }

    override fun getDescriptionParam(index: Int, hullSize: HullSize?): String? {
        if (index == 0) return "25" + SCY_txt.txt("%")
        if (index == 1) return CAP_MULT.toString() + "x"
        if (index == 2) return VENT_MULT.toString() + "x"
        if (index == 3) return "+5/3/2/1" + SCY_txt.txt("%")

        return null
    }

    override fun getTooltipWidth(): Float {
        return 410f
    }

    override fun addPostDescriptionSection(tooltip: TooltipMakerAPI, hullSize: HullSize, ship: ShipAPI?, width: Float, isForModSpec: Boolean) {

        val imageHeight = 64f
        val headingPad = 20f
        val underHeadingPad = 10f
        val listPad = 3f

        val activeTextColor = Misc.getTextColor()
        val activeNegativeColor = Misc.getNegativeHighlightColor()
        val activeHeaderBannerColor = Misc.getDarkPlayerColor()
        val activeHeaderTextColor = brighter(Misc.getButtonTextColor(), 0.8f)
        val activeHighlightColor = Misc.getHighlightColor()

        // Scy Engines
        tooltip.addSectionHeading("Scyan Engines", activeHeaderTextColor, activeHeaderBannerColor , Alignment.MID, headingPad)
        tooltip.beginImageWithText(Global.getSettings().getSpriteName("hullmodHeaders", "SCY_engines"), imageHeight*2)
        .apply {
            setBulletedListMode("•")
            setBulletWidth(15f)
            addPara("Increases engine durability by ${ENGINE_HEALTH_PERCENT.toInt()}%%.",
                listPad, activeTextColor, activeHighlightColor, "${ENGINE_HEALTH_PERCENT.toInt()}%")
            addPara("Cuts engine repair time by ${ENGINE_HEALTH_PERCENT.toInt()}%%.",
                listPad, activeTextColor, activeHighlightColor, "${ENGINE_HEALTH_PERCENT.toInt()}%")
            addPara("Engine Jumpstart subsystem instantly reignites flamed out engines. (20 second cooldown)",
                listPad, activeTextColor, activeHighlightColor, "Engine Jumpstart")
            addPara("Incompatible with further engine modifications.",
                activeNegativeColor, listPad)
            addPara("When moving slowly:", listPad)
            setBulletedListMode("    -")
            setBulletWidth(25f)
            addPara("Reduces sensor profile by ${-SLOW_PROFILE_PERCENT.toInt()}%%.",
                1f, activeTextColor, activeHighlightColor, "${-SLOW_PROFILE_PERCENT.toInt()}%")
            addPara("Cuts maintenance costs (supplies/mo) by 50%%.",
                1f, activeTextColor, activeHighlightColor, "${-SLOW_SUPPLIES_PERCENT.toInt()}%")
            setBulletedListMode("•")
            setBulletWidth(15f)
            addPara("Otherwise:", listPad)
            setBulletedListMode("    -")
            setBulletWidth(25f)
            addPara("Increases sensor profile by ${BURN_PROFILE_PERCENT.toInt()}%%.",
                1f, activeTextColor, activeNegativeColor, "${BURN_PROFILE_PERCENT.toInt()}%")
        }
        tooltip.addImageWithText(underHeadingPad)

        // Scy Flux
        tooltip.addSectionHeading("Scyan Flux Grid", activeHeaderTextColor, activeHeaderBannerColor , Alignment.MID, headingPad)
        tooltip.beginImageWithText(Global.getSettings().getSpriteName("hullmodHeaders", "SCY_flux"), imageHeight)
        .apply {
            setBulletedListMode("•")
            setBulletWidth(15f)
            addPara("Increases flux capacity by x${CAP_MULT.toInt()} from all sources.",
                listPad, activeTextColor, activeHighlightColor, "x${CAP_MULT.toInt()}")
            addPara("Increases flux dissipation rate while actively venting by x${VENT_MULT.toInt()}. (x6 base dissipation rate)",
                listPad, activeTextColor, activeHighlightColor, "x${VENT_MULT.toInt()}", "x2", "x6")
            addPara("Further increases flux dissipation rate while actively venting by " +
                    "${VENTING_BONUS[HullSize.FRIGATE]!!.toInt()}%%/" +
                    "${VENTING_BONUS[HullSize.DESTROYER]!!.toInt()}%%/" +
                    "${VENTING_BONUS[HullSize.CRUISER]!!.toInt()}%%/" +
                    "${VENTING_BONUS[HullSize.CAPITAL_SHIP]!!.toInt()}%% per flux capacitor.",
                listPad, activeTextColor, activeHighlightColor,
                "${VENTING_BONUS[HullSize.FRIGATE]!!.toInt()}%",
                "${VENTING_BONUS[HullSize.DESTROYER]!!.toInt()}%",
                "${VENTING_BONUS[HullSize.CRUISER]!!.toInt()}%",
                "${VENTING_BONUS[HullSize.CAPITAL_SHIP]!!.toInt()}%")
        }
        tooltip.addImageWithText(underHeadingPad)
    }

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
        private var halfVentTime = 0f
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
                    val predictor = FlightPathPredictorManager.getInstance(engine)
                    evaluateState(predictor)
                } else {
                    currentState = BehaviorState.STANDOFF
                }
            }

            enforceState()

            if (ventNow && ship.fluxLevel > 0.2f && !ship.fluxTracker.isVenting) {
                halfVentTime = ship.fluxTracker.timeToVent/2
                ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
            }
        }

        private fun evaluateState(predictor: FlightPathPredictorManager) {
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
            val baseFluxGained = baseDamage.fluxToShield(currentTime, FlightPathPredictor.PREDICTION_DURATION, ship)
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
                val backoffSysFlux = sysDamageResult?.fluxToShield(currentTime, FlightPathPredictor.PREDICTION_DURATION, ship)
                    ?: noSysDamageResult?.fluxToShield(currentTime, FlightPathPredictor.PREDICTION_DURATION, ship)
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

            val backoffFlux = backoffDamage?.fluxToShield(currentTime, FlightPathPredictor.PREDICTION_DURATION, ship) ?: 0f
            val dangerTime =  ship.fluxTracker.timeToVent + timeToRaiseShields + 0.2f
            val (ventArmorDamageTaken, ventHullDamageTaken) =
                backoffDamage?.damageToArmorAndHull(currentTime, dangerTime, ship)
                    ?: Pair(Float.MAX_VALUE, Float.MAX_VALUE)
            val backoffRatio = (backoffFlux + ship.currFlux) / ship.maxFlux

            val armor = if (ship.childModulesCopy.isNotEmpty()) ship.armorGrid.armorRating * 1.5f
                        else ship.armorGrid.armorAtCell(ship.armorGrid.weakestArmorRegion()!!) ?: 0f

            val ventNowResult = backoffDamage?.compareVentingVsNotVenting(
                currentTime, dangerTime, (1-ship.fluxLevel)*ship.maxFlux, ship, startingArmor = armor)

            ventNow = (ventArmorDamageTaken < (ship.armorGrid.armorRating * if (ship.childModulesCopy.isNotEmpty()) 1.5f else 1f)/10
                    && ventHullDamageTaken < ship.maxHitpoints/50)
                    || ventNowResult?.isVentingSafer == true

            currentState = when {
                ship.fluxTracker.isVenting -> BehaviorState.VENTING
                ship.fluxLevel > backoffLevel || (currentState == BehaviorState.DISENGAGE && ship.fluxLevel > ventLevel) -> BehaviorState.DISENGAGE
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
                    BehaviorState.BACKOFF-> {
                        setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, flagDuration)
                        setFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF)
                    }
                    BehaviorState.VENTING ->{
                        if (!ventNow) {
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
                    BehaviorState.DISENGAGE -> {
                        setFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE, flagDuration, 2500f)
                        setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, flagDuration)
                        setFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF)
                    }
                    BehaviorState.STANDOFF -> {
                        if (getCloser) setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF)
                        unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE)
                    }
                }
            }
        }
    }
}
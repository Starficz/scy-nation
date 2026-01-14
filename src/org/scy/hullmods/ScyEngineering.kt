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
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.combat.AIUtils
import org.lwjgl.util.vector.Vector2f
import org.magiclib.kotlin.getGoSlowBurnLevel
import org.magiclib.subsystems.MagicSubsystemsManager.addSubsystemToShip
import org.magiclib.util.MagicIncompatibleHullmods
import org.scy.*
import org.scy.plugins.DamageProfile
import org.scy.plugins.FlightPathPredictorManager
import org.scy.subsystems.EngineJumpstart

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

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize?, stats: MutableShipStatsAPI?, id: String?) {
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
        if (ship.hullSize != HullSize.FIGHTER && ship.parentStation == null) {
            addSubsystemToShip(ship, EngineJumpstart(ship))
            if (!ship.hasListenerOfClass(ScyAiV2::class.java)) ship.addListener(ScyAiV2(ship))
        }

        // set the custom flag to enable DamagePredictor
        Global.getCombatEngine().customData["NeedsDamagePredictor"] = true
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

        val HEIGHT = 64f
        val headingPad = 20f
        val underHeadingPad = 10f
        val listPad = 3f

        val activeTextColor = Misc.getTextColor()
        val activeNegativeColor = Misc.getNegativeHighlightColor()
        val activeHeaderBannerColor = Misc.getDarkPlayerColor()
        val activeHeaderTextColor = brighter(Misc.getButtonTextColor(), 0.8f)
        val activeHighlightColor = Misc.getHighlightColor()

        tooltip.addSectionHeading("Scyan Engines", activeHeaderTextColor, activeHeaderBannerColor , Alignment.MID, headingPad)
        val scyEngines = tooltip.beginImageWithText(Global.getSettings().getSpriteName("hullmodHeaders", "SCY_engines"), HEIGHT*2)
        scyEngines.setBulletedListMode("•")
        scyEngines.setBulletWidth(15f)
        scyEngines.addPara("Increases engine durability by ${ENGINE_HEALTH_PERCENT.toInt()}%%.",
            listPad, activeTextColor, activeHighlightColor, "${ENGINE_HEALTH_PERCENT.toInt()}%")
        scyEngines.addPara("Cuts engine repair time by ${ENGINE_HEALTH_PERCENT.toInt()}%%.",
            listPad, activeTextColor, activeHighlightColor, "${ENGINE_HEALTH_PERCENT.toInt()}%")
        scyEngines.addPara("Engine Jumpstart subsystem instantly reignites flamed out engines. (20 second cooldown)",
            listPad, activeTextColor, activeHighlightColor, "Engine Jumpstart")
        scyEngines.addPara("Incompatible with further engine modifications.",
            activeNegativeColor, listPad)
        scyEngines.addPara("When moving slowly:", listPad)
        scyEngines.setBulletedListMode("    -")
        scyEngines.setBulletWidth(25f)
        scyEngines.addPara("Reduces sensor profile by ${-SLOW_PROFILE_PERCENT.toInt()}%%.",
            1f, activeTextColor, activeHighlightColor, "${-SLOW_PROFILE_PERCENT.toInt()}%")
        scyEngines.addPara("Cuts maintenance costs (supplies/mo) by 50%%.",
            1f, activeTextColor, activeHighlightColor, "${-SLOW_SUPPLIES_PERCENT.toInt()}%")
        scyEngines.setBulletedListMode("•")
        scyEngines.setBulletWidth(15f)
        scyEngines.addPara("Otherwise:", listPad)
        scyEngines.setBulletedListMode("    -")
        scyEngines.setBulletWidth(25f)
        scyEngines.addPara("Increases sensor profile by ${BURN_PROFILE_PERCENT.toInt()}%%.",
            1f, activeTextColor, activeNegativeColor, "${BURN_PROFILE_PERCENT.toInt()}%")
        tooltip.addImageWithText(underHeadingPad)
        //scyEngines.position.setXAlignOffset(-5f)

        tooltip.addSectionHeading("Scyan Flux Grid", activeHeaderTextColor, activeHeaderBannerColor , Alignment.MID, headingPad)
        val scyFluxGrid = tooltip.beginImageWithText(Global.getSettings().getSpriteName("hullmodHeaders", "SCY_flux"), HEIGHT)
        scyFluxGrid.setBulletedListMode("•")
        scyFluxGrid.setBulletWidth(15f)
        scyFluxGrid.addPara("Increases flux capacity by x${CAP_MULT.toInt()} from all sources.",
            listPad, activeTextColor, activeHighlightColor, "x${CAP_MULT.toInt()}")
        scyFluxGrid.addPara("Increases flux dissipation rate while actively venting by x${VENT_MULT.toInt()}. (x6 base dissipation rate)",
            listPad, activeTextColor, activeHighlightColor, "x${VENT_MULT.toInt()}", "x2", "x6")
        scyFluxGrid.addPara("Further increases flux dissipation rate while actively venting by " +
                "${VENTING_BONUS[HullSize.FRIGATE]!!.toInt()}%%/" +
                "${VENTING_BONUS[HullSize.DESTROYER]!!.toInt()}%%/" +
                "${VENTING_BONUS[HullSize.CRUISER]!!.toInt()}%%/" +
                "${VENTING_BONUS[HullSize.CAPITAL_SHIP]!!.toInt()}%% per flux capacitor.",
            listPad, activeTextColor, activeHighlightColor,
            "${VENTING_BONUS[HullSize.FRIGATE]!!.toInt()}%",
            "${VENTING_BONUS[HullSize.DESTROYER]!!.toInt()}%",
            "${VENTING_BONUS[HullSize.CRUISER]!!.toInt()}%",
            "${VENTING_BONUS[HullSize.CAPITAL_SHIP]!!.toInt()}%")
        tooltip.addImageWithText(underHeadingPad)
        //scyFluxGrid.position.setXAlignOffset(-5f)
    }

    class ScyAiV2(val ship: ShipAPI) : AdvanceableListener {
        var backupPoint: Vector2f? = null
        private val interval = IntervalUtil(0.05f, 0.1f)

        private val baseLevel: Float
        private val backoffLevel: Float

        // Internal AI States
        private var shouldHarass = false
        private var shouldBackoff = false
        private var shouldVent = false

        init {
            val (base, backoff) = when (ship.captain?.personalityAPI?.id) {
                Personalities.TIMID -> 0.2f to 0.4f
                Personalities.CAUTIOUS -> 0.4f to 0.6f
                Personalities.STEADY -> 0.6f to 0.8f
                Personalities.AGGRESSIVE -> 0.7f to 0.9f
                Personalities.RECKLESS -> 0.8f to 1.0f
                else -> 0.6f to 0.8f
            }
            baseLevel = base
            backoffLevel = backoff
        }

        override fun advance(amount: Float) {
            interval.advance(amount)

            if (interval.intervalElapsed()) {
                val engine = Global.getCombatEngine()
                if (engine != null) {
                    val predictor = FlightPathPredictorManager.getInstance(engine)
                    evaluateCurrentPosition(predictor)
                    evaluateRetreatPosition(predictor)
                } else {
                    shouldHarass = false
                    shouldBackoff = false
                }
            }

            enforceFlags(amount)

            if (shouldVent) {
                ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
                shouldVent = false // One-shot trigger
            }
        }

        private fun evaluateCurrentPosition(predictor: FlightPathPredictorManager) {
            predictor.queueRequest(ship, Misc.ZERO)
            val baseDamage = predictor.getResult(ship, Misc.ZERO)

            // Fix: If we don't have a result yet, DO NOT drop states.
            // Just return early and let the old state persist until the background sim catches up!
            if (baseDamage == null) return

            val baseFluxGained = calculateShieldFlux(baseDamage)
            ship.setCustomData("baseFluxGained", baseFluxGained)

            val fluxRatio = (baseFluxGained + ship.currFlux) / ship.maxFlux

            shouldHarass = fluxRatio < baseLevel
            shouldVent = isSafeToVent(baseFluxGained)
        }

        private fun evaluateRetreatPosition(predictor: FlightPathPredictorManager) {
            val safePoint = StarficzAIUtils.getBackingOffStrafePoint(ship)
            if (safePoint == null) {
                shouldBackoff = false
                return
            }

            backupPoint = safePoint
            val backupVector = VectorUtils.getDirectionalVector(ship.location, safePoint)
            predictor.queueRequest(ship, backupVector)

            val runawayDamage = predictor.getResult(ship, backupVector)

            // Fix: Do not instantly set backoff to false while simulating
            if (runawayDamage == null) return

            val backoffFluxGained = calculateShieldFlux(runawayDamage)
            val fluxRatio = (backoffFluxGained + ship.currFlux) / ship.maxFlux

            shouldBackoff = fluxRatio > backoffLevel
        }

        private fun enforceFlags(amount: Float) {
            val flagDuration = 0.1f

            ship.aiFlags.apply {
                if (shouldHarass) {
                    setFlag(ShipwideAIFlags.AIFlags.HARASS_MOVE_IN, flagDuration)
                    setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, flagDuration)
                    setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF_EVEN_WHILE_VENTING, flagDuration)
                    unsetFlag(ShipwideAIFlags.AIFlags.HARASS_MOVE_IN_COOLDOWN)
                    unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF)
                }

                if (shouldBackoff) {
                    setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, flagDuration)
                    setFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE, flagDuration)
                    unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF)
                }
            }
        }

        private fun calculateShieldFlux(damage: DamageProfile): Float {
            return fluxToShield(DamageType.ENERGY, damage.energy, ship) +
                    fluxToShield(DamageType.KINETIC, damage.kinetic, ship) +
                    fluxToShield(DamageType.HIGH_EXPLOSIVE, damage.highExplosive, ship) +
                    fluxToShield(DamageType.FRAGMENTATION, damage.fragmentation, ship)
        }

        private fun isSafeToVent(baseFluxGained: Float): Boolean {
            val personality = ship.captain?.personalityAPI?.id
            val isTimidOrCautious = personality == Personalities.TIMID || personality == Personalities.CAUTIOUS

            return baseFluxGained < 0.05f &&
                    ship.fluxLevel > 0.5f &&
                    !isTimidOrCautious &&
                    AIUtils.getNearbyEnemyMissiles(ship, 1000f).isEmpty()
        }
    }
}
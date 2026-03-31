package org.scy.hullmods

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.ShipAPI.HullSize
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import org.magiclib.kotlin.getGoSlowBurnLevel
import org.magiclib.subsystems.MagicSubsystemsManager.addSubsystemToShip
import org.magiclib.util.MagicIncompatibleHullmods
import org.scy.*
import org.scy.combatai.ScyAiV2
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

    override fun applyEffectsAfterShipAddedToCombatEngine(ship: ShipAPI, id: String?) {
        // engine jumpstart and custom SCY ai
        if (ship.hullSize != HullSize.FIGHTER && ship.parentStation == null) {
            addSubsystemToShip(ship, EngineJumpstart(ship))
            if (!ship.hasListenerOfClass(ScyAiV2::class.java)) ship.addListener(ScyAiV2(ship))
        }
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
}
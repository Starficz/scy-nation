package org.scy.hullmods

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.ShipAPI.HullSize
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.combat.CombatEngine
import org.lwjgl.util.vector.Vector2f
import org.magiclib.kotlin.getGoSlowBurnLevel
import org.magiclib.subsystems.MagicSubsystemsManager.addSubsystemToShip
import org.magiclib.util.MagicIncompatibleHullmods
import org.scy.*
import org.scy.StarficzAIUtils.FutureHit
import org.scy.subsystems.EngineJumpstart
import kotlin.math.max

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
        if (ship.hullSize != HullSize.FIGHTER) {
            addSubsystemToShip(ship, EngineJumpstart(ship))
            if (!ship.hasListenerOfClass(ScyVentingAI::class.java)) ship.addListener(ScyVentingAI(ship))
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

    class ScyVentingAI(val ship: ShipAPI): AdvanceableListener{
        var damageTracker: IntervalUtil = IntervalUtil(0.2f, 0.3f)
        var backupPoint: Vector2f? = null
        var lastUpdatedTime: Float = 0f
        var incomingProjectiles: List<FutureHit> = ArrayList()
        var predictedWeaponHits: List<FutureHit> = ArrayList()

        val bufferTime = 0.1f
        val forceBackOffLimit = 0.8f
        override fun advance(amount: Float) {
            val engine = Global.getCombatEngine()
            if (!ship.isAlive || ship.parentStation != null || engine == null || !engine.isEntityInPlay(ship)) return
            // Calculate Decision Flags
            val shieldRaiseTime = (ship.shield?.let{ it.unfoldTime / (it.arc/90f) } ?: 0f) + bufferTime // 0.1 sec buffer at min

            damageTracker.advance(amount)
            if (damageTracker.intervalElapsed()) {
                lastUpdatedTime = Global.getCombatEngine().getTotalElapsedTime(false)
                incomingProjectiles = StarficzAIUtils.incomingProjectileHits(ship, ship.location)
                val timeToPredict = ship.fluxTracker.timeToVent + shieldRaiseTime + damageTracker.maxInterval
                predictedWeaponHits = StarficzAIUtils.generatePredictedWeaponHits(ship, ship.location, timeToPredict)
                if (ship.fluxLevel > forceBackOffLimit - 0.1f) backupPoint = StarficzAIUtils.getBackingOffStrafePoint(ship)
            }


            // calculate how much damage the ship would take if vent
            val currentTime = Global.getCombatEngine().getTotalElapsedTime(false)
            val timeElapsed = currentTime - lastUpdatedTime

            val armorBase = ship.armorGrid.armorAtCell(ship.armorGrid.weakestArmorRegion()!!)!!
            val armorMax = ship.armorGrid.armorRating
            val armorMinLevel = ship.mutableStats.minArmorFraction.modifiedValue
            var armorVent = armorBase

            var hullDamageIfVent = 0f
            var empDamageIfVent = 0f

            for (hit in (incomingProjectiles + predictedWeaponHits)) {
                val timeToHit = (hit.timeToHit - timeElapsed)
                if (timeToHit < -bufferTime) continue  // skip hits that have already happened

                if (timeToHit < ship.fluxTracker.timeToVent + shieldRaiseTime) {
                    val trueDamage = org.scy.damageAfterArmor(hit.damageType, hit.damage, hit.hitStrength, armorVent, ship)
                    armorVent = max(armorVent - trueDamage.first, armorMinLevel * armorMax)
                    hullDamageIfVent += trueDamage.second
                    empDamageIfVent += hit.empDamage
                }
            }

            val armorDamageLevelVent = (armorBase - armorVent) / armorMax
            val hullDamageLevelVent = hullDamageIfVent / (ship.hitpoints * ship.hullLevel)
            val empDamageLevelVent = empDamageIfVent / ship.allWeapons.sumOf { it.currHealth.toDouble() }.toFloat()

            val variant = Global.getSettings().getVariant(ship.hullSpec.baseHullId + "_combat")
            val numModules = variant?.stationModules?.size ?: 0
            val aliveLevel = ship.childModulesCopy.count { it.hitpoints > 0 } / numModules.toFloat()
            val damageRiskMult = Misc.interpolate(1f, 5f, if (numModules > 0) aliveLevel else 0f)


            if (!engine.isUIAutopilotOn || engine.playerShip !== ship) {
                // don't back off if not in danger
                if (armorDamageLevelVent < 0.01f && hullDamageLevelVent < 0.01f && empDamageLevelVent < 0.5f) {
                    ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, 0.05f)
                    ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF_EVEN_WHILE_VENTING, 0.05f)
                }

                // vent control
                if (ship.fluxLevel > 0.2f &&
                    armorDamageLevelVent < (0.03f * damageRiskMult) &&
                    hullDamageLevelVent < (0.03f * damageRiskMult) &&
                    empDamageLevelVent < (0.5f * damageRiskMult))
                {
                    ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
                }
                else {
                    ship.blockCommandForOneFrame(ShipCommand.VENT_FLUX)
                    // force back off
                    if (ship.fluxLevel > forceBackOffLimit && backupPoint != null && !ship.fluxTracker.isVenting) {
                        // make sure ship is not being commanded to eliminate some target
                        val taskManager = (engine as CombatEngine).getTaskManager(ship.owner, ship.isAlly)
                        if (taskManager?.getAssignmentFor(ship) == null || taskManager.getAssignmentFor(ship).type != CombatAssignmentType.INTERCEPT) {
                            StarficzAIUtils.strafeToPointV2(ship, backupPoint)
                        }
                    }
                }
            }
        }
    }
}
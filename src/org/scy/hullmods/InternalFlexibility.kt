package org.scy.hullmods

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.loading.WeaponSlotAPI
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import org.scy.brighter
import org.starficz.ReflectionUtils.getFieldsMatching
import org.starficz.ReflectionUtils.getMethodsMatching
import org.starficz.ReflectionUtils.invoke

class InternalFlexibility : BaseHullMod() {
    override fun addPostDescriptionSection(
        tooltip: TooltipMakerAPI,
        hullSize: ShipAPI.HullSize?,
        ship: ShipAPI?,
        width: Float,
        isForModSpec: Boolean
    ) {
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
        tooltip.addSectionHeading("Oversized Turret Gyros", activeHeaderTextColor, activeHeaderBannerColor , Alignment.MID, headingPad)
        tooltip.beginImageWithText(Global.getSettings().getSpriteName("hullmodHeaders", "SCY_flux"), imageHeight)
            .apply {
                addPara("By default, much of the internals are taken up by massive turret gyros.", listPad)
                addPara("Without a %s, increases the turn rate of all turrets by %s.",
                    listPad, activeTextColor, activeHighlightColor, "Converted Hanger", "2x")
            }
        tooltip.addImageWithText(underHeadingPad)

        tooltip.addSectionHeading("Expanded Hangers", activeHeaderTextColor, activeHeaderBannerColor , Alignment.MID, headingPad)
        tooltip.beginImageWithText(Global.getSettings().getSpriteName("hullmodHeaders", "SCY_flux"), imageHeight)
            .apply {
                addPara("Swapping gyros for extra nanoforges allows a %s to be installed despite the existing fighter bay, increasing the total fighter bays to %s while negating most downsides.", listPad, activeTextColor, activeHighlightColor, "Converted Hangar", "2")
            }
        tooltip.addImageWithText(underHeadingPad)
    }

    override fun getTooltipWidth(): Float {
        return 390f
    }

    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {

        // converted hanger allowed for +1 bay with minimal downside
        stats.dynamic.getMod(Stats.FORCE_ALLOW_CONVERTED_HANGAR).modifyFlat(id, 1f)
        stats.dynamic.getMod(Stats.CONVERTED_HANGAR_NO_CREW_INCREASE).modifyFlat(id, 1f)
        stats.dynamic.getMod(Stats.CONVERTED_HANGAR_NO_REARM_INCREASE).modifyFlat(id, 1f)
        stats.dynamic.getMod(Stats.CONVERTED_HANGAR_NO_REFIT_PENALTY).modifyFlat(id, 1f)
    }


    override fun applyEffectsAfterShipAddedToCombatEngine(ship: ShipAPI, id: String?) {

        // if converted hanger is installed, no super gyros
        if ("converted_hangar" !in ship.variant.hullMods) {
            ship.mutableStats.weaponTurnRateBonus.modifyMult(id, 2f)
            ship.mutableStats.beamWeaponTurnRateBonus.modifyMult(id, 2f)
        }

        // changing weapon mount arcs sure is complicated
        if (ship.hullSpec.hullId == "SCY_orthrus") {

            val originalVariant = ship.variant ?: return
            val originalHullSpec = ship.hullSpec ?: return

            // Clone the Variant
            val cloneVariantMethod = originalVariant.getMethodsMatching(
                name = "clone",
                returnType = ShipVariantAPI::class.java,
                numOfParams = 0
            ).firstOrNull() ?: return

            val clonedVariant = originalVariant.invoke(cloneVariantMethod) as ShipVariantAPI

            // Clone the HullSpec
            val cloneHullMethod = originalHullSpec.getMethodsMatching(
                name = "clone",
                returnType = ShipHullSpecAPI::class.java,
                numOfParams = 0
            ).firstOrNull() ?: return

            val clonedHullSpec = originalHullSpec.invoke(cloneHullMethod) as ShipHullSpecAPI

            // Inject the cloned HullSpec into our isolated variant
            clonedVariant.invoke("setHullSpec", clonedHullSpec)

            // Inject the isolated variant back into the Ship instance
            val variantFields = ship.getFieldsMatching(
                fieldAccepts = originalVariant::class.java,
                searchSuperclass = true
            )
            val specField = variantFields.firstOrNull { it.get(ship) === originalVariant }
            specField?.set(ship, clonedVariant)

            // Update the instantiated Weapon objects to point to the newly cloned slots
            ship.allWeapons.forEach { w ->
                val originalSlot = w.slot

                val newSlot = clonedHullSpec.getWeaponSlotAPI(originalSlot.id) ?: return@forEach

                w.getFieldsMatching(fieldAssignableTo = WeaponSlotAPI::class.java, searchSuperclass = true)
                    .filter { it.get(w) === originalSlot }
                    .forEach { it.set(w, newSlot) }
            }
        }
    }
}
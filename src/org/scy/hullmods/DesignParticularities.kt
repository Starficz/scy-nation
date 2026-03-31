package org.scy.hullmods

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.loading.WeaponSlotAPI
import org.scy.ReflectionUtils.getFieldsMatching
import org.scy.ReflectionUtils.getMethodsMatching
import org.scy.ReflectionUtils.invoke

class DesignParticularities : BaseHullMod() {
    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.dynamic.getMod(Stats.FORCE_ALLOW_CONVERTED_HANGAR).modifyFlat(id, 1f)
        stats.dynamic.getMod(Stats.CONVERTED_HANGAR_NO_CREW_INCREASE).modifyFlat(id, 1f)
        stats.dynamic.getMod(Stats.CONVERTED_HANGAR_NO_REARM_INCREASE).modifyFlat(id, 1f)
        stats.dynamic.getMod(Stats.CONVERTED_HANGAR_NO_REFIT_PENALTY).modifyFlat(id, 1f)
        stats.weaponTurnRateBonus.modifyMult(id, 2f)
        stats.beamWeaponTurnRateBonus.modifyMult(id, 2f)
    }

    override fun applyEffectsAfterShipAddedToCombatEngine(ship: ShipAPI, id: String?) {
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
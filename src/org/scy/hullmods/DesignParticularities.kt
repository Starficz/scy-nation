package org.scy.hullmods

import com.fs.starfarer.api.combat.BaseHullMod
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats

class DesignParticularities : BaseHullMod() {
    override fun applyEffectsBeforeShipCreation(hullSize: ShipAPI.HullSize, stats: MutableShipStatsAPI, id: String) {
        stats.dynamic.getMod(Stats.FORCE_ALLOW_CONVERTED_HANGAR).modifyFlat(id, 1f)
        stats.dynamic.getMod(Stats.CONVERTED_HANGAR_NO_CREW_INCREASE).modifyFlat(id, 1f)
        stats.dynamic.getMod(Stats.CONVERTED_HANGAR_NO_REARM_INCREASE).modifyFlat(id, 1f)
        stats.dynamic.getMod(Stats.CONVERTED_HANGAR_NO_REFIT_PENALTY).modifyFlat(id, 1f)
        stats.weaponTurnRateBonus.modifyMult(id, 2f)
        stats.beamWeaponTurnRateBonus.modifyMult(id, 2f)
    }
}
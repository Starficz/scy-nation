package org.scy.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

public class SCY_armorChild extends BaseHullMod {

  private final String id = "SCY_childModule";

  @Override
  public void advanceInCombat(ShipAPI ship, float amount) {

    if (!ship.isAlive()) return;
    ship.setDrone(true);
  }

  @Override
  public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
    if(!ship.hasListenerOfClass(ExplosionOcclusionRaycast.class)) ship.addListener(new ExplosionOcclusionRaycast());

  }

  @Override
  public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
    stats.getDynamic().getStat(Stats.EXPLOSION_RADIUS_MULT).modifyMult(id, 0.1f);
    stats.getDynamic().getStat(Stats.EXPLOSION_DAMAGE_MULT).modifyMult(id, 0.1f);
  }
}

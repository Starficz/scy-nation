package org.scy.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.Misc;

public class SCY_twinShieldSystem implements EveryFrameWeaponEffectPlugin {

  private ShipAPI ship;
  private ShipSystemAPI system;
  private float shieldArc = 0, time = 0;
  private boolean bonus = false, runOnce = false;

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
    if (engine.isPaused()) {
      return;
    }

    if (!runOnce) {
      runOnce = true;
      ship = weapon.getShip();
      system = ship.getSystem();
      shieldArc = ship.getShield().getArc();
    }

    time += amount;
    if(!system.isActive()) shieldArc = ship.getShield().getArc();

    if (time >= 1 / 30f) {
      time -= 1 / 30f;
      if (system.isActive()) {
        bonus = true;
        float level = system.getEffectLevel();
        ship.getShield().setArc(Misc.interpolate(shieldArc, shieldArc/2, level));
      } else if (bonus) {
        bonus = false;
        ship.getShield().setArc(shieldArc);
      }
    }
  }
}

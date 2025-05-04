package org.scy.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

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

    if (time >= 1 / 30f) {
      time -= 1 / 30f;
      if (system.isActive()) {
        bonus = true;
        float level = system.getEffectLevel();
        ship.getShield().setArc(shieldArc - (level * 2 * shieldArc / 3));
      } else if (bonus) {
        bonus = false;
        ship.getShield().setArc(shieldArc);
      }
    }
  }
}

// By Tartiflette

package org.scy.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

public class SCY_dishEffect implements EveryFrameWeaponEffectPlugin {
  private boolean rotate = true;
  private boolean runOnce = false;

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
    if (engine.isPaused() || !rotate) {
      return;
    }

    if (!runOnce) {
      runOnce = true;
      if (weapon.getShip().getOriginalOwner() != -1 && weapon.getShip().getOwner() != -1) {
        weapon.setCurrAngle((float) Math.random() * 180);
      }
      return;
    }
    if (!weapon.getShip().isAlive()) {
      rotate = false;
      return;
    }
    weapon.setCurrAngle(weapon.getCurrAngle() + amount * 25);
  }
}

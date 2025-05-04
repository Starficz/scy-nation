package org.scy.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.lazywizard.lazylib.MathUtils;

public class SCY_rotatingDishEffect implements EveryFrameWeaponEffectPlugin {

    private float current = MathUtils.getRandomNumberInRange(0f, 180f);

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

    if (engine.isPaused()
        || weapon.getShip().isHulk()
        || weapon.getShip().getOriginalOwner() == -1) {
      return;
    }

      float SPEED = -30;
      current = MathUtils.clampAngle(current + SPEED * amount);
    weapon.setCurrAngle(current);
  }
}

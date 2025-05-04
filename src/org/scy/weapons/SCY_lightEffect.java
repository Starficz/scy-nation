// by Tartiflette
package org.scy.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import java.awt.Color;

public class SCY_lightEffect implements EveryFrameWeaponEffectPlugin {

  private Integer lightOn = 0;
  private final IntervalUtil timer = new IntervalUtil(0.75f, 1.25f);

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

    if (engine.isPaused() || lightOn > 1) {
      return;
    }

    timer.advance(amount);
    if (timer.intervalElapsed()) {
      if (!weapon.getShip().isAlive()) {
        weapon.getSprite().setColor(new Color(0, 0, 0, 0));
        lightOn++;
      }
    }
  }
}

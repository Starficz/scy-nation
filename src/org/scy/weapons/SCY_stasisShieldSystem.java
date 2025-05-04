package org.scy.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import java.awt.Color;

public class SCY_stasisShieldSystem implements EveryFrameWeaponEffectPlugin {

  private boolean lightOn = true;
  private ShipAPI ship;
  private ShipSystemAPI system;
  private float shieldArc = 0;
  private final IntervalUtil timer = new IntervalUtil(0.05f, 0.05f);
  private boolean bonus = false, runOnce = false;

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
    if (engine.isPaused() || !lightOn || weapon.getShip().getShield() == null) {
      return;
    }

    if (!runOnce) {
      runOnce = true;
      ship = weapon.getShip();
      system = ship.getSystem();
      shieldArc = ship.getShield().getArc();
      if (ship.getOriginalOwner() == -1) {
        weapon.getAnimation().setFrame(0);
        lightOn = false;
        return;
      }
      timer.randomize();
    }

    timer.advance(amount);
    if (timer.intervalElapsed()) {
      if (system.isActive()) {
        float level = system.getEffectLevel();
        ship.getShield().setArc(Math.min(360, shieldArc + (360 * level)));
        weapon.getAnimation().setFrame(1);
        weapon.getSprite().setColor(new Color(1,1,1, (float) Math.min(1, Math.max(0, level * 0.75f + 0.25 * Math.random()))));
      }
    }
    if (system.isActive()) {
      bonus = true;
      float level = system.getEffectLevel();
      ship.getShield().setArc(Math.min(360, shieldArc + (360 * level)));
    } else if (bonus) {
      bonus = false;
      ship.getShield().setArc(shieldArc);
      weapon.getAnimation().setFrame(0);
    }
  }
}

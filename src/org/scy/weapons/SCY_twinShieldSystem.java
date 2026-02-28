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
  private float shieldArc = 0, chargedownTime = 0;
  private boolean runOnce = false;

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
    if (engine.isPaused() || !engine.isEntityInPlay(weapon.getShip())) return;


    if (!runOnce) {
      ship = weapon.getShip();
      system = ship.getSystem();
      if(ship == null || system == null || ship.getShield() == null) return;
      shieldArc = ship.getShield().getArc();
      chargedownTime = system.getChargeDownDur();
      runOnce = true;
    }

    if(system.isActive()){
      ship.getShield().setArc(Misc.interpolate(shieldArc, shieldArc/2, system.getEffectLevel()));
      chargedownTime = Misc.interpolate(0, system.getChargeDownDur(), system.getEffectLevel());
    } else{
      if(chargedownTime > 0){
        chargedownTime -= amount;
        ship.getShield().setArc(Misc.interpolate(shieldArc, shieldArc/2, chargedownTime/system.getChargeDownDur()));
      } else {
        shieldArc = ship.getShield().getArc();
      }
    }
  }
}

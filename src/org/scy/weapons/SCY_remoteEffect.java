// by Tartiflette, Experimentation on "remote controlled" missiles
package org.scy.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.lazywizard.lazylib.MathUtils;

public class SCY_remoteEffect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

  private boolean runOnce = false;
  private final List<MissileAPI> CONTROLLED = new ArrayList<>();
  private final IntervalUtil timer = new IntervalUtil(0.05f, 0.15f);
  private boolean FRM = false, reset = false;
  private int charges = 0, maxCharges = 0;
  private ShipSystemAPI system;

  @Override
  public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
    CONTROLLED.add((MissileAPI) projectile);
  }

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

    // Don't bother with any checks if the game is paused
    if (engine.isPaused()) {
      return;
    }

    if (!runOnce) {
      runOnce = true;
      CONTROLLED.clear();
      system = weapon.getShip().getSystem();
      if (system != null && system.getId().equals("fastmissileracks")) {
        maxCharges = system.getAmmo();
        for (WeaponAPI w : weapon.getShip().getAllWeapons()) {
          // only lock the Fast Missile Rack if there is no other types of missiles
          if (w.getSpec().getType() == WeaponAPI.WeaponType.MISSILE
              && w.getId().startsWith("SCY_coasting")) {
            FRM = false;
            break;
          } else {
            FRM = true;
          }
        }
      }
      timer.randomize();
    }

    if (CONTROLLED.size() > 1) {
      // Fast Missile Rack Hack
      if (FRM) {
        if (!reset) {
          reset = true;
          system.setAmmo(0);
        }
        if (system.getAmmo() > 0) {
          charges = Math.min(maxCharges, charges++);
          system.setAmmo(0);
        }
      }

      // remove dead or drifting missiles
      Iterator<MissileAPI> iter = CONTROLLED.iterator();
      while (iter.hasNext()) {
        MissileAPI m = iter.next();
        if (m.isFading() || m.isFizzling() || !Global.getCombatEngine().isEntityInPlay(m)) {
          iter.remove();
        }
      }

      // lock the weapon
      weapon.setRemainingCooldownTo(Math.max(0.1f, weapon.getCooldownRemaining()));
      // visual cue
      timer.advance(amount);
      if (timer.intervalElapsed() && MagicRender.screenCheck(0.1f, weapon.getLocation())) {
        engine.addHitParticle(
            weapon.getLocation(),
            weapon.getShip().getVelocity(),
            MathUtils.getRandomNumberInRange(30, 50),
            0.5f,
            MathUtils.getRandomNumberInRange(0.1f, 0.2f),
            Color.red);
      }
    } else {
      // FRM restore
      if (reset) {
        reset = false;
        system.setAmmo(charges);
      }
      if (FRM) {
        charges = system.getAmmo();
      }
    }
  }
}

package org.scy.weapons;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_hammerEffect implements OnHitEffectPlugin {

  @Override
  public void onHit(
      DamagingProjectileAPI projectile,
      CombatEntityAPI target,
      Vector2f point,
      boolean shieldHit,
      ApplyDamageResultAPI damageResult,
      CombatEngineAPI engine) {
    //        Vector2f to = target.getLocation();
    if (target instanceof ShipAPI && ((ShipAPI) target).getParentStation() != null) {
      target = ((ShipAPI) target).getParentStation();
    }
    CombatUtils.applyForce(target, projectile.getFacing(), 500);
  }
}

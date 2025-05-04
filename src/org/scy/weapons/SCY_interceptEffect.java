package org.scy.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.util.vector.Vector2f;

public class SCY_interceptEffect implements OnHitEffectPlugin {

  private final Map<HullSize, Float> PUSH = new HashMap<>();

  {
    PUSH.put(HullSize.FIGHTER, 0.1f);
    PUSH.put(HullSize.FRIGATE, 0.05f);
    PUSH.put(HullSize.DESTROYER, 0.01f);
    PUSH.put(HullSize.CRUISER, 0f);
    PUSH.put(HullSize.CAPITAL_SHIP, 0f);
  }

  @Override
  public void onHit(
      DamagingProjectileAPI projectile,
      CombatEntityAPI target,
      Vector2f point,
      boolean shieldHit,
      ApplyDamageResultAPI damageResult,
      CombatEngineAPI engine) {
    float mult = 0.05f;

    if (target instanceof MissileAPI) {
      mult = 0.2f;
    } else if (target instanceof ShipAPI) {
      if (((ShipAPI) target).getParentStation() != null) {
        target = ((ShipAPI) target).getParentStation();
      }
      mult = PUSH.get(((ShipAPI) target).getHullSize());
    }

    if (mult > 0) {
      Vector2f vel = new Vector2f(projectile.getVelocity());
      vel.scale(mult);
      Vector2f.add(vel, target.getVelocity(), target.getVelocity());
    }
  }
}

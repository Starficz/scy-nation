package org.scy.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_flakEffect implements OnHitEffectPlugin {

  private final Color EXPLOSION_COLOR = new Color(255, 0, 0, 255);
  private final Color PARTICLE_COLOR = new Color(240, 200, 50, 255);

    @Override
  public void onHit(
      DamagingProjectileAPI projectile,
      CombatEntityAPI target,
      Vector2f point,
      boolean shieldHit,
      ApplyDamageResultAPI damageResult,
      CombatEngineAPI engine) {
    if (MagicRender.screenCheck(0.2f, point)) {
      engine.addHitParticle(point, new Vector2f(), 100, 1, 0.25f, EXPLOSION_COLOR);
        int NUM_PARTICLES = 20;
        for (int i = 0; i < NUM_PARTICLES; i++) {
        float axis = (float) Math.random() * 360;
        float range = (float) Math.random() * 100;
        engine.addHitParticle(
            MathUtils.getPoint(point, range / 5, axis),
            MathUtils.getPoint(new Vector2f(), range, axis),
            2 + (float) Math.random() * 2,
            1,
            1 + (float) Math.random(),
            PARTICLE_COLOR);
      }
    }
  }
}

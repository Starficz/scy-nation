// add extra damage to the ORION artillery's shell depending on the current split stage
package org.scy.weapons;

// import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
// import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_orionEffect implements OnHitEffectPlugin {

    @Override
  public void onHit(
      DamagingProjectileAPI projectile,
      CombatEntityAPI target,
      Vector2f point,
      boolean shieldHit,
      ApplyDamageResultAPI damageResult,
      CombatEngineAPI engine) {

        if (MagicRender.screenCheck(0.1f, point)) {
        float damage = 0;
        for (int i = 0; i < damage / 50; i++) {
        engine.addHitParticle(
            point,
            MathUtils.getPoint(
                new Vector2f(), i * 40, projectile.getFacing() - 1 + (float) (Math.random() * 2)),
            4 + 4 * (20 - i),
            0.5f,
            0.2f + (float) (Math.random() / 4),
            new Color(150 - 6 * (20 - i), 100, 200));
      }

      for (int i = 0; i < 8; i++) {
        engine.addHitParticle(
            point,
            MathUtils.getRandomPointInCone(
                new Vector2f(), 100, projectile.getFacing() + 135, projectile.getFacing() + 225),
            5 + 5 * (float) Math.random(),
            1f,
            1f + (float) Math.random(),
            new Color(250, 100, 50, 150));
      }
    }

    // debug
    }
}

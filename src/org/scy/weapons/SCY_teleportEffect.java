package org.scy.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import org.scy.plugins.SCY_projectilesEffectPlugin;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.entities.SimpleEntity;
import org.lwjgl.util.vector.Vector2f;

public class SCY_teleportEffect implements OnHitEffectPlugin {

  private final Color EXPLOSION_COLOR = new Color(50, 150, 200, 200);
  private final Color PARTICLE_COLOR = new Color(40, 100, 250, 255);
  private boolean station = false;

  @Override
  public void onHit(
      DamagingProjectileAPI projectile,
      CombatEntityAPI target,
      Vector2f point,
      boolean shieldHit,
      ApplyDamageResultAPI damageResult,
      CombatEngineAPI engine) {

    if (target instanceof ShipAPI) {
      if (((ShipAPI) target).getParentStation() != null) {
        target = ((ShipAPI) target).getParentStation();
      }
      if (((ShipAPI) target).isStation()) {
        station = true;
      }
    }

    if (!shieldHit && !station) {
      // teleporting
      Vector2f recallPos =
          MathUtils.getPoint(
              projectile.getSource().getLocation(),
              Math.max(
                  projectile.getSource().getCollisionRadius() + target.getCollisionRadius(),
                  target.getCollisionRadius() * 3),
              projectile.getSource().getFacing() + 20 * (float) (Math.random() - 0.5f));
      SCY_projectilesEffectPlugin.addTeleportation(target, recallPos);
      Global.getSoundPlayer().playSound("SCY_teleporter", 1, 1, point, new Vector2f());
      engine.spawnEmpArc(
          projectile.getSource(),
          recallPos,
          null,
          new SimpleEntity(target.getLocation()),
          DamageType.KINETIC,
          0,
          0,
          1000,
          null,
          10,
          Color.WHITE,
          Color.BLUE);
      projectile.getSource().useSystem();

    } else {
      // EMP effect
      if (target instanceof ShipAPI) {
        for (int i = 0; i < 10; i++) {
          if (Math.random()
              < ((ShipAPI) target).getFluxTracker().getHardFlux()
                  / ((ShipAPI) target).getFluxTracker().getMaxFlux()) {
            engine.spawnEmpArcPierceShields(
                projectile.getSource(),
                point,
                target,
                target,
                DamageType.ENERGY,
                0,
                200, // emp
                500f, // max range
                "tachyon_lance_emp_impact",
                20f, // thickness
                new Color(25, 100, 155, 255),
                new Color(255, 255, 255, 255));
          } else {
            engine.spawnEmpArc(
                projectile.getSource(),
                point,
                target,
                target,
                DamageType.ENERGY,
                0,
                200, // emp
                500f, // max range
                "tachyon_lance_emp_impact",
                20f, // thickness
                new Color(25, 100, 155, 255),
                new Color(255, 255, 255, 255));
          }
        }
      }
      if (MagicRender.screenCheck(0.25f, point)) {
        // Spawn visual effects
        engine.spawnExplosion(
            point,
            (Vector2f) new Vector2f(target.getVelocity()).scale(.85f),
            EXPLOSION_COLOR,
            200f,
            0.5f);

        float facing = projectile.getFacing();
        int NUM_PARTICLES = 10;
        for (int x = 0; x < NUM_PARTICLES; x++) {
          engine.addHitParticle(
              MathUtils.getRandomPointInCircle(point, 30),
              MathUtils.getRandomPointInCone(new Vector2f(0, 0), 400, facing - 175, facing - 185),
              MathUtils.getRandomNumberInRange(2, 10),
              1,
              1,
              PARTICLE_COLOR);
        }
      }
    }
  }
}

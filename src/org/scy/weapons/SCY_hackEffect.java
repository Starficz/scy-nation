package org.scy.weapons;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import org.lwjgl.util.vector.Vector2f;

// import com.fs.starfarer.api.combat.EmpArcEntityAPI;

public class SCY_hackEffect implements BeamEffectPlugin {

  private final IntervalUtil timer = new IntervalUtil(0.1f, 3f);

  @Override
  public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {

    CombatEntityAPI target = beam.getDamageTarget();

    if (target != null && beam.getBrightness() >= 1f) {

      timer.advance(amount);
      if (timer.intervalElapsed()) {

        if (target instanceof ShipAPI) {

          if (((ShipAPI) beam.getDamageTarget()).getParentStation() != null) {
            target = ((ShipAPI) beam.getDamageTarget()).getParentStation();
          }

          if (Math.random() > 0.25) {
            engine.spawnEmpArcPierceShields(
                beam.getSource(),
                beam.getTo(),
                target,
                target,
                DamageType.ENERGY,
                80, // damage
                200, // emp
                1000f, // max range
                "tachyon_lance_emp_impact",
                30f, // thickness
                beam.getFringeColor(),
                beam.getCoreColor());
          }

        } else if (target instanceof MissileAPI
            && target.getOwner() != beam.getSource().getOwner()) {

          float rand = (float) Math.random();
          if (rand > 0.5) {
            engine.applyDamage(
                target, // enemy Entity
                target.getLocation(), // Our 2D vector to the exact world-position of the collision
                500, // DPS modified by the damage multiplier
                DamageType
                    .HIGH_EXPLOSIVE, // Using the damage type here, so that Kinetic / Explosive /
                                     // Fragmentation AOE works.
                0, // EMP (if any)
                false, // Does not bypass shields.
                false, // Does not do Soft Flux damage (unless you want it to for some strange
                       // reason)
                beam.getSource() // Who owns this projectile?
                );
            if (MagicRender.screenCheck(0.2f, target.getLocation())) {
              // public void spawnExplosion(Vector2f vctrf, Vector2f vctrf1, Color color, float f,
              // float f1)
              engine.spawnExplosion(
                  target.getLocation(),
                  new Vector2f(0, 0),
                  Color.orange,
                  target.getHitpoints() / 2,
                  target.getHitpoints() / 100);
            }
          } else {
            ((MissileAPI) target).flameOut();
          }
        }
      }
    }
  }
}

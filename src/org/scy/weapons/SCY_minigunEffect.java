// by Tartiflette, this script allow for a beam weapon to spawn bouncing projectiles on impact
// feel free to use it, credit is appreciated but not mandatory
package org.scy.weapons;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_minigunEffect implements BeamEffectPlugin {

  private boolean runOnce = false, hasSmoke = false, large = false;
  private float TEXTURE_RANDOMNESS = 2, GLOW_SIZE = 40;
  private final IntervalUtil timer = new IntervalUtil(0.025f, 0.075f);

    @Override
  public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
    // game paused, no script
    if (engine.isPaused()) {
      return;
    }

    if (!runOnce) {
      runOnce = true;
      if (beam.getWeapon().getSize() == WeaponSize.MEDIUM) {
        hasSmoke = true;
          float m_TEXTURE_RANDOMNESS = 5;
          TEXTURE_RANDOMNESS = m_TEXTURE_RANDOMNESS;
          float m_GLOW_SIZE = 60;
          GLOW_SIZE = m_GLOW_SIZE;
      } else if (beam.getWeapon().getSize() == WeaponSize.LARGE) {
        hasSmoke = true;
        large = true;
          float l_TEXTURE_RANDOMNESS = 30;
          TEXTURE_RANDOMNESS = l_TEXTURE_RANDOMNESS;
          float l_GLOW_SIZE = 80;
          GLOW_SIZE = l_GLOW_SIZE;
      }
      return;
    }

    timer.advance(amount);
    if (timer.intervalElapsed()
        && beam.getBrightness() > 0.8f
        && MagicRender.screenCheck(0.1f, beam.getFrom())) {

      beam.setPixelsPerTexel(
          15 + MathUtils.getRandomNumberInRange(-TEXTURE_RANDOMNESS, TEXTURE_RANDOMNESS));

      Vector2f from = beam.getFrom();
      Vector2f vel = beam.getSource().getVelocity();
      if (hasSmoke) {
        float grey = MathUtils.getRandomNumberInRange(0.3f, 0.6f);
        if (Math.random() > 0.5) {
          engine.addSmoothParticle(
              MathUtils.getRandomPointInCircle(from, 5),
              new Vector2f(
                  vel.x / 2 + MathUtils.getRandomNumberInRange(-5f, 5f),
                  vel.y / 2 + MathUtils.getRandomNumberInRange(-5f, 5f)),
              MathUtils.getRandomNumberInRange(10f, 25f),
              0.5f,
              MathUtils.getRandomNumberInRange(1f, 5f),
              new Color(grey, grey, grey, MathUtils.getRandomNumberInRange(0.02f, 0.08f)));
        } else {
          engine.addSmokeParticle(
              MathUtils.getRandomPointInCircle(from, 5),
              new Vector2f(
                  vel.x / 2 + MathUtils.getRandomNumberInRange(-5f, 5f),
                  vel.y / 2 + MathUtils.getRandomNumberInRange(-5f, 5f)),
              MathUtils.getRandomNumberInRange(10f, 25f),
              0.5f,
              MathUtils.getRandomNumberInRange(1f, 5f),
              new Color(grey, grey, grey, MathUtils.getRandomNumberInRange(0.02f, 0.08f)));
        }
      }
      engine.addSmoothParticle(
          from,
          vel,
          MathUtils.getRandomNumberInRange(GLOW_SIZE - 15, GLOW_SIZE + 15),
          0.5f,
          0.05f,
          new Color(255, 50, 25, 128));
    }

    // on Hit, create impact effect
    if (beam.didDamageThisFrame()) {
      Vector2f end = beam.getTo();

      // splinters
      float dir = VectorUtils.getAngle(beam.getDamageTarget().getLocation(), end);
      if (hasSmoke) {
        engine.spawnProjectile(
            beam.getSource(),
            beam.getWeapon(),
            "SCY_splinter1",
            MathUtils.getRandomPointOnCircumference(end, 5),
            dir + MathUtils.getRandomNumberInRange(-90, 90),
            null);
        engine.spawnProjectile(
            beam.getSource(),
            beam.getWeapon(),
            "SCY_splinter2",
            MathUtils.getRandomPointOnCircumference(end, 5),
            dir + MathUtils.getRandomNumberInRange(-90, 90),
            null);
        if (large) {
          engine.spawnProjectile(
              beam.getSource(),
              beam.getWeapon(),
              "SCY_splinter2",
              MathUtils.getRandomPointOnCircumference(end, 5),
              dir + MathUtils.getRandomNumberInRange(-90, 90),
              null);
        }
      } else {
        // spawn a single splinters
        if (Math.random() > 0.66) {
          engine.spawnProjectile(
              beam.getSource(),
              beam.getWeapon(),
              "SCY_splinter1",
              MathUtils.getRandomPointOnCircumference(end, 5),
              dir + MathUtils.getRandomNumberInRange(-90, 90),
              null);
        } else if (Math.random() > 0.5) {
          engine.spawnProjectile(
              beam.getSource(),
              beam.getWeapon(),
              "SCY_splinter2",
              MathUtils.getRandomPointOnCircumference(end, 5),
              dir + MathUtils.getRandomNumberInRange(-90, 90),
              null);
        }
      }
    }
  }
}

// by Tartiflette,
// feel free to use it, credit is appreciated but not mandatory
package org.scy.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import org.magiclib.plugins.MagicFakeBeamPlugin;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_scpbEffect implements BeamEffectPlugin {

  private final Color PARTICLE_COLOR = new Color(50, 175, 250, 255);
  private boolean hasFired = false;
  private float timer = 0;

  @Override
  public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
    // Don't bother with any checks if the game is paused
    if (engine.isPaused()) {
      return;
    }

    if (beam.getBrightness() == 1) {
      Vector2f start = beam.getFrom();
      Vector2f end = beam.getTo();

      if (MathUtils.getDistanceSquared(start, end) == 0) {
        return;
      }

      timer += 10 * amount;
      if (timer >= 2f) {
        timer = 0;
        hasFired = false;
      }

      if (!hasFired) {
        hasFired = true;
        boolean visibleStart = MagicRender.screenCheck(0.15f, start);
        boolean visibleEnd = MagicRender.screenCheck(0.15f, end);

        if (beam.getDamageTarget() != null && visibleEnd) {
          // visual effect
          engine.spawnExplosion(
              // where
              end,
              // speed
              (Vector2f) new Vector2f(0, 0),
              // color
              PARTICLE_COLOR,
              // size
              MathUtils.getRandomNumberInRange(50f, 100f),
              // duration
              0.2f);
        }

        // play sound (to avoid limitations with the way weapon sounds are handled)
        Global.getSoundPlayer()
            .playSound("SCY_scpb", 1f, 1f, start, beam.getSource().getVelocity());
        if (visibleStart) {
          // weapon glow
          engine.addHitParticle(start, new Vector2f(), 75, 1f, 0.3f, new Color(50, 100, 255, 255));
          engine.addHitParticle(start, new Vector2f(), 50, 1f, 0.1f, Color.WHITE);
        }

        if (visibleStart || visibleEnd) {
          // Add the beam to the plugin
          // public static void addBeam(float duration, float fading, float width, Vector2f from,
          // float angle, float length, Color core, Color fringe)
          float WIDTH = 8;
          MagicFakeBeamPlugin.addBeam(
              0.15f,
              0.35f,
                  WIDTH,
              start,
              VectorUtils.getAngle(start, end),
              MathUtils.getDistance(start, end),
              Color.WHITE,
              PARTICLE_COLOR);
        }
      }
    }
    if (beam.getWeapon().getChargeLevel() < 1) {
      hasFired = false;
      timer = 0;
    }
  }
}

package org.scy.weapons;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_deconstructionEffect implements BeamEffectPlugin {

  private final IntervalUtil damageInterval = new IntervalUtil(0.05f, 0.2f);
  private float width = 5;

  @Override
  public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {

    if (engine.isPaused()) {
      return;
    }

    damageInterval.advance(amount);

    // on Hit, create impact effect
    if (beam.getDamageTarget() != null) {
      width = Math.min(15, width + amount);
      beam.setWidth(width);

      if (MagicRender.screenCheck(1, beam.getTo())) {
        engine.addHitParticle(
            MathUtils.getRandomPointInCircle(beam.getTo(), 10),
            MathUtils.getRandomPointInCone(
                new Vector2f(),
                300,
                beam.getSource().getFacing() - 178,
                beam.getSource().getFacing() - 182),
            MathUtils.getRandomNumberInRange(2, 10),
            0.8f,
            1,
            Color.orange);
      }
      // public void addHitParticle(Vector2f loc, Vector2f vel, float size, float brightness, float
      // duration, Color color)
      // damage bonus
      if (damageInterval.intervalElapsed()
          && beam.getDamageTarget() != null
          && beam.getDamageTarget() instanceof ShipAPI) {
        ShipAPI target = (ShipAPI) beam.getDamageTarget();
        if (target.getShield() == null || !target.getShield().isWithinArc(beam.getTo())) {

          float HP = target.getHitpoints();
          if (HP > target.getMaxHitpoints() / 10) {
            //                        target.setHitpoints(HP - (int)(target.getMaxHitpoints()/100));
            target.setHitpoints(HP - 10);
          }
        }
      }
    } else {
      width = Math.max(5, width - amount);
    }
  }
}

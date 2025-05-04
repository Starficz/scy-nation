package org.scy.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import java.util.Iterator;
import java.util.List;
import org.apache.log4j.Logger;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_khalkotauroiBeamEffect implements BeamEffectPlugin {

  private final Logger log = Global.getLogger(SCY_khalkotauroiBeamEffect.class);

  private final IntervalUtil timer = new IntervalUtil(0.075f, 0.125f);
  private boolean logged = false;
  private final Vector2f FLARE_A = new Vector2f(594, 17);
  private final Vector2f FLARE_B = new Vector2f(654, 57);
  private final Vector2f FLARE_C = new Vector2f(203, 203);

  @Override
  public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {

    if (engine.isPaused()) {
      return;
    }

    // visual effect
    float pow = beam.getBrightness();

    if (pow > 0.1f) {

      // on Hit, create impact effect
      if (beam.didDamageThisFrame() && MagicRender.screenCheck(0.2f, beam.getTo())) {
        engine.spawnExplosion(
            // where
            beam.getTo(),
            // speed
            (Vector2f) new Vector2f(0, 0),
            // color
            new Color(25, 75, 150, 75),
            // size
            MathUtils.getRandomNumberInRange(50f, 100f),
            // duration
            0.2f);
      }

      // damage bonus
      timer.advance(amount);
      if (timer.intervalElapsed()) {
        // pods count
        List<ShipAPI> thePods = beam.getSource().getChildModulesCopy();
        for (Iterator<ShipAPI> iterator = thePods.iterator(); iterator.hasNext(); ) {
          ShipAPI next = iterator.next();
          if (!next.isAlive()) {
            iterator.remove();
          }
        }

        // beam visual
        beam.setWidth(1.5f * thePods.size() + 10);
        beam.setCoreColor(
            new Color(255, (75 + (30 * thePods.size())), (75 + (30 * thePods.size())), 255));
        beam.setFringeColor(
            new Color(255, (15 + (40 * thePods.size())), (15 + (40 * thePods.size())), 255));
        beam.setPixelsPerTexel((6 + 2 * thePods.size()) * (0.5f + pow / 2));
        // beam damage
        float OUTPUT = 10f;
        if (beam.getDamageTarget() != null) {
          engine.applyDamage(
              beam.getDamageTarget(),
              beam.getTo(),
              thePods.size() * OUTPUT,
              DamageType.ENERGY,
              0f,
              false,
              true,
              beam.getSource());
        }
        beam.getSource().getFluxTracker().increaseFlux(thePods.size() * OUTPUT, false);

        if (MagicRender.screenCheck(0.25f, beam.getFrom())) {
          pow *= (thePods.size() + 2) / 6f;
          // beam flare
          Vector2f offset = new Vector2f(beam.getFrom());
          Vector2f.sub(offset, beam.getSource().getLocation(), offset);
          MagicRender.objectspace(
              Global.getSettings().getSprite("fx", "SCY_khalkA"),
              beam.getSource(),
              offset,
              new Vector2f(),
              (Vector2f)
                  new Vector2f(FLARE_A).scale(pow * MathUtils.getRandomNumberInRange(0.95f, 1.05f)),
              new Vector2f(),
              90,
              0,
              false,
              Color.WHITE,
              true,
              0.05f,
              0.05f,
              (float) Math.random() / 10,
              false);

          MagicRender.objectspace(
              Global.getSettings().getSprite("fx", "SCY_khalkB"),
              beam.getSource(),
              offset,
              new Vector2f(),
              (Vector2f)
                  new Vector2f(FLARE_B).scale(pow * MathUtils.getRandomNumberInRange(0.95f, 1.05f)),
              new Vector2f(),
              90,
              0,
              false,
              Color.WHITE,
              true,
              0.05f,
              0.05f,
              (float) Math.random() / 10,
              false);

          MagicRender.objectspace(
              Global.getSettings().getSprite("fx", "SCY_khalkC"),
              beam.getSource(),
              offset,
              new Vector2f(),
              (Vector2f)
                  new Vector2f(FLARE_C).scale(pow * MathUtils.getRandomNumberInRange(0.95f, 1.05f)),
              new Vector2f(),
              0,
              0,
              false,
              Color.WHITE,
              true,
              0.05f,
              0.1f,
              (float) Math.random() / 5,
              false);
        }
      }
    }

    if (!logged) {
      logged = true;
      log.info(
          "Khalkotauroi: Found " + beam.getSource().getChildModulesCopy().size() + " modules.");
    }
  }
}

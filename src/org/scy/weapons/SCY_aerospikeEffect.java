/*
   By Tartiflette
*/
package org.scy.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import org.lazywizard.lazylib.MathUtils;

public class SCY_aerospikeEffect implements EveryFrameWeaponEffectPlugin {

  private boolean runOnce = false;
  private ShipAPI SHIP;
  private float SPEED;
  private ShipEngineControllerAPI ENGINES;
  private final IntervalUtil timer = new IntervalUtil(0.03f, 0.05f);

  private final List<aerospikeData> AEROSPIKES = new ArrayList<>();

  private float SOmult = 1f;

  private static class aerospikeData {
    private final WeaponAPI FLAME;
    private final SpriteAPI HEAT;
    private final ShipEngineAPI ENGINE;
    private final float SIZE;
    private float FLAME_THROTTLE;
    private float HEAT_THROTTLE;

    private aerospikeData(
        WeaponAPI flame,
        SpriteAPI heat,
        ShipEngineAPI engine,
        float size,
        float flameThrottle,
        float heatThrottle) {
      this.FLAME = flame;
      this.HEAT = heat;
      this.ENGINE = engine;
      this.SIZE = size;
      this.FLAME_THROTTLE = flameThrottle;
      this.HEAT_THROTTLE = heatThrottle;
    }
  }

  private void addAerospike(
      WeaponAPI flame,
      SpriteAPI heat,
      ShipEngineAPI engine,
      float size,
      float flameThrottle,
      float heatThrottle) {
    AEROSPIKES.add(new aerospikeData(flame, heat, engine, size, flameThrottle, heatThrottle));
  }

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

    if (!runOnce) {
      runOnce = true;
      timer.randomize();
      SHIP = weapon.getShip();
      SPEED = SHIP.getMutableStats().getMaxSpeed().getBaseValue();
      ENGINES = SHIP.getEngineController();

      // get all engines and sprites assigned, NOT FUTURE PROOF but expandable easily.
      WeaponAPI flameCenter = null;
      WeaponAPI flameLeft = null;
      WeaponAPI flameRight = null;

      WeaponAPI heatCenter = null;
      WeaponAPI heatLeft = null;
      WeaponAPI heatRight = null;

      ShipEngineAPI engineCenter = null;
      ShipEngineAPI engineLeft = null;
      ShipEngineAPI engineRight = null;

      for (WeaponAPI w : SHIP.getAllWeapons()) {
        if (w.getSlot().getId().contains("FLAME")) {
          if (w.getSlot().getId().endsWith("CENTER")) {
            flameCenter = w;
          } else if (w.getSlot().getId().endsWith("LEFT")) {
            flameLeft = w;
          } else if (w.getSlot().getId().endsWith("RIGHT")) {
            flameRight = w;
          }
        }

        if (w.getSlot().getId().contains("HEAT")) {
          if (w.getSlot().getId().endsWith("CENTER")) {
            heatCenter = w;
          } else if (w.getSlot().getId().endsWith("LEFT")) {
            heatLeft = w;
          } else if (w.getSlot().getId().endsWith("RIGHT")) {
            heatRight = w;
          }
        }
      }

      for (ShipEngineAPI e : SHIP.getEngineController().getShipEngines()) {

        if (flameCenter != null
            && MathUtils.isWithinRange(e.getLocation(), flameCenter.getLocation(), 10)) {
          engineCenter = e;
          SHIP.getEngineController()
              .fadeToOtherColor(e, new Color(255, 200, 60, 0), new Color(0, 0, 0, 0), 1, 1);
        } else if (flameLeft != null
            && MathUtils.isWithinRange(e.getLocation(), flameLeft.getLocation(), 10)) {
          engineLeft = e;
          SHIP.getEngineController()
              .fadeToOtherColor(e, new Color(255, 200, 60, 0), new Color(0, 0, 0, 0), 1, 1);
        } else if (flameRight != null
            && MathUtils.isWithinRange(e.getLocation(), flameRight.getLocation(), 10)) {
          engineRight = e;
          SHIP.getEngineController()
              .fadeToOtherColor(e, new Color(255, 200, 60, 0), new Color(0, 0, 0, 0), 1, 1);
        }
      }

      // Make sure everything is working
      if (flameCenter != null
          && flameCenter.getAnimation() != null
          && heatCenter != null
          && heatCenter.getSprite() != null
          && engineCenter != null) {
        flameCenter.getAnimation().setFrame(flameCenter.getAnimation().getNumFrames() - 1);
        addAerospike(
            flameCenter,
            heatCenter.getSprite(),
            engineCenter,
            flameCenter.getSprite().getHeight(),
            1,
            1);
      }
      if (flameLeft != null
          && flameLeft.getAnimation() != null
          && heatLeft != null
          && heatLeft.getSprite() != null
          && engineLeft != null) {
        flameLeft.getAnimation().setFrame(flameLeft.getAnimation().getNumFrames() - 1);
        addAerospike(
            flameLeft, heatLeft.getSprite(), engineLeft, flameLeft.getSprite().getHeight(), 1, 1);
      }
      if (flameRight != null
          && flameRight.getAnimation() != null
          && heatRight != null
          && heatRight.getSprite() != null
          && engineRight != null) {
        flameRight.getAnimation().setFrame(flameRight.getAnimation().getNumFrames() - 1);
        addAerospike(
            flameRight,
            heatRight.getSprite(),
            engineRight,
            flameRight.getSprite().getHeight(),
            1,
            1);
      }

      for (String h : SHIP.getVariant().getHullMods()) {
        if (h.equals("safetyoverrides")) {
          SOmult = 0.5f;
          break;
        }
      }

      //            //debug
      //            if(AEROSPIKES.isEmpty()){
      //                engine.addFloatingText(SHIP.getLocation(), "CUSTOM ENGINE ERROR", 50,
      // Color.red, SHIP, 5, 5);
      //            }

      // hide the engines in reffit
      if (SHIP.getOriginalOwner() == -1 || SHIP.getOwner() == -1) {
        for (aerospikeData a : AEROSPIKES) {
          a.FLAME.getAnimation().setFrame(0);
          a.FLAME.getAnimation().setFrameRate(0);
          a.FLAME.getSprite().setColor(new Color(0, 0, 0, 0));
          a.FLAME.getSprite().setAlphaMult(0);
          a.FLAME.getSprite().setAdditiveBlend();
          a.HEAT.setColor(Color.BLACK);
        }
        return;
      }

      // randomize stuff
      for (aerospikeData a : AEROSPIKES) {
        a.FLAME
            .getAnimation()
            .setFrame(
                MathUtils.getRandomNumberInRange(1, a.FLAME.getAnimation().getNumFrames() - 1));
      }
    }

    if (engine.isPaused()) {
      return;
    }

    // screencheck
    if (!MagicRender.screenCheck(0.3f, SHIP.getLocation())) return;

    // check for death
    if (!SHIP.isAlive()) {
      for (aerospikeData a : AEROSPIKES) {
        a.FLAME.getAnimation().setFrame(0);
        a.FLAME.getAnimation().setAlphaMult(0);
        a.FLAME.getAnimation().setFrameRate(0);
        a.HEAT.setColor(new Color(0, 0, 0, 0));
      }
      return;
    }

    // 30FPS
    timer.advance(amount);
    if (timer.intervalElapsed()) {

      // check the current behavior of the ship
      float throttle = 1;
      if (ENGINES.isFlamedOut() || ENGINES.isFlamingOut()) {
        throttle = 0;
      } else if (ENGINES.isAccelerating()) {
        throttle = 1;
      } else if (ENGINES.isAcceleratingBackwards()
          || ENGINES.isDecelerating()
          || ENGINES.isStrafingLeft()
          || ENGINES.isStrafingRight()) {
        throttle = 0.66f;
      } else {
        throttle = 0.25f;
      }

      // over extend the flame along speed bonuses and other ship systems
      throttle *= SHIP.getMutableStats().getMaxSpeed().getModifiedValue() / SPEED;

      // apply to individuals engine
      for (aerospikeData a : AEROSPIKES) {
        float flameout = 1;
        // check for individual extinguished engine
        if (a.ENGINE.isDisabled()) {
          flameout = 0;
        }

        // lightly smooth out the flame behavior
        float offsetFlame = flameout * throttle - a.FLAME_THROTTLE;
        if (Math.abs(offsetFlame) < 0.05f) {
          a.FLAME_THROTTLE = throttle;
        } else {
          a.FLAME_THROTTLE = a.FLAME_THROTTLE + (offsetFlame) / 20;
        }

        // heavily smooth the heat behavior
        float offsetHeat = (flameout * throttle * 0.66f) - a.HEAT_THROTTLE;
        if (Math.abs(offsetHeat) < 0.01f) {
          a.HEAT_THROTTLE = throttle * 0.66f;
        } else {
          a.HEAT_THROTTLE = a.HEAT_THROTTLE + (offsetHeat) / 80;
        }

        // modify the size and opacity of the heat and flame accordingly
        int frame = a.FLAME.getAnimation().getFrame() + 1;
        if (frame >= a.FLAME.getAnimation().getNumFrames()) {
          frame = 1;
        }

        float color = Math.min(1, Math.max(0, a.FLAME_THROTTLE * 0.75f));

        SHIP.getEngineController()
            .fadeToOtherColor(a, new Color(255, 200, 60, 0), new Color(0, 0, 0, 0), 1, 1);

        a.FLAME.getAnimation().setFrame(frame);
        a.FLAME.getSprite().setHeight(a.SIZE * (0.5f + a.FLAME_THROTTLE / 2));
        a.FLAME.getSprite().setCenterY(a.FLAME.getSprite().getHeight() / 2);
        a.FLAME
            .getSprite()
            .setColor(
                new Color(1, 0.25f + (0.75f * color), 0.25f + (SOmult * 0.75f * color), color));
        a.HEAT.setColor(new Color(1, 1, 1, Math.min(1, Math.max(0, a.HEAT_THROTTLE))));
      }
    }
  }
}

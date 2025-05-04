// by Tartiflette
package org.scy.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.magiclib.util.MagicLensFlare;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import org.apache.log4j.Logger;
import org.lazywizard.lazylib.FastTrig;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_ketoMainEffect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

  private final Logger log = Global.getLogger(SCY_ketoMainEffect.class);

  private boolean runOnce = false, hasFired = false, LDEATH = false, RDEATH = false, sound = false;
  // private int DEAD=0;
  private float wCharge = 0, delay;
  private WeaponAPI ASTRAPIOS, LIGHT, HEAT, BARREL, DOT;
  private ShipAPI SHIP;
  private ShipAPI LEFT, RIGHT;

  private float warmup = 0;

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

    if (engine.isPaused()) {
      return;
    }

    if (!runOnce) {
      runOnce = true;
      SHIP = weapon.getShip();

      if (SHIP.getOriginalOwner() == -1 || SHIP.getOwner() == -1) {
        return;
      }

      if (SHIP.getOriginalOwner() != 0) {
        delay = 2.1f;
      }

      for (WeaponAPI w : SHIP.getAllWeapons()) {
        switch (w.getSlot().getId()) {
          case "F_LIGHT":
            LIGHT = w;
            break;
          case "F_HEAT":
            HEAT = w;
            break;
          case "F_DOT":
            DOT = w;
            break;
          case "Z_GUN":
            BARREL = w;
            break;
          case "ASTRAPIOS":
            ASTRAPIOS = w;
            break;
        }
      }
      return;
    }

    if (delay >= 0) {
      delay -= amount;
      if (delay <= 0) {
        if (!SHIP.getChildModulesCopy().isEmpty()) {
          for (ShipAPI s : SHIP.getChildModulesCopy()) {
            // debug
            log.info("Found module: " + s.getHullSpec().getHullId());
            s.setDrone(true);

            if (s.getHullSpec().getHullId().contains("SCY_ketoAL")) {
              LEFT = s;
            } else {
              RIGHT = s;
            }
          }
        }
      }
      return;
    }

    if (hasFired) {
      light(wCharge);
      warmup = -1f;
      if (wCharge == 0) {
        hasFired = false;
        sound = false;
      }
    } else if (!LDEATH
        && !SHIP.getFluxTracker().isOverloadedOrVenting()
        && !SHIP.getTravelDrive().isActive()
        && ASTRAPIOS.getCooldownRemaining() == 0.0f) {
      // weapon warms-up if the ship isn't overloaded, venting, travelling in and out and if the
      // weapon is ready to fire
      warmup = Math.min(1, warmup + amount / 5);
      if (warmup > 0 && !sound) {
        sound = true;
        Global.getSoundPlayer()
            .playSound("SCY_keto_load", 1, 1, ASTRAPIOS.getLocation(), SHIP.getVelocity());
      }
    } else {
      warmup = Math.max(-1f, warmup - amount * 10);
      if (sound) {
        sound = false;
        Global.getSoundPlayer()
            .playSound("SCY_keto_unload", 1, 1, ASTRAPIOS.getLocation(), SHIP.getVelocity());
      }
    }

    // lock weapon as long as it isn't warmed up
    if (!LDEATH && warmup == 1) {
      ASTRAPIOS.setAmmo(1);
    } else {
      ASTRAPIOS.setAmmo(0);
    }

    wCharge = ASTRAPIOS.getChargeLevel();

    // deco effects
    dot(warmup, wCharge, amount);
    heat(wCharge, hasFired);

    if (!LDEATH && (LEFT == null || !LEFT.isAlive())) {
      LDEATH = true;
      ASTRAPIOS.disable(true);
      BARREL.getAnimation().setFrame(0);
    }

    if (!RDEATH) {
      if (RIGHT == null || !RIGHT.isAlive()) {
        RDEATH = true;
        SHIP.getMutableStats().getFighterRefitTimeMult().modifyMult("SCY_deckDestroyed", 10);
        log.info("Deck destroyed.");
      } else {
        SHIP.getMutableStats().getFighterRefitTimeMult().unmodify("SCY_deckDestroyed");
      }
    }
  }

  @Override
  public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
    Global.getSoundPlayer()
        .playSound("SCY_keto_unload", 1, 1, ASTRAPIOS.getLocation(), SHIP.getVelocity());
    hasFired = true;
    // muzzle
    Vector2f loc = ASTRAPIOS.getLocation();
    if (MagicRender.screenCheck(0.5f, weapon.getLocation())) {
      engine.spawnExplosion(loc, new Vector2f(0, 0), new Color(75, 10, 5, 25), 250, 1f);
      engine.addHitParticle(loc, new Vector2f(0, 0), 500, 1f, 0.15f, new Color(255, 200, 150, 255));
      engine.addHitParticle(loc, new Vector2f(0, 0), 250, 1f, 1f, new Color(150, 25, 10, 255));
      for (int i = 0; i < 10; i++) {
        engine.addHitParticle(
            loc,
            MathUtils.getRandomPointInCone(
                new Vector2f(0, 0), 200, SHIP.getFacing() - 45, SHIP.getFacing() + 45),
            MathUtils.getRandomNumberInRange(3, 10),
            1f,
            MathUtils.getRandomNumberInRange(1, 3),
            new Color(150, 25, 10, 255));
      }
      MagicLensFlare.createSharpFlare(
          engine,
          SHIP,
          loc,
          10,
          750,
          0,
          new Color(150, 75, 25, 255),
          new Color(250, 200, 150, 150));
    }
  }

  private void dot(float warmth, float charge, float amount) {
    if (warmth <= 0) {
      DOT.getAnimation().setFrame(0);
      DOT.setCurrAngle(SHIP.getFacing());
    } else {
      int frame = Math.round(Math.min(1, warmth) * (DOT.getAnimation().getNumFrames() - 1));
      float color = Math.max(0.0f, Math.min(warmth * 2.0f, 1.0f));
      DOT.getAnimation().setFrame(frame);
      DOT.getSprite().setColor(new Color(1.0f, color, color, color));
      float smooth = (float) -FastTrig.cos(warmth * MathUtils.FPI / 2) + 1;
      DOT.setCurrAngle(DOT.getCurrAngle() - amount * (smooth + charge) * 4000);
    }
  }

  private void heat(float charge, boolean hasFired) {
    if (charge == 0) {
      BARREL.getAnimation().setFrame(0);
      HEAT.getAnimation().setFrame(0);
    } else {
      HEAT.getAnimation().setFrame(1);
      float heat = Math.max(0, Math.min(charge * 1.25f, 1));
      HEAT.getSprite().setColor(new Color(1, heat, heat, heat));
      if (hasFired) {
        BARREL.getAnimation().setFrame(1);
        BARREL.getSprite().setColor(new Color(1, heat, heat, heat));
      }
    }
  }

  private void light(float charge) {
    if (charge == 0) {
      LIGHT.getAnimation().setFrame(0);
    } else {
      LIGHT.getAnimation().setFrame(1);
      float light = Math.min(0, Math.max(charge * 4 - 3, 1));
      LIGHT.getSprite().setColor(new Color(1, light, light, light));
    }
  }
}

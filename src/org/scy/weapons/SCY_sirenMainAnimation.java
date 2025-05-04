package org.scy.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicAnim;
import org.magiclib.util.MagicLensFlare;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_sirenMainAnimation implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

  private WeaponAPI CHARGE;
  private WeaponAPI HEAT;
  private SpriteAPI DOOR_FL;
  private SpriteAPI DOOR_FR;
  private SpriteAPI DOOR_RL;
  private SpriteAPI DOOR_RR;
  private ShipAPI SHIP;

  private float FLheight, FLwidth, RLheight, RLwidth;
  private float doors = 0, directionCheck = 0;
  private int direction = 1;
  private final IntervalUtil timer = new IntervalUtil(0.0333f, 0.0333f);
  private final float tic = 0.0333f;

  private boolean runOnce = false,
      hasFired = false,
      closed = true,
      open = false,
      refundCheck = false;

  private static final String chargeID = "E_CHARGE";
  private static final String heatID = "E_HEAT";
  private static final String doorFLID = "F_GUNLEFT";
  private static final String doorFRID = "F_GUNRIGHT";
  private static final String doorRLID = "G_GUNLEFT";
  private static final String doorRRID = "G_GUNRIGHT";

  @Override
  public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {

    if (weapon.getShip().getSystem().isOn()) {
      weapon.getShip().getSystem().deactivate();

      engine.spawnProjectile(
          weapon.getShip(),
          weapon,
          "SCY_sirenSuper",
          projectile.getLocation(),
          projectile.getFacing(),
          weapon.getShip().getVelocity());
      //            engine.spawnProjectile(
      //                    weapon.getShip(),
      //                    weapon,
      //                    "SCY_sirenMain",
      //                    projectile.getLocation(),
      //                    projectile.getFacing(),
      //                    weapon.getShip().getVelocity()
      //            );
      engine.removeEntity(projectile);
    }
  }

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

    if (engine.isPaused()) return;

    if (!runOnce || SHIP == null) {
      runOnce = true;
      SHIP = weapon.getShip();
      refundCheck = false;

      // get the weapon, all the sprites and sizes
      for (WeaponAPI w : SHIP.getAllWeapons()) {
        switch (w.getSlot().getId()) {
          case chargeID:
            CHARGE = w;
            break;

          case heatID:
            HEAT = w;
            break;

          case doorFLID:
            DOOR_FL = w.getSprite();
            FLheight = DOOR_FL.getHeight();
            FLwidth = DOOR_FL.getWidth();
            break;

          case doorFRID:
            DOOR_FR = w.getSprite();
            break;

          case doorRLID:
            DOOR_RL = w.getSprite();
            RLheight = DOOR_RL.getHeight();
            RLwidth = DOOR_RL.getWidth();
            break;

          case doorRRID:
            DOOR_RR = w.getSprite();
            break;
        }
      }
      return;
    }

    // system toggle off refund
    if (SHIP.getSystem().isActive()) {
      refundCheck = true;
    } else if (refundCheck) {
      refundCheck = false;
      if (weapon.getChargeLevel() < 0.99f) {
        // system was turned off without firing, refund system ammo and halve cooldown
        SHIP.getSystem()
            .setAmmo(Math.min(SHIP.getSystem().getAmmo() + 1, SHIP.getSystem().getMaxAmmo()));
        SHIP.getSystem().setCooldownRemaining(SHIP.getSystem().getCooldownRemaining() / 10f);
      }
    }

    float charge = weapon.getChargeLevel();
    if (charge == 1) {
      hasFired = true;
      // Visual effect
      if (MagicRender.screenCheck(0.25f, weapon.getLocation())) {

        Vector2f muzzle =
            new Vector2f(MathUtils.getPoint(weapon.getLocation(), 52, weapon.getCurrAngle()));

        engine.addHitParticle(muzzle, new Vector2f(), 150, 1, 0.25f, new Color(50, 100, 200, 255));

        engine.addHitParticle(muzzle, new Vector2f(), 75, 1, 0.15f, Color.WHITE);

        MagicLensFlare.createSharpFlare(
            engine, SHIP, muzzle, 6, 600, 0, new Color(50, 150, 255, 128), Color.white);
      }
    } else if (charge == 0) {
      hasFired = false;
    }

    timer.advance(amount);
    if (timer.intervalElapsed()) {
      directionCheck -= tic;

      // GUN stuff
      if (weapon.getChargeLevel() > 0 && !hasFired) {
        CHARGE.getAnimation().setFrame(1);
        CHARGE.getSprite().setColor(new Color(charge, charge, 1, charge));
        direction = 1;
        directionCheck = 4;
        if (closed) {
          Global.getSoundPlayer()
              .playSound("SCY_siren_open", 1, 1, SHIP.getLocation(), SHIP.getVelocity());
          closed = false;
        }
      } else {
        CHARGE.getAnimation().setFrame(0);
      }
      if (weapon.getCooldownRemaining() > 0) {
        HEAT.getAnimation().setFrame(1);
        HEAT.getSprite().setColor(new Color(1, charge, charge, charge));
      } else {
        HEAT.getAnimation().setFrame(0);
      }

      // DOORS stuff
      if (directionCheck <= 0) {
        direction = -1;
        if (open) {
          Global.getSoundPlayer()
              .playSound("SCY_siren_close", 1, 1, SHIP.getLocation(), SHIP.getVelocity());
          open = false;
        }
      }

      doors = Math.min(1, Math.max(0, doors + direction * tic));
      if (doors == 1) {
        open = true;
      } else if (doors == 0) {
        closed = true;
      }

      float R = RLheight / 2 - MagicAnim.smoothNormalizeRange(doors, 0f, 0.8f) * 6;
      float RL = RLwidth / 2 + MagicAnim.smoothNormalizeRange(doors, 0f, 0.6f) * 5;
      float RR = RLwidth / 2 - MagicAnim.smoothNormalizeRange(doors, 0f, 0.6f) * 5;
      DOOR_RL.setCenter(RL, R);
      DOOR_RR.setCenter(RR, R);

      float F = FLheight / 2 + MagicAnim.smoothNormalizeRange(doors, 0.4f, 1f) * 11;
      float FL = FLwidth / 2 + MagicAnim.smoothNormalizeRange(doors, 0.2f, 0.7f) * 4;
      float FR = FLwidth / 2 - MagicAnim.smoothNormalizeRange(doors, 0.2f, 0.7f) * 4;
      DOOR_FL.setCenter(FL, F);
      DOOR_FR.setCenter(FR, F);
    }
  }
}

// By Tartiflette, updated by Starficz
package org.scy.weapons;

import static org.scy.SCY_txt.txt;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.magiclib.util.MagicRender;
import org.magiclib.util.MagicUI;
import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lazywizard.lazylib.combat.entities.SimpleEntity;
import org.lwjgl.util.vector.Vector2f;

public class SCY_stymphalianEffect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

  private boolean runOnce = false, ventBoost = false, shieldBoost = false, overcharged = false, teleporterArmed = true;
  private ShipAPI ship;
  private WeaponAPI SPARKS;
  private SpriteAPI HEAT, CAPACITOR, RAILS;
  private ShipSystemAPI system;
  private float SHIELD_ARC = 0, SPARKS_WIDTH;
  private final Color NO_COLOR = new Color(0, 0, 0, 0);
  private int reverse = 1;
  private float boost = 0f, heat = 0, capacitor = 0, rails = 0, flip = 1;
  private final IntervalUtil animationTimer = new IntervalUtil(0.05f, 0.05f);
  private final String ID = "SCY_experimentalTeleporter";

  @Override
  public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
    if (overcharged) {

      int extraShots = 1 + Math.round(9 * boost);
      rails = 1;
      boost = 0;
      capacitor = 0;
      system.setAmmo(system.getAmmo() - 1);
      overcharged = false;

      for (int y = 0; y < extraShots; y++) {
        engine.spawnProjectile(
          ship,
          weapon,
          "SCY_stymphalianSuper",
          projectile.getLocation(),
          projectile.getFacing(),
          ship.getVelocity()
        );
      }
      engine.spawnProjectile(
        ship,
        weapon,
        "SCY_stymphalianMain",
        projectile.getLocation(),
        projectile.getFacing(),
        ship.getVelocity()
      );
      engine.removeEntity(projectile);

      // flash
      Vector2f speed = ship.getVelocity();
      float facing = ship.getFacing();
      Vector2f barrel = new Vector2f(150, 0);
      Vector2f tip = VectorUtils.rotate(barrel, facing, barrel);
      SimpleEntity aim = new SimpleEntity(new Vector2f(weapon.getLocation().x + tip.x, weapon.getLocation().y + tip.y));

      if (MagicRender.screenCheck(0.25f, ship.getLocation())) {
        engine.spawnEmpArc(
          ship,
          weapon.getLocation(),
          ship,
          aim,
          DamageType.KINETIC,
          0,
          0,
          1000,
          null,
          2,
          Color.orange,
          Color.white
        );

        engine.addHitParticle(weapon.getLocation(), (Vector2f) speed.scale(.5f), 200, 2, 1f, Color.ORANGE);
        engine.addHitParticle(weapon.getLocation(), (Vector2f) speed.scale(.5f), 100, 2, 0.2f, Color.white);
        for (int x = 0; x < 10; x++) {
          engine.addHitParticle(
            weapon.getLocation(),
            MathUtils.getPoint(null, MathUtils.getRandomNumberInRange(100, 500), MathUtils.getRandomNumberInRange(facing - 20f, facing + 20f)),
            MathUtils.getRandomNumberInRange(3, 10),
            1f,
            MathUtils.getRandomNumberInRange(0.5f, 1f),
            Color.orange
          );
        }
      }
      CombatUtils.applyForce(ship, ship.getFacing() + 180, 100 * boost);
      Global.getSoundPlayer()
          .playSound(
              "SCY_spear_chargedFire",
              1.25f - boost / 2,
              0.5f + boost / 2,
              ship.getLocation(),
              ship.getVelocity());
    }
  }

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

    if (!runOnce) {
      runOnce = true;
      ship = weapon.getShip();
      system = ship.getSystem();
      if (ship.getShield() != null) {
        SHIELD_ARC = ship.getShield().getArc();
      }

      for (WeaponAPI w : ship.getAllWeapons()) {
        switch (w.getSlot().getId()) {
          case "Z_SYSTEM1":
            if (ship.getOriginalOwner() != -1 && ship.getOwner() != -1) {
              w.getAnimation().setFrame(1);
            }
            HEAT = w.getSprite();
            break;
          case "Z_SYSTEM2":
            SPARKS = w;
            break;
          case "Z_SYSTEM3":
            if (ship.getOriginalOwner() != -1 && ship.getOwner() != -1) {
              w.getAnimation().setFrame(1);
            }
            CAPACITOR = w.getSprite();
            break;
          case "Z_HEAT_MAIN":
            if (ship.getOriginalOwner() != -1 && ship.getOwner() != -1) {
              w.getAnimation().setFrame(1);
            }
            RAILS = w.getSprite();
            break;
        }
      }

      SPARKS.getAnimation().setFrame(1);
      SPARKS_WIDTH = SPARKS.getSprite().getWidth();
      SPARKS.getAnimation().setFrame(0);

      HEAT.setColor(NO_COLOR);
      CAPACITOR.setColor(NO_COLOR);
      RAILS.setColor(NO_COLOR);

      // spread the load
      animationTimer.randomize();
      return;
    }

    if(engine.isPaused()) return;

    animationTimer.advance(amount);

    // every frame decays
    boost = Math.max(0, boost - amount*(2f/15f));
    heat = Math.max(0, heat - amount/5f);
    capacitor = Math.max(0, capacitor - amount*(2f/15f));
    if (heat > 0 || capacitor > 0) {
      visualEffect();
    }

    // always check the main gun glow
    rails = Math.max(0, rails - ((float) 1 / 150));
    RAILS.setColor(new Color(1, 1, 1, rails));

    // if boost is 0, switch off all boosts and overcharged state
    if(boost == 0){
      if(overcharged){
        system.setAmmo(system.getAmmo() - 1);
        overcharged = false;
      }
      if (shieldBoost) {
        shieldBoost = false;
        ship.getShield().setArc(SHIELD_ARC);
        ship.getMutableStats().getShieldUnfoldRateMult().unmodify(ID);
        ship.getMutableStats().getShieldDamageTakenMult().unmodify(ID);
      }
      if (ventBoost) {
        ventBoost = false;
        ship.getMutableStats().getVentRateMult().unmodify(ID);
      }

      ship.getMutableStats().getTimeMult().unmodify(ID);
      ship.getMutableStats().getDeceleration().unmodify(ID);
      ship.getMutableStats().getAcceleration().unmodify(ID);
      engine.getTimeMult().unmodify(ID);
    }

    // if system is active without being overcharged, activate/reset to first stage of system and refund the charge
    if (system.isActive()){
      if(!overcharged && teleporterArmed){
        system.setAmmo(system.getAmmo() + 1);
        overcharged = true;
        boost = 1;
        capacitor = 1;
        heat = 1;
        shieldBoost = false;
        ventBoost = false;
        teleporterArmed = false;
      }
    } else{
      teleporterArmed = true;
    }

    // Apply first stage of system buffs, and check if any second stage trigger is activated
    if(overcharged){
      // First stage of system buffs
      // Overcharge Trail
      if (MagicRender.screenCheck(0.25f, ship.getLocation()) && animationTimer.intervalElapsed()) {
        engine.addHitParticle(
          ship.getLocation(),
          new Vector2f(ship.getVelocity().x * 0.5f, ship.getVelocity().y * 0.5f),
          ship.getCollisionRadius() * (0.5f + boost),
          boost / 10,
          MathUtils.getRandomNumberInRange(0.5f, 1f + boost),
          new Color(0.3f, 0.1f, 0.3f));
      }

      // Time dilation
      float dilation = (1 + boost * 2);
      ship.getMutableStats().getTimeMult().modifyMult(ID, dilation);
      ship.getMutableStats().getDeceleration().modifyMult(ID, dilation);
      ship.getMutableStats().getAcceleration().modifyMult(ID, dilation);

      if (ship == engine.getPlayerShip()) engine.getTimeMult().modifyMult(ID, 1f / dilation);
      else engine.getTimeMult().unmodify(ID);

      // Check second stage triggers
      // shield boost
      if (ship.getShield() != null && ship.getShield().isOn()) {
        shieldBoost = true;
        system.setAmmo(system.getAmmo() - 1);
        overcharged = false;
      }
      // system reuse
      else if (system.isActive() && teleporterArmed) {
        teleporterArmed = false;
        overcharged = false;
      }
      // venting boost
      else if (ship.getFluxTracker().isVenting()) {
        ventBoost = true;
        Global.getSoundPlayer().playSound("SCY_enhancedVent", 1f, 1, ship.getLocation(), ship.getVelocity());
        system.setAmmo(system.getAmmo() - 1);
        overcharged = false;
      }
    }



    // Apply second stage buffs if active
    if (shieldBoost) {
      ship.getShield().setArc(SHIELD_ARC + (270 * boost));
      ship.getMutableStats().getShieldUnfoldRateMult().modifyMult(ID, 10f * boost);
      ship.getMutableStats().getShieldDamageTakenMult().modifyMult(ID, 1 - (1 * boost));

      if (MagicRender.screenCheck(0.5f, ship.getLocation()) && animationTimer.intervalElapsed()) {
        engine.addHitParticle(
          MathUtils.getPoint(ship.getShield().getLocation(), ship.getShield().getRadius(),
                  (int) MathUtils.getRandomNumberInRange(ship.getFacing() - (ship.getShield().getArc() / 2), ship.getFacing() + (ship.getShield().getArc() / 2))),
          ship.getVelocity(),
          5 + (float) Math.random() * 5,
          1f,
          0.1f + (float) Math.random() * 0.2f,
          new Color(125, 200, 250, 150));
      }
    }

    if (ventBoost) {
      ship.getMutableStats().getVentRateMult().modifyMult(ID, 10f * boost);

      // overcharge quickly dissipates
      boost = Math.max(0, boost - amount/3f);

      // STEAM
      if (ship.getFluxTracker().isVenting() && MagicRender.screenCheck(0.1f, ship.getLocation()) && animationTimer.intervalElapsed()) {
        for (int x = 0; x < Math.round(10 * boost); x++) {
          engine.addSmokeParticle(
            ship.getLocation(),
            MathUtils.getRandomPointInCircle(null, 50),
            MathUtils.getRandomNumberInRange(25f, 100f),
            MathUtils.getRandomNumberInRange(0.1f, 0.2f),
            MathUtils.getRandomNumberInRange(0.2f, 2f),
            new Color(1, 1, 1, 0.1f));
        }
      }
    }

    // UI
    MagicUI.drawInterfaceStatusBar(ship, boost, overcharged ? Misc.getNegativeHighlightColor() : null, null, 0, txt("wpn_bird"), Math.round(boost * 100));

    // SOUND
    if (overcharged) {
      Global.getSoundPlayer().playLoop(
        "system_emp_emitter_loop",
        ship,
        2f + boost,
        boost / 2,
        ship.getLocation(),
        ship.getVelocity());
      Global.getSoundPlayer().playLoop(
        "SCY_deconstruction_loop",
        ship,
        0.2f + boost / 2,
        boost / 2,
        ship.getLocation(),
        ship.getVelocity());
    }
  }

  //////////////////////////////
  //                          //
  //      DECOS EFFECT        //
  //                          //
  //////////////////////////////

  private void visualEffect() {
    // capacitor
    float capacitorAlpha = Math.min(1, Math.max(0, (1 - 1 / (float) Math.pow(capacitor + 1, 2))+ capacitor * ((float) Math.random() / 2)));
    CAPACITOR.setColor(new Color(capacitorAlpha, capacitorAlpha, 1, capacitorAlpha));

    // heat
    HEAT.setColor(new Color(1, heat, heat, heat)); // linear regression

    // spakles
    int frame = SPARKS.getAnimation().getFrame();
    if (Math.random() > 1 - boost) { // chance to skip the animation that grows as the Overcharge dwindle
      if (frame == 0) { // random start
        frame = (int) Math.round(Math.random() * (SPARKS.getAnimation().getNumFrames() - 1));
        if (Math.random() > 0.5) { // random flip
          flip *= -1;
        }
        if (Math.random() > 0.5) { // random flip
          reverse *= -1;
        }
      } else {
        frame = frame + reverse; // or play the animation normaly
        if (frame == SPARKS.getAnimation().getNumFrames() || frame < 0) {
          frame = (int) Math.round(Math.random() * (SPARKS.getAnimation().getNumFrames() - 1));
        }
      }
    } else {
      frame = 0;
    }
    SPARKS.getAnimation().setFrame(frame);
    SPARKS.getSprite().setWidth(SPARKS_WIDTH * flip);
    SPARKS.getSprite().setCenterX(SPARKS.getSprite().getWidth() / 2);
  }
}

// By Tartiflette
package org.scy.weapons;

import com.fs.starfarer.api.AnimationAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.scy.plugins.SCY_muzzleFlashesPlugin;
import org.lazywizard.lazylib.MathUtils;

public class SCY_minigunAnimation implements EveryFrameWeaponEffectPlugin {

  private float delay = 0.1f;
  private float timer = 0;
  private float SPINUP = 0.02f;
  private float SPINDOWN = 10f;
  private float PITCH = 0;

  private boolean put = false, spool = false;

  private boolean runOnce = false;
  private boolean hidden = false, flash = true;
  private AnimationAPI theAnim;
  private int maxFrame;
  private int frame;

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

    if (engine.isPaused()) {
      return;
    }

    if (!runOnce) {
      runOnce = true;
      if (weapon.getSlot().isHidden()) {
        hidden = true;
      } else {
        theAnim = weapon.getAnimation();
        maxFrame = theAnim.getNumFrames();
        frame = MathUtils.getRandomNumberInRange(0, maxFrame - 1);
      }
      if (weapon.getSlot().isBuiltIn()) {
        SPINUP = 0.06f;
        SPINDOWN = 4f;
        PITCH = 0.15f;
        flash = false;
      } else if (weapon.getSize().equals(WeaponAPI.WeaponSize.SMALL)) {
        SPINUP = 0.04f;
        SPINDOWN = 7.5f;
        PITCH = 0.1f;
      } else if (weapon.getSize().equals(WeaponAPI.WeaponSize.LARGE)) {
        SPINUP = 0.01f;
        SPINDOWN = 15f;
        PITCH = -0.05f;
      }
      PITCH += MathUtils.getRandomNumberInRange(-0.02f, 0.02f);
      SPINUP += MathUtils.getRandomNumberInRange(-0.0075f, 0.0075f);
      SPINDOWN += MathUtils.getRandomNumberInRange(-1f, 1f);
    }

    if (weapon.isFiring() || delay < 0.1f) {
      timer += amount;
      if (timer >= delay) {
        timer -= delay;
        if (weapon.getChargeLevel() > 0) {
          delay = Math.max(delay - SPINUP, 0.02f);
        } else {
          delay = Math.min(delay + delay / SPINDOWN, 0.1f);
        }
        if (!hidden && delay != 0.1f) {
          frame++;
          if (frame == maxFrame) {
            frame = 0;
          }
        }
      }
    }

    // play the spinning sound
    if (weapon.getChargeLevel() > 0) {
      Global.getSoundPlayer()
          .playLoop(
              "SCY_minigun_spin",
              weapon,
              (0.1f + 0.9f * weapon.getChargeLevel()) * (1 + PITCH),
              0.25f * weapon.getChargeLevel(),
              weapon.getLocation(),
              weapon.getShip().getVelocity());
      if (!spool) {
        spool = true;
        Global.getSoundPlayer()
            .playSound(
                "SCY_minigun_spinUp",
                1 + PITCH,
                0.1f,
                weapon.getLocation(),
                weapon.getShip().getVelocity());
      }
    } else if (spool) {
      spool = false;
      Global.getSoundPlayer()
          .playSound(
              "SCY_minigun_spinDown",
              1 + PITCH,
              0.1f,
              weapon.getLocation(),
              weapon.getShip().getVelocity());
    }

    if (!hidden) {
      theAnim.setFrame(frame);
      // assign the weapon for the muzzle flash plugin
      if (flash && weapon.getChargeLevel() == 1) {
        // add the weapon to the MEMBERS map
        if (!put) {
          put = true;
          SCY_muzzleFlashesPlugin.addMuzzle(weapon, 0, Math.random() > 0.5);
        }
      } else if (flash && weapon.getChargeLevel() == 0) {
        // remove the weapon from the MEMBERS map
        if (put) {
          put = false;
          SCY_muzzleFlashesPlugin.removeMuzzle(weapon);
        }
      }
    }
  }
}

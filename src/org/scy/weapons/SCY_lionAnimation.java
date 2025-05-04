package org.scy.weapons;


import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.magiclib.util.MagicAnim;
import java.awt.Color;
import org.apache.log4j.Logger;

public class SCY_lionAnimation implements EveryFrameWeaponEffectPlugin {

  private final Logger log = Global.getLogger(SCY_lionAnimation.class);
  private final String ID = "lion_hide";
  private boolean activation = false, runOnce = false;
  //    private ShipSystemAPI system;
  private ShipAPI SHIP;
  private float TimeDoors = 1, delay = 0;
  private SpriteAPI DFL, DFR, DRL, DRR;
  private WeaponAPI WFL, WFR, WRL, WRR;
  private ShipAPI MFL, MFR, MRL, MRR;
  private boolean FL = false, FR = false, RL = false, RR = false, damageResist = true;
  private float dfX, dfY, drX, drY, DirDoors = 1;
  private boolean weaponLocked = false;

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

    if (engine.isPaused()) {
      return;
    }

    if (!runOnce) {
      runOnce = true;
      SHIP = weapon.getShip();
      //            system=SHIP.getSystem();

      if (SHIP.getOriginalOwner() != 0) {
        delay = 2.1f;
      }

      log.info("Nemean Lion deco detection");
      // get the corresponding deco weapons
      for (WeaponAPI w : SHIP.getAllWeapons()) {
        switch (w.getSlot().getId()) {
          case "D_FL":
            WFL = w;
            DFL = WFL.getSprite();
            dfX = DFL.getCenterX();
            dfY = DFL.getCenterY();
            log.info("Front Left deco: " + WFL.getId());
            break;
          case "D_FR":
            WFR = w;
            DFR = WFR.getSprite();
            log.info("Front Right deco: " + WFR.getId());
            break;
          case "D_RL":
            WRL = w;
            DRL = WRL.getSprite();
            drX = DRL.getCenterX();
            drY = DRL.getCenterY();
            log.info("Rear Left deco: " + WRL.getId());
            break;
          case "D_RR":
            WRR = w;
            DRR = WRR.getSprite();
            log.info("Rear Right deco: " + WRR.getId());
            break;
        }
      }
      return;
    }

    if (delay >= 0) {
      delay -= amount;
      if (delay <= 0) {
        log.info("Nemean Lion modules detection");
        // get the modules as ship
        if (!SHIP.getChildModulesCopy().isEmpty()) {
          for (ShipAPI m : SHIP.getChildModulesCopy()) {
            switch (m.getStationSlot().getId()) {
              case "M_FL":
                MFL = m;
                FL = true;
                log.info("Front Left module: " + m.getHullSpec().getHullId());
                break;
              case "M_FR":
                MFR = m;
                FR = true;
                log.info("Front Right module: " + m.getHullSpec().getHullId());
                break;
              case "M_RL":
                MRL = m;
                RL = true;
                log.info("Rear Left module: " + m.getHullSpec().getHullId());
                break;
              case "M_RR":
                MRR = m;
                RR = true;
                log.info("Rear Right module: " + m.getHullSpec().getHullId());
                break;
            }
          }
        } else {
          log.info("No module detected.");
        }
      }
      return;
    }
    Color tranparent = new Color(0, 0, 0, 0);
    // check if the modules are alive and hide the deco weapon otherwise
    if (FL && !MFL.isAlive()) {
      DFL.setColor(tranparent);
      FL = false;
      log.info("Front Left module absent");
    }
    if (FR && !MFR.isAlive()) {
      DFR.setColor(tranparent);
      FR = false;
      log.info("Front Right module absent");
    }
    if (RL && !MRL.isAlive()) {
      DRL.setColor(tranparent);
      RL = false;
      log.info("Rear Left module absent");
    }
    if (RR && !MRR.isAlive()) {
      DRR.setColor(tranparent);
      RR = false;
      log.info("Rear Right module absent");
    }

    // activate the closing animation
    //        if((system.isChargeup() || system.isOn()) ||
    // SHIP.getFluxTracker().isOverloadedOrVenting() || SHIP.getTravelDrive().isActive()){
    if (SHIP.isHoldFire()
        || SHIP.isHoldFireOneFrame()
        || (SHIP.getFluxTracker().isOverloadedOrVenting() && (SHIP.getFluxTracker().getTimeToVent() > 1f || SHIP.getFluxTracker().getOverloadTimeRemaining() > 1f))
        || SHIP.getTravelDrive().isActive()) {
      if (activation) {
        activation = false;
        DirDoors = 0.5f;
        Global.getSoundPlayer()
            .playSound("SCY_NL_close", 1, 1, SHIP.getLocation(), SHIP.getVelocity());
        weaponLocked = true;
      }
    } else if (!activation) {
      activation = true;
      DirDoors = -0.5f;
      Global.getSoundPlayer()
          .playSound("SCY_NL_open", 1, 1, SHIP.getLocation(), SHIP.getVelocity());
    }

    // DOORS STUFF
    if (DirDoors != 0) {
      TimeDoors += DirDoors * amount;
      if (DirDoors > 0) {
        if (TimeDoors > 1) {
          TimeDoors = 1;
          DirDoors = 0;
        }
      } else if (TimeDoors < 0) {
        TimeDoors = 0;
        DirDoors = 0;
        weaponLocked = false;
      }

      // apply the animation to the relevant modules and deco weapons
      int offsetFY = -22;
      int offsetFX = -7;
      if (FL) {
        MFL.getModuleOffset()
            .set(
                -(MagicAnim.smoothNormalizeRange(TimeDoors, 0.25f, 1f) * offsetFX),
                -(MagicAnim.smoothNormalizeRange(TimeDoors, 0, 0.75f) * offsetFY));

        DFL.setCenter(
            dfX + (MagicAnim.smoothNormalizeRange(TimeDoors, 0, 0.75f) * offsetFY),
            dfY - (MagicAnim.smoothNormalizeRange(TimeDoors, 0.25f, 1f) * offsetFX));
      }

      if (FR) {
        MFR.getModuleOffset()
            .set(
                -(MagicAnim.smoothNormalizeRange(TimeDoors, 0.25f, 1f) * offsetFX),
                (MagicAnim.smoothNormalizeRange(TimeDoors, 0, 0.75f) * offsetFY));

        DFR.setCenter(
            dfX - (MagicAnim.smoothNormalizeRange(TimeDoors, 0, 0.75f) * offsetFY),
            dfY - (MagicAnim.smoothNormalizeRange(TimeDoors, 0.25f, 1f) * offsetFX));
      }

      int offsetRY = -6;
      int offsetRX = 6;
      if (RL) {
        MRL.getModuleOffset()
            .set(
                -(MagicAnim.smoothNormalizeRange(TimeDoors, 0.25f, 1f) * offsetRX),
                -(MagicAnim.smoothNormalizeRange(TimeDoors, 0, 0.75f) * offsetRY));

        DRL.setCenter(
            drX + (MagicAnim.smoothNormalizeRange(TimeDoors, 0, 0.75f) * offsetRY),
            drY - (MagicAnim.smoothNormalizeRange(TimeDoors, 0.25f, 1f) * offsetRX));
      }

      if (RR) {
        MRR.getModuleOffset()
            .set(
                -(MagicAnim.smoothNormalizeRange(TimeDoors, 0.25f, 1f) * offsetRX),
                (MagicAnim.smoothNormalizeRange(TimeDoors, 0, 0.75f) * offsetRY));

        DRR.setCenter(
            drX - (MagicAnim.smoothNormalizeRange(TimeDoors, 0, 0.75f) * offsetRY),
            drY - (MagicAnim.smoothNormalizeRange(TimeDoors, 0.25f, 1f) * offsetRX));
      }
    }

    if (TimeDoors > 0) {
      if (SHIP.getFluxTracker().isOverloaded()) {
        // interrupt effect
        damageResist = false;
        if (FL) {
          MFL.getMutableStats().getArmorDamageTakenMult().unmodify(ID);
        }
        if (FR) {
          MFR.getMutableStats().getArmorDamageTakenMult().unmodify(ID);
        }
        if (RL) {
          MRL.getMutableStats().getArmorDamageTakenMult().unmodify(ID);
        }
        if (RR) {
          MRR.getMutableStats().getArmorDamageTakenMult().unmodify(ID);
        }
      } else {
        // add damage reduction
        damageResist = true;
        if (FL) {
          MFL.getMutableStats().getArmorDamageTakenMult().modifyMult(ID, 0.75f * TimeDoors);
          MFL.setJitter(SHIP, Color.cyan, 0.25f * TimeDoors, 5, 50 * TimeDoors);
        }
        if (FR) {
          MFR.getMutableStats().getArmorDamageTakenMult().modifyMult(ID, 0.75f * TimeDoors);
          MFR.setJitter(SHIP, Color.cyan, 0.25f * TimeDoors, 5, 50 * TimeDoors);
        }
        if (RL) {
          MRL.getMutableStats().getArmorDamageTakenMult().modifyMult(ID, 0.75f * TimeDoors);
          MRL.setJitter(SHIP, Color.cyan, 0.25f * TimeDoors, 5, 50 * TimeDoors);
        }
        if (RR) {
          MRR.getMutableStats().getArmorDamageTakenMult().modifyMult(ID, 0.75f * TimeDoors);
          MRR.setJitter(SHIP, Color.cyan, 0.25f * TimeDoors, 5, 50 * TimeDoors);
        }
      }

    } else if (damageResist) {
      // remove effect
      damageResist = false;
      if (FL) {
        MFL.getMutableStats().getArmorDamageTakenMult().unmodify(ID);
      }
      if (FR) {
        MFR.getMutableStats().getArmorDamageTakenMult().unmodify(ID);
      }
      if (RL) {
        MRL.getMutableStats().getArmorDamageTakenMult().unmodify(ID);
      }
      if (RR) {
        MRR.getMutableStats().getArmorDamageTakenMult().unmodify(ID);
      }
    }

    if(weaponLocked){
      SHIP.getAllWeapons().forEach(potentialWeapon -> {
        if(potentialWeapon.getSize() == WeaponAPI.WeaponSize.LARGE) potentialWeapon.isForceNoFireOneFrame();
      });
    }
  }
}

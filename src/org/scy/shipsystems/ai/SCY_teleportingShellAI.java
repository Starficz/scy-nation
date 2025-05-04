package org.scy.shipsystems.ai;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import java.util.List;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_teleportingShellAI implements ShipSystemAIScript {
  private CombatEngineAPI engine;
  private ShipAPI ship;
  private ShipSystemAPI system;
  private WeaponAPI theWeapon;
  private boolean runOnce = false;
  private final IntervalUtil timer = new IntervalUtil(1f, 2f);

  @Override
  public void init(
      ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
    this.ship = ship;
    this.system = system;
    this.engine = engine;
    timer.randomize();
  }

  @Override
  public void advance(
      float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {

    if (engine.isPaused() || ship.getShipAI() == null) {
      return;
    }

    if (!runOnce) {
      runOnce = true;
      List<WeaponAPI> weapons = ship.getAllWeapons();
      for (WeaponAPI w : weapons) {
        if (w.getId().equals("SCY_sirenMain")) {
          theWeapon = w;
          break;
        }
      }
    }

    // prevent the system activation if retreating
    if (ship.isRetreating()) {
      if (system.isActive()) {
        ship.useSystem();
      }
      return;
    }

    timer.advance(amount);
    if (timer.intervalElapsed()) {

      if (target != null) {
        // check if the target is in weapon range and can be reached with the system active

        float relativeAngle = 0;
        if (target.getShield() != null) {
          relativeAngle =
              MathUtils.getShortestRotation(
                  target.getFacing(),
                  VectorUtils.getAngle(target.getLocation(), ship.getLocation()));
        }

        if (target.isAlive()
            && // target alive
            (target.isDestroyer() || target.isCruiser() || target.isCapital())
            && // and is a big ship
            (MathUtils.isWithinRange(ship, target, theWeapon.getRange())
                && !MathUtils.isWithinRange(ship, target, 300))
            && ( // and is in range but not already close
            target.getFluxTracker().isOverloadedOrVenting()
                || // either overloaded, venting
                target.getShield() == null
                || // without a shield
                (target.getShield() != null
                    && target.getShield().getType() == ShieldType.FRONT
                    && relativeAngle
                        > 1.2
                            * target.getShield().getArc()
                            / 2) // or with a frontal shield facing away
            )) {
          // target open for teleporting, check if the system is ready
          if (!system.isActive()
              && AIUtils.canUseSystemThisFrame(ship)
              && ship.getFluxTracker().getFluxLevel() < 0.5f) {
            ship.useSystem();
            return;
          }
        }
      }
      if (system.isActive() && (target == null || (MathUtils.isWithinRange(ship, target, 500)))) {
        ship.useSystem();
      }
    }
  }
}

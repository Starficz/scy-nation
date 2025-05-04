package org.scy.weapons.ai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import org.magiclib.util.MagicTargeting;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_coastingMissileAI implements MissileAIPlugin, GuidedMissileAI {

  private CombatEngineAPI engine;
  private final MissileAPI missile;
  private CombatEntityAPI target;
  private Vector2f lead = new Vector2f(), offset = new Vector2f();
  private boolean courseCorrection = true;
  private float eccm = 3f, timer = 0f, check = 0.2f, coastingTimer = 0;
  private boolean launch = true, coasting = true;
  // data
  private final float MAX_SPEED;

  // engine autonomy in seconds
  private float fuel = 15;

  public SCY_coastingMissileAI(MissileAPI missile, ShipAPI launchingShip) {
    if (engine != Global.getCombatEngine()) {
      this.engine = Global.getCombatEngine();
    }
    this.missile = missile;
    MAX_SPEED = missile.getMaxSpeed();
    if (missile.getSource().getVariant().getHullMods().contains("eccm")) {
      eccm = 1;
    }
    fuel += (3 - eccm) * 2.5f;
  }

  @Override
  public void advance(float amount) {
    if (engine.isPaused() || missile.isFading() || missile.isFizzling()) {
      return;
    }

    // if the fuel is exhausted, flameout the missile
    if (fuel < 0) {
      missile.flameOut();
      return;
    }

    // if the missile has no target, get one
    if (target == null
        || ((target instanceof ShipAPI) && !((ShipAPI) target).isAlive())
        || !engine.isEntityInPlay(target)
        || target.getCollisionClass() == CollisionClass.NONE) {
      int SEARCH_CONE = 360;
      setTarget(
          MagicTargeting.pickMissileTarget(
              missile,
              MagicTargeting.targetSeeking.NO_RANDOM,
              (int) missile.getWeapon().getRange(),
                  SEARCH_CONE,
              0,
              1,
              1,
              1,
              1));

      if (target != null) {
        offset = MathUtils.getRandomPointInCircle(new Vector2f(), target.getCollisionRadius());
      }

      fuel -= amount;
      missile.giveCommand(ShipCommand.ACCELERATE);
      return;
    }

    timer += amount;
    coastingTimer += amount;
    // finding lead point to aim to
    if (launch || timer >= check) {
      launch = false;
      timer -= check;
      float dist = MathUtils.getDistanceSquared(missile.getLocation(), target.getLocation());

      // set the next check time
      int PRECISION_RANGE = 500;
      check = Math.min(0.5f, Math.max(0.1f, 2 * dist / PRECISION_RANGE));

      lead =
          AIUtils.getBestInterceptPoint(
              missile.getLocation(), MAX_SPEED * eccm, target.getLocation(), target.getVelocity());

      if (lead == null) {
        lead = target.getLocation();
      }

      lead =
          new Vector2f(
              lead.x + (offset.x * Math.min(1, dist / 1000000)),
              lead.y + (offset.y * Math.min(1, dist / 1000000)));
      coasting = !MathUtils.isWithinRange(missile, target, PRECISION_RANGE);
    }

    float leadAngle = VectorUtils.getAngle(missile.getLocation(), lead);
    float aimAngle = MathUtils.getShortestRotation(missile.getFacing(), leadAngle);

    // always point the missile to the lead
    if (aimAngle < 0) {
      missile.giveCommand(ShipCommand.TURN_RIGHT);
    } else {
      missile.giveCommand(ShipCommand.TURN_LEFT);
    }

    // Damp angular velocity if we're getting close to the target angle
    float DAMPING = 0.1f;
    if (Math.abs(aimAngle) < Math.abs(missile.getAngularVelocity()) * DAMPING) {
      missile.setAngularVelocity(aimAngle / DAMPING);
    }

    // if the missile is far from it's target, use fuel saving navigation

    if (coasting && coastingTimer > 1f) {
      coastingTimer -= 1f;
      float deviation =
          MathUtils.getShortestRotation(VectorUtils.getFacing(missile.getVelocity()), leadAngle);
      courseCorrection = Math.abs(deviation) > 22.5f;
    }

    if (Math.abs(aimAngle) < 90
        && (missile.getVelocity().length() < 0.9f * MAX_SPEED
            || !coasting
            || (coasting && courseCorrection))) {
      fuel -= amount;
      missile.giveCommand(ShipCommand.ACCELERATE);
    }
  }

  @Override
  public CombatEntityAPI getTarget() {
    return target;
  }

  @Override
  public void setTarget(CombatEntityAPI target) {
    this.target = target;
  }

  public void init(CombatEngineAPI engine) {}
}

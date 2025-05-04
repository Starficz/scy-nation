// By Tartiflette, fast and highly customizable Missile AI.
package org.scy.weapons.ai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import org.magiclib.util.MagicTargeting;
import java.awt.*;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_phaseTorpedoAI implements MissileAIPlugin, GuidedMissileAI {

  //////////////////////
  //     SETTINGS     //
  //////////////////////

  // Does the missile find a random target or aways tries to hit the ship's one?
  /*
   *  NO_RANDOM,
   * If the launching ship has a valid target within arc, the missile will pursue it.
   * If there is no target, it will check for an unselected cursor target within arc.
   * If there is none, it will pursue its closest valid threat within arc.
   *
   *  LOCAL_RANDOM,
   * If the ship has a target, the missile will pick a random valid threat around that one.
   * If the ship has none, the missile will pursue a random valid threat around the cursor, or itself.
   * Can produce strange behavior if used with a limited search cone.
   *
   *  FULL_RANDOM,
   * The missile will always seek a random valid threat within arc around itself.
   *
   *  IGNORE_SOURCE,
   * The missile will pick the closest target of interest. Useful for custom MIRVs.
   *
   */
  private final MagicTargeting.targetSeeking seeking = MagicTargeting.targetSeeking.NO_RANDOM;

  // range under which the missile start to get progressively more precise in game units.
  private static float PRECISION_RANGE = 500;
  private static final float DETONATION_RANGE = 800;

  // Leading loss without ECCM hullmod. The higher, the less accurate the leading calculation will
  // be.
  //   1: perfect leading with and without ECCM
  //   2: half precision without ECCM
  //   3: a third as precise without ECCM. Default
  //   4, 5, 6 etc : 1/4th, 1/5th, 1/6th etc precision.
  private float ECCM = 2f; // A VALUE BELOW 1 WILL PREVENT THE MISSILE FROM EVER HITTING ITS TARGET!

  //////////////////////
  //    VARIABLES     //
  //////////////////////

  // max speed of the missile after modifiers.
  private final float MAX_SPEED;
  private CombatEngineAPI engine;
  private final MissileAPI MISSILE;
  private CombatEntityAPI target;
  private Vector2f lead = new Vector2f();
  private boolean launch = true;
  private float timer = 0, check = 0f;

  //////////////////////
  //  DATA COLLECTING //
  //////////////////////

  public SCY_phaseTorpedoAI(MissileAPI missile, ShipAPI launchingShip) {
    this.MISSILE = missile;
    MAX_SPEED = missile.getMaxSpeed();
    if (missile.getSource().getVariant().getHullMods().contains("eccm")) {
      ECCM = 0.8f;
    }
    // calculate the precision range factor
    PRECISION_RANGE = (float) Math.pow((2 * PRECISION_RANGE), 2);
  }

  //////////////////////
  //   MAIN AI LOOP   //
  //////////////////////

  @Override
  public void advance(float amount) {

    if (engine != Global.getCombatEngine()) {
      this.engine = Global.getCombatEngine();
    }

    // skip the AI if the game is paused, the missile is engineless or fading
    if (Global.getCombatEngine().isPaused() || MISSILE.isFading() || MISSILE.isFizzling()) {
      return;
    }

    // assigning a target if there is none or it got destroyed
    // Does the missile switch its target if it has been destroyed?
    boolean TARGET_SWITCH = true;
    if (target == null
        || (TARGET_SWITCH
            && ((target instanceof ShipAPI && !((ShipAPI) target).isAlive())
                || !engine.isEntityInPlay(target)))) {
      // should the missile fall back to the closest enemy when no target is found within the search
      // parameters
      // only used with limited search cones
      boolean FAILSAFE = true;
      // range in which the missile seek a target in game units.
      int MAX_SEARCH_RANGE = 2000;
      // Arc to look for targets into
      // set to 360 or more to ignore
      int SEARCH_CONE = 360;
      int capitals = 5;
      int cruisers = 3;
      int destroyers = 1;
      int frigates = 0;
      // Target class priorities
      // set to 0 to ignore that class
      int fighters = 0;
      setTarget(
          MagicTargeting.pickTarget(
              MISSILE,
              seeking,
                  MAX_SEARCH_RANGE,
                  SEARCH_CONE,
                  fighters,
                  frigates,
                  destroyers,
                  cruisers,
                  capitals,
                  FAILSAFE));
      // forced acceleration by default
      MISSILE.giveCommand(ShipCommand.ACCELERATE);
      return;
    }

    timer += amount;
    // finding lead point to aim to
    if (launch || timer >= check) {
      launch = false;
      timer -= check;

      // set the next check time
      check =
          Math.min(
              0.25f,
              Math.max(
                  0.05f,
                  MathUtils.getDistanceSquared(MISSILE.getLocation(), target.getLocation())
                      / PRECISION_RANGE));

      // best intercepting point
      lead =
          AIUtils.getBestInterceptPoint(
              MISSILE.getLocation(),
              MAX_SPEED
                  * ECCM, // if eccm is intalled the point is accurate, otherwise it's placed closer
              // to the target (almost tailchasing)
              target.getLocation(),
              target.getVelocity());

      // null pointer protection
      if (lead == null) {
        lead = target.getLocation();
      }

      // mine trigger
      if (MathUtils.isWithinRange(target, MISSILE, DETONATION_RANGE * ECCM / 3)) {
        engine.spawnProjectile(
            MISSILE.getSource(),
            MISSILE.getWeapon(),
            "SCY_phase_mine",
            MISSILE.getLocation(),
            MISSILE.getFacing(),
            new Vector2f(MISSILE.getVelocity()));
        engine.addHitParticle(
            MISSILE.getLocation(), MISSILE.getVelocity(), 50, 0.5f, 0.25f, Color.PINK);
        engine.addHitParticle(
            MISSILE.getLocation(), MISSILE.getVelocity(), 100, 1f, 0.1f, Color.WHITE);
        Global.getSoundPlayer()
            .playSound(
                "system_phase_cloak_deactivate",
                1,
                0.5f,
                MISSILE.getLocation(),
                MISSILE.getVelocity());
        engine.removeEntity(MISSILE);
        return;
      }
    }

    // best velocity vector angle for interception
    float correctAngle = VectorUtils.getAngle(MISSILE.getLocation(), lead);

    // target angle for interception
    float aimAngle = MathUtils.getShortestRotation(MISSILE.getFacing(), correctAngle);

    MISSILE.giveCommand(ShipCommand.ACCELERATE);

    if (aimAngle < 0) {
      MISSILE.giveCommand(ShipCommand.TURN_RIGHT);
    } else {
      MISSILE.giveCommand(ShipCommand.TURN_LEFT);
    }

    // Damp angular velocity if the missile aim is getting close to the targeted angle
    // Damping of the turn speed when closing on the desired aim. The smaller the snappier.
    float DAMPING = 0.1f;
    if (Math.abs(aimAngle) < Math.abs(MISSILE.getAngularVelocity()) * DAMPING) {
      MISSILE.setAngularVelocity(aimAngle / DAMPING);
    }
  }

  //////////////////////
  //    TARGETING     //
  //////////////////////

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

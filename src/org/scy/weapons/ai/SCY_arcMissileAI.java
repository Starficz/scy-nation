// By Tartiflette,
package org.scy.weapons.ai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import org.magiclib.util.MagicLensFlare;
import org.magiclib.util.MagicRender;
import org.magiclib.util.MagicTargeting;
import java.awt.Color;
import org.lazywizard.lazylib.FastTrig;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_arcMissileAI implements MissileAIPlugin, GuidedMissileAI {
  // max speed of the missile after modifiers
  private final float MAX_SPEED;
  // range under which the missile start to get progressively more precise in game units.
  private float PRECISION_RANGE = 750;

  private final Color CORE_COLOR = new Color(200, 100, 225);
  private final Color FRINGE_COLOR = new Color(200, 100, 150, 128);

  private final float RANDOM_RANGE;

  private CombatEngineAPI engine;
  private final MissileAPI missile;
  private CombatEntityAPI target;
  private Vector2f lead = new Vector2f();
  private boolean launch = true, arcing = false;
  private float eccm = 2,
      timer = 0,
      check = 0f,
      arcTimer = 0,
      arcRandomness = 0.25f,
      circlingRadius = 1000f,
      empCharge = 20,
      direction = 1;

  //////////////////////
  //  DATA COLLECTING //
  //////////////////////

  public SCY_arcMissileAI(MissileAPI missile, ShipAPI launchingShip) {
    this.missile = missile;
    MAX_SPEED = missile.getMaxSpeed();
    if (missile.getSource().getVariant().getHullMods().contains("eccm")) {
      eccm = 1;
    }
    // calculate the precision range factor
    PRECISION_RANGE = (float) Math.pow(2 * PRECISION_RANGE, 2);
    RANDOM_RANGE = (float) Math.random() * 50;

    if (engine != Global.getCombatEngine()) {
      this.engine = Global.getCombatEngine();
    }
  }

  //////////////////////
  //   MAIN AI LOOP   //
  //////////////////////

  @Override
  public void advance(float amount) {

    // skip the AI if the game is paused, the missile is engineless or fading
    if (Global.getCombatEngine().isPaused() || missile.isFading() || missile.isFizzling()) {
      return;
    }

    // assigning a target if there is none or it got destroyed
    if (target == null
        || (target instanceof ShipAPI
            && ((ShipAPI) target).isHulk()) // comment out this line to remove target reengagement
        || !engine.isEntityInPlay(target)
        || target.getCollisionClass() == CollisionClass.NONE // check for phasing ships
    ) {
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
      // forced acceleration by default
      missile.giveCommand(ShipCommand.ACCELERATE);
      if (Math.random() > 0.5) {
        direction = -direction;
      }
      return;
    }

    // choose between a heatseeker behavior, or a simple continuous circling of the target.
    boolean HEATSEEKER = false;
    if (!HEATSEEKER && empCharge <= 0) {
      engine.addHitParticle(
          missile.getLocation(),
          new Vector2f(),
          150,
          1,
          0.5f + 0.5f * (float) Math.random(),
          CORE_COLOR);
      for (int i = 0; i <= 10; i++) {
        engine.addHitParticle(
            missile.getLocation(),
            MathUtils.getRandomPointInCircle(new Vector2f(), 300),
            5 + 5 * (float) Math.random(),
            1,
            0.5f + 1 * (float) Math.random(),
            FRINGE_COLOR);
      }
      // public void applyDamage(CombatEntityAPI entity, Vector2f point, float damageAmount,
      // DamageType damageType, float empAmount, boolean bypassShields, boolean dealsSoftFlux,
      // Object source)
      engine.applyDamage(
          missile,
          missile.getLocation(),
          missile.getHitpoints() * 2,
          DamageType.FRAGMENTATION,
          0,
          true,
          false,
          missile.getSource());
      return;
    }

    timer += amount;
    // finding lead point to aim to
    if (launch || timer >= check) {
      launch = false;
      timer -= check;
      float dist = MathUtils.getDistanceSquared(missile.getLocation(), target.getLocation());

      // set the next check time
      check = Math.min(0.5f, Math.max(0.1f, 2 * dist / PRECISION_RANGE));

      if (!MathUtils.isWithinRange(missile, target, 500)) {
        // best intercepting point
        lead =
            AIUtils.getBestInterceptPoint(
                missile.getLocation(),
                MAX_SPEED
                    * eccm, // if eccm is intalled the point is accurate, otherwise it's placed
                            // closer to the target
                target.getLocation(),
                target.getVelocity());
        if (lead == null) {
          lead = target.getLocation();
        }
      } else {

        float traverse; // angle around the target to go to

        // get the direction from the ship to the missile
        float angleToMissile =
            VectorUtils.getFacing(
                VectorUtils.getDirectionalVector(target.getLocation(), missile.getLocation()));

        if (HEATSEEKER) {

          // HEATSEEKING BEHAVIOR

          traverse =
              MathUtils.getShortestRotation(
                  angleToMissile, target.getFacing() + 180); // get the angle of circling left

          // get the direction to circle to
          if (traverse <= 0) {
            direction = 1f;
          } else {
            direction = -1f;
          }

          float idealRadius =
              ( // radius used when the missile reached the vincinity of the target
                  Math.max(
                      target.getCollisionRadius() + 100, // minimal distance to not hit the shield
                      target.getCollisionRadius() / 3
                          + 300)) // more lenient distance for small targets
                  * ((float)
                      FastTrig.sin(
                          MathUtils.FPI
                              * Math.min(90, Math.abs(traverse) - 30)
                              / 180)); // radius reduction as the missile get closer to the rear
                                       // axis

          float fallbackRadius =
              MathUtils.getDistance(missile.getLocation(), target.getLocation())
                  - 100; // Radius used when the missile is aproaching the target but already faces
                         // its rear. Prevent early rushes to the hull in case the target sudently
                         // turns around

          circlingRadius =
              Math.min(
                  circlingRadius, // previous radius, prevent going back away from the target if the
                                  // missile slightly overshoot the rear
                  Math.max(
                      idealRadius,
                      fallbackRadius) // choose the larger of either the ideal or fallback circling
                                      // radius
                  );

        } else {

          // ENDLESS CIRCLING BEHAVIOR

          traverse = angleToMissile + 25 * direction;
          circlingRadius =
              Math.max(
                  target.getCollisionRadius() + 100, // minimal distance to not hit the shield
                  target.getCollisionRadius() / 3
                      + 300); // more lenient distance for small targets
        }

        float targetAngle =
            angleToMissile
                - direction
                    * (Math.min(
                        25, Math.abs(traverse * 0.9f))); // prevent going beyond the rear alignment

        lead = MathUtils.getPoint(target.getLocation(), circlingRadius - RANDOM_RANGE, targetAngle);
        // debug
        //                engine.addHitParticle(lead, new Vector2f(), 5, 1, 1, Color.red);

      }
      // null pointer protection
      if (lead == null) {
        lead = target.getLocation();
      }

      // check distance for arcing
      if (MathUtils.isWithinRange(missile, target, 250) && empCharge > 0) {
        arcing = true;
      } else {
        arcing = false;
        engine.addHitParticle(
            missile.getLocation(),
            missile.getVelocity(),
            50 + 25 * (float) Math.random(),
            0.5f,
            0.1f,
            FRINGE_COLOR);
      }
    }

    // best angle for interception
    float aimAngle =
        MathUtils.getShortestRotation(
            missile.getFacing(), VectorUtils.getAngle(missile.getLocation(), lead));

    // angle beyond which the missile turn around without accelerating. Avoid endless circling.
    float OVERSHOT_ANGLE = 60;
    if (Math.abs(aimAngle) < OVERSHOT_ANGLE) {
      missile.giveCommand(ShipCommand.ACCELERATE);
    }

    if (aimAngle < 0) {
      missile.giveCommand(ShipCommand.TURN_RIGHT);
    } else {
      missile.giveCommand(ShipCommand.TURN_LEFT);
    }

    // Damp angular velocity if the missile aim is getting close to the targeted angle
    // Damping of the turn speed when closing on the desired aim. The smaller the snappier.
    float DAMPING = 0.1f;
    if (Math.abs(aimAngle) < Math.abs(missile.getAngularVelocity()) * DAMPING) {
      missile.setAngularVelocity(aimAngle / DAMPING);
    }
    if (arcing) {
      empArcing(amount);
    }
  }

  void empArcing(float time) {
    if (target instanceof ShipAPI) {
      arcTimer += time;
      if (arcTimer >= arcRandomness) {
        arcTimer -= arcRandomness;
        float ARC_DELAY = 0.3f;
        arcRandomness = ARC_DELAY - 0.2f + ((float) Math.random() * 0.4f);
        empCharge--;
        // public CombatEntityAPI spawnEmpArc(ShipAPI damageSource, Vector2f point, CombatEntityAPI
        // pointAnchor, CombatEntityAPI empTargetEntity, DamageType damageType, float damAmount,
        // float empDamAmount, float maxRange, String impactSoundId, float thickness, Color fringe,
        // Color core)
        if (Math.random() / 2
            < ((ShipAPI) target).getFluxTracker().getHardFlux()
                / ((ShipAPI) target).getFluxTracker().getMaxFlux()) {
          engine.spawnEmpArcPierceShields(
              missile.getSource(),
              missile.getLocation(),
              null,
              target,
              DamageType.FRAGMENTATION,
              0,
              100,
              1000,
              "tachyon_lance_emp_impact",
              5 + 3 * (float) Math.random(),
              FRINGE_COLOR,
              CORE_COLOR);
        } else {
          engine.spawnEmpArc(
              missile.getSource(),
              missile.getLocation(),
              null,
              target,
              DamageType.FRAGMENTATION,
              0,
              200,
              1000,
              "tachyon_lance_emp_impact",
              5 + 3 * (float) Math.random(),
              FRINGE_COLOR,
              CORE_COLOR);
        }
        if (MagicRender.screenCheck(0.25f, missile.getLocation())) {
          MagicLensFlare.createSharpFlare(
              engine,
              missile.getSource(),
              missile.getLocation(),
              3,
              200,
              0,
              FRINGE_COLOR,
              CORE_COLOR);
        }
      }
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

// By Tartiflette,
// V2 done
package data.scripts.ai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.util.Misc;
import org.magiclib.util.MagicTargeting;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_clusterTorpedoAI implements MissileAIPlugin, GuidedMissileAI {
  // max speed of the missile after modifiers
  private final float MAX_SPEED;
  // range under which the missile start to get progressively more precise in game units.
  private float PRECISION_RANGE = 1000;
  // Random starting offset for the waving.
  private final float OFFSET;

  private final float DROP_DELAY = 0.1f;

  private CombatEngineAPI engine;
  private final MissileAPI missile;
  private CombatEntityAPI target;
  private Vector2f lead = new Vector2f();
  private boolean launch = true, dropping = false;
  private float eccm = 3, timer = 0, check = 0f, drops = 5, dropTimer = 0;

  //////////////////////
  //  DATA COLLECTING //
  //////////////////////

  public SCY_clusterTorpedoAI(MissileAPI missile, ShipAPI launchingShip) {
    this.missile = missile;
    MAX_SPEED = missile.getMaxSpeed();
    if (missile.getSource().getVariant().getHullMods().contains("eccm")) {
      eccm = 1;
    }
    // calculate the precision range factor
    PRECISION_RANGE = (float) Math.pow(2 * PRECISION_RANGE, 2);
    OFFSET = (float) (Math.random() * MathUtils.FPI * 2);

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
    if (Global.getCombatEngine().isPaused()) {
      return;
    }

    // if the missile is close enough, drop the sub-munitions
    if (dropping && drops > 0) {
      if (target != null) {
        if (target instanceof ShipAPI) {
          calculateCarpetBombing(amount);
        } else {
          dropTimer += amount;
          if (dropTimer >= DROP_DELAY) {
            float angleA =
                VectorUtils.getFacing(missile.getVelocity()) + (float) (Math.random() * 45);
            float angleB =
                VectorUtils.getFacing(missile.getVelocity()) + (float) (Math.random() * -45);

            Vector2f motionA =
                MathUtils.getPoint(new Vector2f(), missile.getVelocity().length(), angleA);
            Vector2f motionB =
                MathUtils.getPoint(new Vector2f(), missile.getVelocity().length(), angleB);

            applyCarpetBombingEffect(missile, motionA, motionB);
            drops--;

            if (drops <= 0) {
              engine.spawnProjectile(
                  missile.getSource(),
                  missile.getWeapon(),
                  "SCY_clusterempty",
                  missile.getLocation(),
                  missile.getFacing(),
                  missile.getVelocity());
              missile.flameOut();
              engine.removeEntity(missile);
            }
          }
        }
      } else {
        return;
      }
    } else if (missile.isFading() || missile.isFizzling()) {
      return;
    }

    // assigning a target if there is none or it got destroyed
    if (target == null
        || (target instanceof ShipAPI
            && ((ShipAPI) target).isHulk()) // comment out this line to remove target reengagement
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
      // forced acceleration by default
      missile.giveCommand(ShipCommand.ACCELERATE);
      return;
    }

    timer += amount;
    // finding lead point to aim to
    if (launch || timer >= check) {
      launch = false;
      timer -= check;
      float dist = MathUtils.getDistanceSquared(missile.getLocation(), target.getLocation());
      // set the next check time
      check = Math.min(0.5f, Math.max(0.05f, 2 * dist / PRECISION_RANGE));
      // best intercepting point
      lead =
          AIUtils.getBestInterceptPoint(
              missile.getLocation(),
              MAX_SPEED
                  * eccm, // if eccm is intalled the point is accurate, otherwise it's placed closer
                          // to the target
              target.getLocation(),
              target.getVelocity());
      // null pointer protection
      if (lead == null) {
        lead = target.getLocation();
      }
      // check for drop
      float DROP_RANGE = 700;
      if (missile.getElapsed() > 1 && dist < (float) Math.pow(DROP_RANGE, 2)) {
        dropping = true;
      }
    }

    // best angle for interception
    float aimAngle =
        MathUtils.getShortestRotation(
            missile.getFacing(), VectorUtils.getAngle(missile.getLocation(), lead));

    // angle beyond which the missile turn around without accelerating. Avoid endless circling.
    float OVERSHOT_ANGLE = 25;
    if (Math.abs(aimAngle) < OVERSHOT_ANGLE) {
      missile.giveCommand(ShipCommand.ACCELERATE);
    }

    // waving
    // angle of the waving in degree (divided by 3 with ECCM)
    float WAVE_AMPLITUDE = 6;
    // time to complete a wave in seconds.
    float WAVE_TIME = 3;
    aimAngle +=
        WAVE_AMPLITUDE
            * 4
            / 3
            * check
            * eccm
            * Math.cos(OFFSET + missile.getElapsed() * (2 * MathUtils.FPI / WAVE_TIME));

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
  }

  //////////////////////
  //   CARPET CALC    //
  //////////////////////

  public void calculateCarpetBombing(float amount) {
    dropTimer += amount;
    if (dropTimer >= DROP_DELAY) {
      dropTimer -= DROP_DELAY;
      Vector2f posA = new Vector2f();
      Vector2f posB = new Vector2f();
      VectorUtils.rotateAroundPivot(missile.getLocation(), target.getLocation(), 90, posA);
      VectorUtils.rotateAroundPivot(missile.getLocation(), target.getLocation(), -90, posB);
      // get the visible width of the collision oval
      float visibleRadius =
          Misc.getTargetingRadius(posA, target, false)
              + Misc.getTargetingRadius(posB, target, false);
      // find the apparent profile angle
      float apparentAngle =
          (float) Math.atan(visibleRadius / MathUtils.getDistance(missile, target)) / 3f;
      apparentAngle *= 180 / MathUtils.FPI;
      // correct some of the drifting
      float offCourse =
          Math.min(
              10,
              Math.max(
                  -10,
                  MathUtils.getShortestRotation(
                      VectorUtils.getFacing(missile.getVelocity()),
                      VectorUtils.getAngle(missile.getLocation(), lead))));
      // spread the bomblets in the attack cone
      float angleA =
          VectorUtils.getFacing(missile.getVelocity())
              + (float) (Math.random() * 2 - 1)
              + offCourse
              + apparentAngle * (drops / 5);
      float angleB =
          VectorUtils.getFacing(missile.getVelocity())
              + (float) (Math.random() * 2 - 1)
              + offCourse
              - apparentAngle * (drops / 5);

      Vector2f motionA = MathUtils.getPoint(new Vector2f(), missile.getVelocity().length(), angleA);
      Vector2f motionB = MathUtils.getPoint(new Vector2f(), missile.getVelocity().length(), angleB);

      applyCarpetBombingEffect(missile, motionA, motionB);
      drops--;

      if (drops <= 0) {
        engine.spawnProjectile(
            missile.getSource(),
            missile.getWeapon(),
            "SCY_clusterempty",
            missile.getLocation(),
            missile.getFacing(),
            missile.getVelocity());
        missile.flameOut();
        engine.removeEntity(missile);
      }
    }
  }

  //////////////////////
  //  CARPET BOMBING  //
  //////////////////////

  public void applyCarpetBombingEffect(MissileAPI missile, Vector2f motionA, Vector2f motionB) {
    Vector2f mLoc = missile.getLocation();
    ShipAPI mSource = missile.getSource();
    // public CombatEntityAPI spawnProjectile(ShipAPI ship, WeaponAPI weapon, String weaponId,
    // Vector2f point, float angle, Vector2f shipVelocity)
    engine.spawnProjectile(
        mSource,
        missile.getWeapon(),
        "SCY_clusterbomb",
        mLoc,
        missile.getFacing() + MathUtils.getRandomNumberInRange(45, 135),
        motionA);
    engine.spawnProjectile(
        mSource,
        missile.getWeapon(),
        "SCY_clusterbomb",
        mLoc,
        missile.getFacing() - MathUtils.getRandomNumberInRange(45, 135),
        motionB);
    missile.setDamageAmount(missile.getBaseDamageAmount() - 600);
    // play sound
    Global.getSoundPlayer().playSound("bomb_bay_fire", 1f, 1f, mLoc, missile.getVelocity());
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

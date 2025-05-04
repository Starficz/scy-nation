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
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicTargeting;
import org.lazywizard.lazylib.FastTrig;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_swarmerAI implements MissileAIPlugin, GuidedMissileAI {

  private CombatEngineAPI engine;
  private final MissileAPI missile;
  private CombatEntityAPI target;
  private boolean launch = true, isSeeking = true;
  private Vector2f lead = new Vector2f();
  private float eccm = 3, seeking = 0;
  private final IntervalUtil timer = new IntervalUtil(0.05f, 0.15f);
  // data
  private final float MAX_SPEED;
  private final float OFFSET, SEEKING_DONE;

  public SCY_swarmerAI(MissileAPI missile, ShipAPI launchingShip) {
    this.missile = missile;
    MAX_SPEED = missile.getMaxSpeed();
    if (missile.getSource().getVariant().getHullMods().contains("eccm")) {
      eccm = 1;
    }
    OFFSET = (float) (Math.random() * MathUtils.FPI * 2);
    SEEKING_DONE = 0.25f + 0.75f * (float) Math.random();
    timer.randomize();
  }

  @Override
  public void advance(float amount) {

    if (engine != Global.getCombatEngine()) {
      this.engine = Global.getCombatEngine();
    }

    // skip AI if the missile is engineless or the game paused
    if (Global.getCombatEngine().isPaused() || missile.isFading() || missile.isFizzling()) {
      return;
    }

    // The missile take some time before starting to seek it's target
    if (isSeeking) {
      seeking += amount;
      if (seeking >= SEEKING_DONE) {
        isSeeking = false;
        setTarget(
            MagicTargeting.pickTarget(
                missile, MagicTargeting.targetSeeking.LOCAL_RANDOM, 750, 360, 3, 2, 1, 1, 1, true));
        launch = true;
      }
      return;
    }

    // if the missile has no target, pick the nearest one
    if (target == null
        || (target instanceof ShipAPI && !((ShipAPI) target).isAlive())
        || !engine.isEntityInPlay(target)
        || target.getCollisionClass() == CollisionClass.NONE) {
      missile.giveCommand(ShipCommand.ACCELERATE);
      setTarget(
          MagicTargeting.pickTarget(
              missile, MagicTargeting.targetSeeking.FULL_RANDOM, 500, 360, 3, 2, 1, 1, 1, true));
      return;
    }

    timer.advance(amount);
    // finding lead point to aim to
    if (launch || timer.intervalElapsed()) {
      launch = false;
      lead =
          leadPoint(
              target.getLocation(),
              new Vector2f(target.getVelocity()),
              missile.getLocation(),
              MAX_SPEED * eccm);
    }

    // best angle for interception
    float aimAngle =
        MathUtils.getShortestRotation(
            missile.getFacing(), VectorUtils.getAngle(missile.getLocation(), lead));

    // waving
    // angle of the waving in degree (divided by 3 with ECCM)
    float WAVE_AMPLITUDE = 10;
    // time to complete a wave in seconds.
    float WAVE_TIME = 2;
    aimAngle +=
        WAVE_AMPLITUDE
            * eccm
            * FastTrig.cos(OFFSET + missile.getElapsed() * (2 * MathUtils.FPI / WAVE_TIME));

    missile.giveCommand(ShipCommand.ACCELERATE);
    if (aimAngle < 0) {
      missile.giveCommand(ShipCommand.TURN_RIGHT);
    } else {
      missile.giveCommand(ShipCommand.TURN_LEFT);
    }

    // Damp angular velocity if the missile aim is getting close to the targeted angle
    float DAMPING = 0.1f;
    if (Math.abs(aimAngle) < Math.abs(missile.getAngularVelocity()) * DAMPING) {
      missile.setAngularVelocity(aimAngle / DAMPING);
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

  private Vector2f leadPoint(
      Vector2f targetPoint, Vector2f targetVel, Vector2f projPoint, float projSpeed) {
    float time =
        (targetPoint.x - projPoint.x) * (targetPoint.x - projPoint.x)
            + (targetPoint.y - projPoint.y)
            + (targetPoint.y - projPoint.y); // distance squared
    time = (float) Math.sqrt(time); // distance
    time /= projSpeed; // divided by proj speed

    Vector2f leadPoint = targetVel;
    leadPoint.scale(time);
    Vector2f.add(leadPoint, targetPoint, leadPoint);
    return leadPoint;
  }
}

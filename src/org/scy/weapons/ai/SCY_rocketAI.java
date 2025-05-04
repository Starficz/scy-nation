// By Tartiflette, simple and fast rocket AI that will try to attack a target in a frontal cone, and
// not reengage any if it misses.
// V2 done
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
import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_rocketAI implements MissileAIPlugin, GuidedMissileAI {

  CombatEngineAPI engine;
  private final MissileAPI missile;
  private CombatEntityAPI target;
  private Vector2f lead = new Vector2f(), offset = new Vector2f();
  private boolean launch = true;
  private float eccm = 1.5f, timer = 0, check = 0.1f;
  // data
  private final float MAX_SPEED;

    //////////////////////
  //  DATA COLLECTING //
  //////////////////////

  public SCY_rocketAI(MissileAPI missile, ShipAPI launchingShip) {

    if (engine != Global.getCombatEngine()) {
      this.engine = Global.getCombatEngine();
    }

    this.missile = missile;
    MAX_SPEED = missile.getMaxSpeed();
    if (missile.getSource().getVariant().getHullMods().contains("eccm")) {
      eccm = 1;
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

    // forced acceleration by default
    missile.giveCommand(ShipCommand.ACCELERATE);

    if (launch) {
      launch = false;
        int SEARCH_CONE = 120;
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
        // pick a random point inside the ship
        offset =
            MathUtils.getRandomPointInCircle(
                new Vector2f(), target.getCollisionRadius() * eccm / 4);
        if (!CollisionUtils.isPointWithinBounds(
            new Vector2f(offset.x + target.getLocation().x, offset.y + target.getLocation().y),
            target)) {
          offset = new Vector2f();
        }
      }
      return;
    } else if (target == null || target.getCollisionClass() == CollisionClass.NONE) {
      return;
    }

    timer += amount;
    // finding lead point to aim to
    if (launch || timer >= check) {
      launch = false;
      timer = 0;
      float dist = MathUtils.getDistanceSquared(missile.getLocation(), target.getLocation());
      // set the next check time
      check = Math.min(0.5f, Math.max(0.05f, 2 * dist / 1000000));
      lead =
          AIUtils.getBestInterceptPoint(
              missile.getLocation(), MAX_SPEED * eccm, target.getLocation(), target.getVelocity());
      if (lead == null) {
        lead = target.getLocation();
      }

      Vector2f.add(lead, offset, lead);
    }

    //        //debug
    //        engine.addHitParticle(lead, new Vector2f(), 10, 10, 0.1f, Color.red);

    // best angle for interception
    float aimAngle =
        MathUtils.getShortestRotation(
            missile.getFacing(), VectorUtils.getAngle(missile.getLocation(), lead));

    if (aimAngle < 0) {
      missile.giveCommand(ShipCommand.TURN_RIGHT);
    } else {
      missile.giveCommand(ShipCommand.TURN_LEFT);
    }

    // Damp angular velocity if the missile aim is getting close to the targeted angle
      float DAMPING = 0.05f;
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
}

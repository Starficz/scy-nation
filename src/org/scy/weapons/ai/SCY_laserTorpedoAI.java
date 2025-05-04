// By Tartiflette
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
import org.magiclib.util.MagicFakeBeam;
import org.magiclib.util.MagicRender;
import org.magiclib.util.MagicTargeting;
import java.awt.Color;
import org.lazywizard.lazylib.FastTrig;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lazywizard.lazylib.combat.entities.SimpleEntity;
import org.lwjgl.util.vector.Vector2f;

public class SCY_laserTorpedoAI implements MissileAIPlugin, GuidedMissileAI {

  private CombatEngineAPI engine;
  private final MissileAPI missile;
  private CombatEntityAPI target;
  private Vector2f lead = new Vector2f();

  private float timer = 0, check = 0, eccm = 3;
  private boolean launch = true;

  private final float OFFSET;
  private final float MAX_SPEED;
  private float spreadangle = 0;
  private float effectiveSpread = 0;
  // LASERHEAD DATA
  // beam numbers
  private final int lasers = 10;
  // beam MAX range
  private float beamRange = 600;
  // beam burst DAMAGE
  private float defaultDamage = 300;
  // beam damage TYPE
  private final DamageType TYPE;
  // impact COLOR
  private final Color COLOR = new Color(50, 150, 255, 255);

  //////////////////////
  //                  //
  //  DATA COLLECTING //
  //                  //
  //////////////////////

  public SCY_laserTorpedoAI(MissileAPI missile, ShipAPI launchingShip) {
    this.missile = missile;
    MAX_SPEED = missile.getMaxSpeed();
    TYPE = missile.getDamageType();
    spreadangle = MathUtils.getRandomNumberInRange(-45, 45);
    if (missile.getSource().getVariant().getHullMods().contains("advancedoptics")) {
      beamRange = 800;
    }

    defaultDamage =
        missile.getDamageAmount() // total damage after skills
            //                missile.getWeapon().getDamage().getDamage() //total damage
            //                *
            //
            // missile.getSource().getMutableStats().getMissileWeaponDamageMult().getModifiedValue()
            // //multiplied by modifier
            / lasers // divided by the number or rays
            * 1.5f; // plus buff just because

    missile.setDamageAmount(missile.getBaseDamageAmount() * 0.5f);

    if (missile.getSource().getVariant().getHullMods().contains("eccm")) {
      eccm = 1;
    }
    OFFSET = (float) (Math.random() * MathUtils.FPI * 2);

    if (engine != Global.getCombatEngine()) {
      this.engine = Global.getCombatEngine();
    }
  }

  //////////////////////
  //                  //
  //   MAIN AI LOOP   //
  //                  //
  //////////////////////

  @Override
  public void advance(float amount) {

    // cancelling IF: skip the AI if the game is paused, the missile is engineless or fading
    if (Global.getCombatEngine().isPaused() || missile.isFading()) {
      return;
    }

    // assigning a target if there is none already
    if (target == null
        || (target instanceof ShipAPI && ((ShipAPI) target).isHulk())
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
      float PRECISION_RANGE = 1000;
      check = Math.min(0.5f, Math.max(0.1f, dist / PRECISION_RANGE));

      // dampen the spread
      effectiveSpread = spreadangle * Math.max(0, Math.min(1, -0.2f + dist / 1000000));

      float TRIGGER = 300;
      if (!MathUtils.isWithinRange(missile, target, TRIGGER * 2)) {
        lead =
            AIUtils.getBestInterceptPoint(
                missile.getLocation(),
                MAX_SPEED * eccm,
                target.getLocation(),
                target.getVelocity());
        if (lead == null) {
          lead = target.getLocation();
        }
      } else {
        lead = target.getLocation();
      }

      // laserhead trigger
      if (target instanceof ShipAPI) {
        boolean timeTrig = missile.getElapsed() > 0.5f;
        boolean distTrig;
        if (target.getShield() == null || target.getShield().isOff()) {
          distTrig =
              MathUtils.isWithinRange(
                  missile.getLocation(), target.getLocation(), beamRange * 0.66f);
        } else {
          distTrig = MathUtils.isWithinRange(missile, target, beamRange * 0.66f);
        }
        boolean aimTrig =
            Math.abs(
                    MathUtils.getShortestRotation(
                        VectorUtils.getAngle(missile.getLocation(), target.getLocation()),
                        missile.getFacing()))
                < 10;

        //                boolean friendTrig = false;
        //                for (CombatEntityAPI e :
        // CombatUtils.getEntitiesWithinRange(missile.getLocation(), beamRange*0.33f)){
        //                    if (e.getOwner()==missile.getSource().getOwner()) continue;
        //
        //                    if(e.getCollisionClass()==CollisionClass.SHIP) continue;
        //
        //                    if(
        //                            MathUtils.getDistanceSquared(missile.getLocation(),
        // e.getLocation())
        //                            <
        //                            MathUtils.getDistanceSquared(missile.getLocation(),
        // target.getLocation())
        //                            ) continue;
        //
        //                    if(Math.abs(
        //                                MathUtils.getShortestRotation(
        //                                        missile.getFacing(),
        //                                        VectorUtils.getAngle(
        //                                                missile.getLocation(),
        //                                                e.getLocation()
        //                                        )
        //                                )
        //                            )<(45)){
        //                        friendTrig = true;
        //                    }
        //                }

        // if the missile is close enough and the target in sight, detonate the laser warhead
        if (!launch && timeTrig && distTrig && aimTrig
        //                        && !friendTrig
        ) {
          applyExplosionEffect(missile, lasers);
        }
        // If the missile has been lured by flares:
      } else if (MathUtils.isWithinRange(target, missile, beamRange * 0.5f)) {
        boolean timeTrig = missile.getElapsed() > 1f;
        boolean aimTrig =
            Math.abs(
                    MathUtils.getShortestRotation(
                        VectorUtils.getAngle(missile.getLocation(), target.getLocation()),
                        missile.getFacing()))
                < lasers / 2f;
        boolean friendTrig = false;
        for (CombatEntityAPI e :
            CombatUtils.getEntitiesWithinRange(missile.getLocation(), TRIGGER / 2)) {
          if (e.getOwner() == missile.getOwner()
              && Math.abs(
                      MathUtils.getShortestRotation(
                          missile.getFacing(),
                          VectorUtils.getAngle(missile.getLocation(), e.getLocation())))
                  < (lasers / 2f)) {
            friendTrig = true;
          }
        }
        if (!launch && timeTrig && aimTrig && !friendTrig) {
          applyExplosionEffect(missile, lasers);
        }
      }
    }

    // best angle for interception
    float aimAngle =
        MathUtils.getShortestRotation(
            missile.getFacing(), VectorUtils.getAngle(missile.getLocation(), lead));

    // waving
    // angle of the waving in degree (divided by 3 with ECCM)
    float WAVE_AMPLITUDE = 3;
    // time to complete a wave in seconds.
    float WAVE_TIME = 2;
    aimAngle +=
        WAVE_AMPLITUDE
            * 4
            / 3
            * check
            * eccm
            * FastTrig.cos(OFFSET + missile.getElapsed() * (2 * MathUtils.FPI / WAVE_TIME));
    // spread
    aimAngle += effectiveSpread;

    float OVERSHOT_ANGLE = 30;
    if (Math.abs(aimAngle) < OVERSHOT_ANGLE) {
      missile.giveCommand(ShipCommand.ACCELERATE);
    }
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

  //////////////////////
  //                  //
  //    LASERHEAD     //
  //                  //
  //////////////////////

  public void applyExplosionEffect(MissileAPI missile, float laserHead) {
    Vector2f mLoc = missile.getLocation();
    ShipAPI mSource = missile.getSource();
    if (MagicRender.screenCheck(0.5f, mLoc)) {
      // public void addHitParticle(Vector2f loc, Vector2f vel, float size, float brightness, float
      // duration, Color color)
      engine.spawnExplosion(mLoc, new Vector2f(0, 0), Color.BLACK, 60 * laserHead, 3);
      engine.spawnExplosion(
          mLoc, new Vector2f(0, 0), new Color(100, 50, 200), 50 * laserHead, 1.5f);
      engine.addHitParticle(
          mLoc, new Vector2f(0, 0), 40 * laserHead, 1, 1, new Color(100, 200, 255));
      engine.addHitParticle(mLoc, new Vector2f(0, 0), 30 * laserHead, 1, 1, Color.WHITE);
      // flare
      engine.spawnEmpArc(
          mSource, // damage dealt by
          mLoc, // start
          new SimpleEntity(new Vector2f(mLoc.x, mLoc.y)), // moves with
          new SimpleEntity(new Vector2f(mLoc.x, mLoc.y + 10f)), // target
          DamageType.ENERGY, // damage type
          0, // damage
          0, // emp
          500f, // max range
          null, // sound
          1000, // thickness
          new Color(100, 200, 255, 100), // fringe
          new Color(25, 50, 150, 100) // core
          );
    }
    // play sound
    Global.getSoundPlayer().playSound("SCY_laserHead", 1f, 1f, mLoc, new Vector2f());

    for (int x = 0; x < lasers; x++) {
      /*
      public static void spawnFakeBeam(
              CombatEngineAPI engine,
              Vector2f from,
              float range,
              float angle,
              float width,
              float full,
              float fading,
              float impactSize,
              Color core,
              Color fringe,
              float normalDamage,
              DamageType type,
              float emp,
              ShipAPI source
      )
      */
      float SIZE = 50;
      // beam EMP
      float EMP = 100;
      // beam WIDTH in su
      float WIDTH = 15;
      // beam DURATION in seconds
      float DURATION = 0.5f;
      MagicFakeBeam.spawnAdvancedFakeBeam(
          engine,
          mLoc,
          beamRange,
          missile.getFacing()
              + MathUtils.getRandomNumberInRange(
                  -lasers * (0.5f + eccm / 2), lasers * (0.5f + eccm / 2)),
          WIDTH / 2,
          WIDTH / 3,
          WIDTH / 2,
          "base_trail_rough",
          "base_trail_aura",
          512,
          2048,
          20,
          20,
          0.15f,
          DURATION / 2 + (float) Math.random() * DURATION / 2,
              SIZE, // impact size
          Color.WHITE,
          COLOR, // impact color
          defaultDamage,
          TYPE,
              EMP,
          mSource // damage source
          );
    }
    engine.removeEntity(missile);
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

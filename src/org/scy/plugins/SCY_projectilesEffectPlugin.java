// by Tartiflette, using Debido's Area Scorcher script for some part. This script manage all
// everyframe projectiles effect for all Scyan weapons,
package org.scy.plugins;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import java.util.ArrayList;
// import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lazywizard.lazylib.combat.entities.SimpleEntity;
// import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
// import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import org.lwjgl.util.vector.Vector2f;

public class SCY_projectilesEffectPlugin extends BaseEveryFrameCombatPlugin {
  // projectiles to catch
  private static final String area_ID = "SCY_scorcherS";
  private static final String ricochet_ID = "SCY_ricochetS";
  private static final String teleporter_ID = "SCY_teleporterShell_shot";

  // effects
  private static final Color EXPLOSION_COLOR = new Color(250, 100, 50, 150);
  private static final Color FLASH_COLOR = new Color(50, 200, 250, 255);
  private static final Color SMOKE1_COLOR = new Color(100, 100, 100, 200);
  private static final Color SMOKE2_COLOR = new Color(75, 75, 75, 175);
  private static final Color SMOKE3_COLOR = new Color(40, 40, 40, 130);
  private static final Color SMOKE4_COLOR = new Color(20, 20, 20, 100);
  private static final String SOUND_ID = "explosion_from_damage";

  private static final IntervalUtil globalTimer = new IntervalUtil(0.05f, 0.05f);

  // SCORCHER VARIABLES
  private static final Map<DamagingProjectileAPI, Integer> SCORCHER = new WeakHashMap<>();
  private static final Map<ShipAPI, Float> FLAKED = new WeakHashMap<>();
  private static final String FLAK_ID = "scy_flakDebuff";
  private static final IntervalUtil scorcherTimer = new IntervalUtil(0.05f, 0.05f);
  private static final float RADIUS = 150f, SUBSHOTS = 10f, FLAK_DAMAGE = 50;

  // TELEPORT VARIABLE
  private static final Map<CombatEntityAPI, Vector2f> TELEPORT = new WeakHashMap<>();

  // SINGULARITY VARIABLES
  private static final float NEWTON = 2f;
  private static final List<singularityData> SINGULARITIES = new ArrayList<>();
  private static final String slow_ID = "SCY_singularityEffect";
  private static final IntervalUtil singularityTime = new IntervalUtil(0.05f, 0.05f);
  private static final Color CORE = new Color(180, 200, 255), FRINGE = new Color(50, 70, 200);

  // ANTIMISSILE VARIABLES
  private static final Map<MissileAPI, MissileAPI> ANTIMISSILES = new WeakHashMap<>();
  private static boolean forceCheck = false;

  //////////////////////////////
  //                          //
  //        MAIN LOOP         //
  //                          //
  //////////////////////////////
  @Override
  public void init(CombatEngineAPI engine) {
    // reinitialize the map
    //        ORION.clear();
    SCORCHER.clear();
    FLAKED.clear();
    SINGULARITIES.clear();
    ANTIMISSILES.clear();
    TELEPORT.clear();
  }

  public static void cleanSlate() {
    SINGULARITIES.clear();
    ANTIMISSILES.clear();
    TELEPORT.clear();
  }

  @Override
  public void advance(float amount, List events) {

    CombatEngineAPI engine = Global.getCombatEngine();

    if (engine.isPaused()) {
      return;
    }

    globalTimer.advance(amount);
    if (globalTimer.intervalElapsed()) {

      // list all projectiles
      List<DamagingProjectileAPI> projectiles = engine.getProjectiles();
      if (!projectiles.isEmpty()) {
        for (DamagingProjectileAPI p : projectiles) {
          // sort SCY missiles first
          if (p instanceof MissileAPI
              && p.getProjectileSpecId() != null
              && p.getProjectileSpecId().startsWith("SCY")) {
            // self-destruction effect if fading
            if (p.isFading()) {
              applySelfDestruct((MissileAPI) p, engine);
            }
          }
          // then bullets
          else {
            if (p.getProjectileSpecId() != null && p.getProjectileSpecId().startsWith("SCY")) {
              switch (p.getProjectileSpecId()) {
                case area_ID:
                  boolean proxFuse = false;
                  ShipAPI s = AIUtils.getNearestEnemy(p);
                  if (s != null && MathUtils.isWithinRange(p.getLocation(), s.getLocation(), 50)) {
                    proxFuse = true;
                  } else {
                    MissileAPI m = AIUtils.getNearestEnemyMissile(p);
                    if (m != null
                        && MathUtils.isWithinRange(p.getLocation(), m.getLocation(), 100)) {
                      proxFuse = true;
                    }
                  }
                  if (proxFuse || p.isFading()) {
                    DamagingProjectileAPI flak = p;
                    SCORCHER.put(flak, 0);
                    engine.removeEntity(p);
                  }
                  p.setDamageAmount(FLAK_DAMAGE);
                  break;

                case ricochet_ID:
                  if (p.isFading() && !p.didDamage()) {
                    applyRicochetEffect(p, engine);
                  }
                  break;

                case teleporter_ID:
                  p.setDamageAmount(0);
                  break;

                  //                                case orionFake_ID:
                  //                                    engine.removeEntity(p);
                  //                                    break;
                  //
                  //                                case orion_ID:
                  //
                  //                                    if(!ORION.containsKey(p)){
                  //                                        ORION.put(p,
                  // MagicTrailPlugin.getUniqueID());
                  //                                    }
                  //
                  //                                    applyOrionTrail(p);
                  //
                  //                                    if(p.getSource().isAlive() &&
                  // p.getDamageAmount()>25){
                  //                                        applyOrionEffect(p);
                  //                                    }

                  //                                    break;
              }
            }
          }
        }
      }
      // check antimissiles timed
      if (!ANTIMISSILES.isEmpty()) {
        checkAntimissiles(engine);
      }
    }
    // forced antimissile check
    if (forceCheck) {
      checkAntimissiles(engine);
    }

    // check for Scorcher explosions
    if (!SCORCHER.isEmpty()) {
      applyScorcher(amount, engine);
    }

    // check teleportations timed
    if (!TELEPORT.isEmpty()) {
      teleport(amount, engine);
    }

    // check for ongoing Singularities
    if (!SINGULARITIES.isEmpty()) {
      applySingularityEffect(amount, engine);
    }
  }

  //////////////////////////////
  //                          //
  //       AREA EFFECT        //
  //                          //
  //////////////////////////////

  private void applyScorcher(float amount, CombatEngineAPI engine) {

    scorcherTimer.advance(amount);
    if (scorcherTimer.intervalElapsed()) {

      // dig through the SCORCHER member
      for (Iterator<Map.Entry<DamagingProjectileAPI, Integer>> iter =
              SCORCHER.entrySet().iterator();
          iter.hasNext(); ) {
        Map.Entry<DamagingProjectileAPI, Integer> entry = iter.next();

        float bonus;
        if (entry.getKey().getSource() != null) {
          bonus =
              1
                  - Math.min(
                      1,
                      Math.max(
                          0,
                          entry
                              .getKey()
                              .getSource()
                              .getMutableStats()
                              .getAutofireAimAccuracy()
                              .getModifiedValue()));
        } else {
          bonus = 1;
        }

        if (entry.getValue() >= SUBSHOTS) {
          iter.remove();
        } else {
          // add a random number of sub shot explosions
          float random = 1 + (float) Math.random() * 3;
          for (int i = 0; i <= random; i++) {

            Vector2f cPoint =
                MathUtils.getRandomPointOnCircumference(
                    entry.getKey().getLocation(),
                    (1 + entry.getValue()) * RADIUS / SUBSHOTS * (0.75f + (bonus / 4)));

            // add an explosion effect at that point
            //                        for (int u = 0; u <= 1; u++) {
            engine.addHitParticle(
                cPoint,
                MathUtils.getRandomPointOnCircumference(new Vector2f(), 300f),
                MathUtils.getRandomNumberInRange(2f, 4f),
                15f,
                0.35f,
                new Color(249, 246, 202));
            //                        }

            if (MagicRender.screenCheck(0.5f, cPoint)) {
              // void spawnExplosion(Vector2f loc, Vector2f vel, Color color, float size, float
              // maxDuration);
              engine.spawnExplosion(
                  cPoint,
                  new Vector2f(),
                  EXPLOSION_COLOR,
                  MathUtils.getRandomNumberInRange(50f, 80f),
                  0.1f);
              engine.spawnExplosion(
                  cPoint,
                  new Vector2f(),
                  FLASH_COLOR,
                  MathUtils.getRandomNumberInRange(30f, 50f),
                  0.1f);

              // add a bit of smoke
              // public void addSmokeParticle(Vector2f loc, Vector2f vel, float size, float opacity,
              // float duration, Color color);
              engine.addSmokeParticle(
                  cPoint,
                  new Vector2f(),
                  MathUtils.getRandomNumberInRange(
                      50f - entry.getValue() * 2, 70f - entry.getValue()),
                  0.1f,
                  3f,
                  SMOKE4_COLOR);
              engine.addSmokeParticle(
                  cPoint,
                  new Vector2f(),
                  MathUtils.getRandomNumberInRange(
                      45f - entry.getValue() * 2, 60f - entry.getValue()),
                  0.1f,
                  1.5f,
                  SMOKE3_COLOR);
              engine.addSmokeParticle(
                  cPoint,
                  new Vector2f(),
                  MathUtils.getRandomNumberInRange(
                      40f - entry.getValue() * 2, 50f - entry.getValue()),
                  0.1f,
                  0.75f,
                  SMOKE2_COLOR);
              engine.addSmokeParticle(
                  cPoint,
                  new Vector2f(),
                  MathUtils.getRandomNumberInRange(
                      35f - entry.getValue() * 2, 40f - entry.getValue()),
                  0.1f,
                  0.25f,
                  SMOKE1_COLOR);
            }

            // damage nearby missiles
            List<MissileAPI> nearbyMissiles = CombatUtils.getMissilesWithinRange(cPoint, 100f);
            if (!nearbyMissiles.isEmpty()) {
              for (MissileAPI missile : nearbyMissiles) {
                if (missile.getCollisionClass() != CollisionClass.NONE) {
                  engine.applyDamage(
                      missile,
                      cPoint,
                      FLAK_DAMAGE,
                      DamageType.FRAGMENTATION,
                      0f,
                      false,
                      false,
                      entry.getKey().getSource());
                }
              }
            }

            // damage nearby ships
            List<ShipAPI> nearbyShips = CombatUtils.getShipsWithinRange(cPoint, 100f);
            if (!nearbyShips.isEmpty()) {
              for (ShipAPI ship : nearbyShips) {
                if (ship != entry.getKey().getSource()
                    && ship.getCollisionClass() != CollisionClass.NONE) {
                  engine.applyDamage(
                      ship,
                      cPoint,
                      FLAK_DAMAGE,
                      DamageType.FRAGMENTATION,
                      0f,
                      false,
                      false,
                      entry.getKey().getSource());
                  // debuff weapons
                  FLAKED.put(ship, 2f);
                  ship.getMutableStats().getAutofireAimAccuracy().modifyMult(FLAK_ID, 0.25f);
                  ship.getMutableStats().getBallisticWeaponDamageMult().modifyMult(FLAK_ID, 0.75f);
                  ship.getMutableStats().getBeamWeaponDamageMult().modifyMult(FLAK_ID, 0.75f);
                  ship.getMutableStats().getEnergyWeaponDamageMult().modifyMult(FLAK_ID, 0.75f);
                  ship.getMutableStats().getWeaponTurnRateBonus().modifyMult(FLAK_ID, 0.75f);
                  ship.getMutableStats().getWeaponDamageTakenMult().modifyMult(FLAK_ID, 2f);
                }
              }
            }
            // add one explosion to the counter
            SCORCHER.put(entry.getKey(), entry.getValue() + 1);
          }
          // play the sound only once per step
          Global.getSoundPlayer()
              .playSound(
                  SOUND_ID,
                  MathUtils.getRandomNumberInRange(0.25f, 0.75f),
                  (10f - (float) entry.getValue()) / 10f,
                  entry.getKey().getLocation(),
                  new Vector2f());
        }
      }

      // now check the ships under FLAK for weapon debuff
      for (Iterator<Map.Entry<ShipAPI, Float>> iter = FLAKED.entrySet().iterator();
          iter.hasNext(); ) {
        Map.Entry<ShipAPI, Float> entry = iter.next();
        if (!entry.getKey().isAlive() || entry.getValue() - scorcherTimer.getMaxInterval() < 0) {
          entry.getKey().getMutableStats().getAutofireAimAccuracy().unmodify(FLAK_ID);
          entry.getKey().getMutableStats().getBallisticWeaponDamageMult().unmodify(FLAK_ID);
          entry.getKey().getMutableStats().getBeamWeaponDamageMult().unmodify(FLAK_ID);
          entry.getKey().getMutableStats().getEnergyWeaponDamageMult().unmodify(FLAK_ID);
          entry.getKey().getMutableStats().getWeaponTurnRateBonus().unmodify(FLAK_ID);
          entry.getKey().getMutableStats().getWeaponDamageTakenMult().unmodify(FLAK_ID);
          iter.remove();
        } else {
          entry.setValue(entry.getValue() - scorcherTimer.getMaxInterval());
        }
      }
    }
  }

  //////////////////////////////
  //                          //
  //      MISSILES FUSE       //
  //                          //
  //////////////////////////////

  private void applySelfDestruct(MissileAPI missile, CombatEngineAPI engine) {
    engine.applyDamage(
        missile,
        missile.getLocation(),
        missile.getHitpoints() * 2f,
        DamageType.FRAGMENTATION,
        0f,
        false,
        false,
        missile);
  }

  //////////////////////////////
  //                          //
  //     RICOCHET EFFECT      //
  //                          //
  //////////////////////////////

  private void applyRicochetEffect(DamagingProjectileAPI shell, CombatEngineAPI engine) {
    ShipAPI t = AIUtils.getNearestEnemy(shell);
    if (t != null && MathUtils.isWithinRange(t, shell, 1000)) {

      // DEGRADING LEAD WITH ANGLE OFFSET
      Vector2f pLoc = shell.getLocation();
      Vector2f pVel = shell.getVelocity();
      // spawn projectile toward nearest enemy
      Vector2f tPoint = AIUtils.getBestInterceptPoint(pLoc, 1500, t.getLocation(), t.getVelocity());
      float angle;
      if (tPoint != null) {
        angle = VectorUtils.getAngle(pLoc, tPoint);
      } else {
        angle = VectorUtils.getAngle(pLoc, t.getLocation());
      }
      angle += 0.05 * MathUtils.getShortestRotation(angle, shell.getFacing());

      engine.spawnProjectile(
          shell.getSource(),
          shell.getWeapon(),
          "SCY_ricochetReplacement",
          pLoc,
          angle,
          new Vector2f());

      //            //NO LEAD, SMALL VELOCITY INHERITANCE VERSION
      //            Vector2f pLoc = shell.getLocation();
      //            Vector2f pVel = shell.getVelocity();
      //            //spawn projectile toward nearest enemy
      //
      //            float angle = VectorUtils.getAngle(
      //                    pLoc,
      //                    t.getLocation()
      //            );
      //
      //            engine.spawnProjectile(null, null, "SCY_ricochetReplacement", pLoc, angle,
      // (Vector2f)pVel.scale(0.5f));

      // PERFECT LEAD VERSION
      //            float angle;
      //            Vector2f tPoint = AIUtils.getBestInterceptPoint(pLoc, 700, t.getLocation(),
      // t.getVelocity());
      //            if (tPoint !=null){
      //                angle = VectorUtils.getAngle(
      //                        pLoc,
      //                        tPoint
      //                );
      //            } else {
      //                angle = VectorUtils.getAngle(
      //                        pLoc,
      //                        t.getLocation()
      //                );
      //            }
      //            engine.spawnProjectile(null, null, "SCY_ricochetReplacement", pLoc, angle, new
      // Vector2f(0,0));

      // INHERITED VELOCITY TEST
      //            float angle;
      //            float velocity;
      //            //compute speed after redirection
      //            angle = VectorUtils.getAngle(
      //                        pLoc,
      //                        t.getLocation()
      //                );
      //            angle = MathUtils.getShortestRotation(shell.getFacing(), angle);
      //            angle = (float)Math.toRadians(angle);
      //
      //            velocity = 700f + shell.getVelocity().length()*(float)FastTrig.cos(angle);
      //
      //            Vector2f tPoint = AIUtils.getBestInterceptPoint(pLoc, velocity, t.getLocation(),
      // t.getVelocity());
      //            if (tPoint !=null){
      //                angle = VectorUtils.getAngle(
      //                        pLoc,
      //                        tPoint
      //                );
      //            } else {
      //                angle = VectorUtils.getAngle(
      //                        pLoc,
      //                        t.getLocation()
      //                );
      //            }
      //            engine.spawnProjectile(null, null, "SCY_ricochetReplacement", pLoc, angle,
      // pVel);

      Vector2f deflection = new Vector2f();
      pVel.normalise(deflection);
      Vector2f.sub(deflection, MathUtils.getPoint(new Vector2f(), 1, angle), deflection);
      if (MagicRender.screenCheck(0.15f, pLoc)) {
        // spawn visual effect
        engine.spawnExplosion(
            pLoc,
            new Vector2f(),
            EXPLOSION_COLOR,
            MathUtils.getRandomNumberInRange(50f, 80f),
            0.4f);
        engine.addHitParticle(pLoc, new Vector2f(), 60, 1f, 0.2f, FLASH_COLOR);
        float eject = VectorUtils.getFacing(deflection);
        for (int i = 0; i <= 5; i++) {
          engine.addHitParticle(
              pLoc,
              MathUtils.getRandomPointInCone(
                  new Vector2f(),
                  Math.abs(MathUtils.getShortestRotation(shell.getFacing(), angle) / 2)
                      + (float) Math.random() * 50
                      + 50,
                  eject - 10,
                  eject + 10),
              MathUtils.getRandomNumberInRange(5f, 15f),
              1f,
              MathUtils.getRandomNumberInRange(0.5f, 1.5f),
              EXPLOSION_COLOR);
        }
      }
      // audio effect
      Global.getSoundPlayer().playSound(SOUND_ID, 0.5f, 0.5f, pLoc, new Vector2f(0, 0));
      // remove previous projectile
      engine.removeEntity(shell);
    }
  }

  /*
      //////////////////////////////
      //                          //
      //       ORION EFFECT       //
      //                          //
      //////////////////////////////

      private void applyOrionEffect(DamagingProjectileAPI p){
  //        if (p.getElapsed()>(2-1.9*((p.getDamageAmount()/500)*(p.getDamageAmount()/500)))*(p.getSource().getMutableStats().getBallisticWeaponRangeBonus().getBonusMult()/1.25)){ //I know, magic number bullshit here.
          if (p.getElapsed()>(2-1.9*(p.getDamageAmount()/1000))*(p.getSource().getMutableStats().getBallisticWeaponRangeBonus().getBonusMult()/1.25)){ //I know, magic number bullshit here.

              float dmg = p.getBaseDamageAmount()-50;

              if (dmg>0){
                  p.setDamageAmount(dmg);

                  Vector2f pVel = p.getVelocity();
  //                Vector2f tVel = MathUtils.getPoint(new Vector2f(), p.getWeapon().getProjectileSpeed()/6, p.getFacing());
  //                Vector2f.add(pVel, tVel, pVel);

                  Vector2f tVel = MathUtils.getPoint(new Vector2f(), p.getVelocity().length()/8, p.getFacing());
                  Vector2f.add(pVel, tVel, pVel);

                  Vector2f loc = p.getLocation();

  //                int stage=(int)Math.min(250, Math.max(0, p.getDamageAmount()/2));

                  if(MagicRender.screenCheck(0.15f, loc)){
                      //Add the beam to the plugin
                      //public static void addBeam(float duration, float fading, float width, Vector2f from, float angle, float length, Color core, Color fringe)
                      MagicFakeBeamPlugin.addBeam(
                              0f,
                              0.05f,
                              5,
                              loc,
                              VectorUtils.getAngle(loc, p.getWeapon().getLocation()),
                              MathUtils.getDistance(p, p.getWeapon().getLocation())-50,
                              new Color(255,200,150,150),
                              new Color(255,150,50,50)
                      );

                      engine.addSmokeParticle(loc, new Vector2f(0,0), 30, 0.25f, 0.5f, SMOKE1_COLOR);

                      engine.addHitParticle(
                              loc,
                              new Vector2f(pVel.x*0.2f,pVel.y*0.2f),
                              50,
                              0.75f,
                              0.2f,
                              new Color(255,100,50)
                      );

                      for(int i=0;i<=5;i++){
                          engine.addHitParticle(
                                  loc,
                                  MathUtils.getPoint(
                                          new Vector2f(),
                                          (float)Math.random()*200,
                                          p.getFacing()+180+20*((float)Math.random()-0.5f)
                                  ),
                                  10*(1-(float)Math.random()/2),
                                  0.75f*(1-(float)Math.random()/2),
                                  0.75f*(1-(float)Math.random()/2),
                                  new Color(255,100,50)
                          );
                      }
                  }

                  //audio effect
                  Global.getSoundPlayer().playSound(ORION_ID, 1.2f, 0.5f, loc, new Vector2f(0,0));
                  //debug
  //                engine.addFloatingText(MathUtils.getPoint(p.getLocation(),100,20), ""+dmg, 60, Color.yellow, p.getSource(), 0, 0);
  //                engine.addFloatingText(MathUtils.getPoint(p.getLocation(),100,-20), ""+p.getBaseDamageAmount(), 60, Color.red, p.getSource(), 0, 0);
  //                engine.addFloatingText(MathUtils.getPoint(p.getLocation(),100,0), ""+p.getSource().getMutableStats().getBallisticWeaponRangeBonus().getBonusMult(), 60, Color.blue, p.getSource(), 0, 0);
              }
          }
      }

      private void applyOrionTrail(DamagingProjectileAPI p){

          float speed = 1- Math.min(1, Math.max(0, p.getDamageAmount()/1000));

          MagicTrailPlugin.AddTrailMemberAdvanced(
                  p,
                  ORION.get(p),
                  Global.getSettings().getSprite("fx", "base_trail_smooth"),
                  p.getLocation(),
                  50,
                  0,
                  p.getFacing(),
                  MathUtils.getRandomNumberInRange(-45, 45),
                  0,
                  16,
                  4,
                  new Color(1,speed*0.9f,speed*0.25f),
                  Color.DARK_GRAY,
                  1,
                  0.1f,
                  0.2f,
                  0.5f+speed/2,
                  GL_SRC_ALPHA,
                  GL_ONE_MINUS_SRC_ALPHA,
                  128,
                  0,
                  new Vector2f(),
                  null
          );
      }
      */

  //////////////////////////////
  //                          //
  //     TELEPORT EFFECT      //
  //                          //
  //////////////////////////////

  public static void addTeleportation(CombatEntityAPI target, Vector2f location) {
    TELEPORT.put(target, location);
  }

  private void teleport(float amount, CombatEngineAPI engine) {
    for (Iterator<CombatEntityAPI> iter = TELEPORT.keySet().iterator(); iter.hasNext(); ) {
      CombatEntityAPI C = iter.next();
      if (!engine.isEntityInPlay(C)
          || MathUtils.isWithinRange(C.getLocation(), TELEPORT.get(C), 30)) {
        iter.remove();
      } else {
        float x = 10 * amount * (TELEPORT.get(C).x - C.getLocation().x);
        float y = 10 * amount * (TELEPORT.get(C).y - C.getLocation().y);
        Vector2f targetPos = new Vector2f(C.getLocation().x + x, C.getLocation().y + y);
        if (C instanceof ShipAPI && MagicRender.screenCheck(0.15f, targetPos)) {
          /*
          public void addAfterimage(
              Color color,
              float locX,
              float locY,
              float velX,
              float velY,
              float maxJitter,
              float in,
              float dur,
              float out,
              boolean additive,
              boolean combineWithSpriteColor,
              boolean aboveShip)
          */
          ((ShipAPI) C)
              .addAfterimage(
                  new Color(255, 255, 255, 50),
                  0,
                  0,
                  -10 * x,
                  -10 * y,
                  1f,
                  0.1f,
                  0.1f,
                  0.1f,
                  false,
                  true,
                  false);
        }
        setLocation(C, targetPos);
      }
    }
    forceCheck = false;
  }

  //////////////////////////////
  //                          //
  //   SINGULARITY EFFECT     //
  //                          //
  //////////////////////////////

  private static class singularityData {
    private final Vector2f LOC;
    private float TIME;
    private final List<ShipAPI> AFFECTED;
    private final Map<CombatEntityAPI, CollisionClass> COLLISIONS;
    private final ShipAPI SOURCE;

    public singularityData(
        Vector2f loc, float time, List affected, Map collisions, ShipAPI source) {
      this.LOC = loc;
      this.TIME = time;
      this.AFFECTED = affected;
      this.COLLISIONS = collisions;
      this.SOURCE = source;
    }
  }

  public static void addSingularity(
      Vector2f location, float timeLeft, List affectedList, Map collisionsList, ShipAPI source) {
    SINGULARITIES.add(
        new singularityData(location, timeLeft, affectedList, collisionsList, source));
  }

  private void applySingularityEffect(float amount, CombatEngineAPI engine) {

    singularityTime.advance(amount);
    if (singularityTime.intervalElapsed()) {

      for (Iterator iter = SINGULARITIES.iterator(); iter.hasNext(); ) {
        singularityData singularity = (singularityData) iter.next();

        singularity.TIME -= Math.max(0.05f, amount);

        // Check if the singularity is gone
        if (singularity.TIME <= 0) {
          // unapply the slowing effect
          for (ShipAPI s : singularity.AFFECTED) {
            s.getMutableStats().getAcceleration().unmodify(slow_ID);
            s.getMutableStats().getTurnAcceleration().unmodify(slow_ID);
          }
          // restore the original collision class
          Map<CombatEntityAPI, CollisionClass> theCollisions = singularity.COLLISIONS;
          for (CombatEntityAPI object : theCollisions.keySet()) {
            if (engine.isEntityInPlay(object)
                && object.getCollisionClass() == CollisionClass.ASTEROID) {
              object.setCollisionClass(theCollisions.get(object));
            }
          }
          // remove the whole thing
          iter.remove();
        } else {

          // Apply force to nearby entities

          float power = Math.min(2, singularity.TIME);

          List<CombatEntityAPI> pull =
              CombatUtils.getEntitiesWithinRange(singularity.LOC, power * 500);
          if (pull != null) {
            float force;

            for (CombatEntityAPI tmp : pull) {
              // skip module parts and fixed stations
              if (tmp instanceof ShipAPI
                  && (((ShipAPI) tmp).getParentStation() != null
                      && ((ShipAPI) tmp).getVariant().getHullMods().contains("axialrotation"))) {
                continue;
              }

              float distance = MathUtils.getDistanceSquared(tmp.getLocation(), singularity.LOC);
              float angle =
                  VectorUtils.getAngle(tmp.getLocation(), singularity.LOC)
                      - 1
                      - (15
                          * (1000000 - distance)
                          / 1000000); // do not pull exactly to the center, avoiding collisions
                                      // penetrations and make them swirl micely

              if (distance >= 2500) { // minimal distance of 50
                force =
                    (20 * Math.min(2, singularity.TIME))
                        * Math.min(1, power)
                        * NEWTON
                        * Math.min(
                            1,
                            Math.max(
                                0,
                                (1000000 - distance)
                                    / 1000000)); // power x distance falloff, not a realistic square
                                                 // of the distance drop for gameplay purpose

                if (tmp instanceof ShipAPI) {
                  ShipAPI ship = (ShipAPI) tmp;

                  Vector2f sVel = MathUtils.getPoint(new Vector2f(), force * 5, angle);
                  Color color = new Color(power / 9, power / 10, power / 7, power / 10);
                  /*
                  public void addAfterimage(
                      Color color,
                      float locX,
                      float locY,
                      float velX,
                      float velY,
                      float maxJitter,
                      float in,
                      float dur,
                      float out,
                      boolean additive,
                      boolean combineWithSpriteColor,
                      boolean aboveShip)
                  */
                  ship.addAfterimage(
                      color, 0, 0, sVel.x, sVel.y, 2f, 0.1f, 0.2f, 0.3f, false, true, false);
                }

                if (tmp instanceof MissileAPI && Math.random() < 0.25) {
                  // Missiles can hit allies
                  if (Math.random() < (0.25 - (distance / 2000000))) {
                    if (((MissileAPI) tmp).getCollisionClass() == CollisionClass.MISSILE_NO_FF) {
                      ((MissileAPI) tmp).setCollisionClass(CollisionClass.MISSILE_FF);
                    }
                    // debug
                    //                                    engine.addFloatingText(tmp.getLocation(),
                    // "Flameout", 20, Color.red, tmp, 0, 0.1f);
                  }
                }

                if (tmp instanceof DamagingProjectileAPI) {
                  force *= 15;
                }

                // MANUAL MASS-FREE FORCE
                Vector2f direction = MathUtils.getPoint(new Vector2f(), 1f, angle);
                force *=
                    Math.max(
                        .5f,
                        (Math.abs(
                                MathUtils.getShortestRotation(
                                    angle, VectorUtils.getFacing(tmp.getVelocity()))))
                            / 90);
                direction.scale(force);
                Vector2f.add(direction, tmp.getVelocity(), tmp.getVelocity());

                if (tmp instanceof DamagingProjectileAPI) {
                  tmp.setFacing(VectorUtils.getFacing(tmp.getVelocity()));
                }
              }
            }
          }

          // disrupt the engines of the ships affected by the initial blast
          for (ShipAPI s : singularity.AFFECTED) {
            s.getMutableStats()
                .getAcceleration()
                .modifyMult(slow_ID, Math.max(0.5f, (2 - power) / 2));
            s.getMutableStats()
                .getTurnAcceleration()
                .modifyMult(slow_ID, Math.max(0.5f, (2 - power) / 2));
            // debug
            //                        engine.addFloatingText(new
            // Vector2f(s.getLocation().x,s.getLocation().y-20), "Slowed at: "+Math.max(0,
            // 1-cluster.TIME), 20, Color.black, s, 0, 0.1f);
          }

          if (MagicRender.screenCheck(0.25f, singularity.LOC)) {
            // GLOW!
            engine.addSmoothParticle(
                singularity.LOC,
                new Vector2f(),
                100 + 100 * (float) Math.random() + 75 * power,
                1,
                0.5f + 1f * (float) Math.random(),
                FRINGE);

            engine.addHitParticle(
                singularity.LOC,
                new Vector2f(),
                25 * power + 50 * (float) Math.random(),
                1,
                0.25f + 0.5f * (float) Math.random(),
                CORE);

            // PARTICLES!
            for (int i = 0; i < Math.round(power * 3); i++) {
              float radius = 100 + (float) Math.random() * 900;
              float angle = (float) Math.random() * 360;
              float azimut = angle + 180 - 90 * (radius / 1500);
              Color particles =
                  new Color(
                      0.1f + 0.2f * (1000 - radius) / 1000,
                      0.2f + 0.25f * (1000 - radius) / 1000,
                      0.7f);

              // public void addSmoothParticle(Vector2f loc, Vector2f vel, float size, float
              // brightness, float duration, Color color)
              engine.addSmoothParticle(
                  MathUtils.getPoint(singularity.LOC, radius, angle),
                  MathUtils.getPoint(new Vector2f(), 50 + (1000 - radius) / 6, azimut),
                  10 + (float) Math.random() * 10,
                  1,
                  radius / 500,
                  particles);
            }

            // FLARE!
            if (Math.random() < 0.25) {
              float spread = (float) Math.random() * 2;
              Vector2f offset = new Vector2f((float) Math.random() * 10, (float) Math.random() * 5);
              engine.spawnEmpArc(
                  singularity.SOURCE,
                  new Vector2f(
                      singularity.LOC.x + offset.x, singularity.LOC.y - spread * power + offset.y),
                  null,
                  new SimpleEntity(
                      new Vector2f(
                          singularity.LOC.x + offset.x,
                          singularity.LOC.y + spread * power + offset.y)),
                  DamageType.KINETIC,
                  0,
                  0,
                  20000,
                  null,
                  100 + 300 * power + (float) Math.random() * 100,
                  FRINGE,
                  CORE);
            }

            // ARCS!
            if (Math.random() < Math.min(singularity.TIME / 10, 0.2f)) {
              // public CombatEntityAPI spawnEmpArc(ShipAPI damageSource, Vector2f point,
              // CombatEntityAPI pointAnchor, CombatEntityAPI empTargetEntity, DamageType
              // damageType, float damAmount, float empDamAmount, float maxRange, String
              // impactSoundId, float thickness, Color fringe, Color core)
              engine.spawnEmpArc(
                  singularity.SOURCE,
                  singularity.LOC,
                  null,
                  new SimpleEntity(MathUtils.getRandomPointInCircle(singularity.LOC, 500 * power)),
                  DamageType.KINETIC,
                  0,
                  0,
                  2000,
                  null,
                  2 + (float) Math.random() * 5,
                  FRINGE,
                  CORE);
            }
          }
        }
      }
    }
  }

  //////////////////////////////
  //                          //
  //      ANTI MISSILES       //
  //                          //
  //////////////////////////////

  public static List<MissileAPI> getAntimissiles() {
    List<MissileAPI> missiles = new ArrayList<>();

    for (MissileAPI m : ANTIMISSILES.keySet()) {
      if (ANTIMISSILES.get(m) != null) {
        missiles.add(ANTIMISSILES.get(m));
      }
    }
    return missiles;
  }

  public static void addAntimissiles(MissileAPI antimissile, MissileAPI target) {
    ANTIMISSILES.put(antimissile, target);
  }

  public static void forceCheck() {
    forceCheck = true;
  }

  private void checkAntimissiles(CombatEngineAPI engine) {
    for (Iterator<MissileAPI> iter = ANTIMISSILES.keySet().iterator(); iter.hasNext(); ) {
      MissileAPI c = iter.next();
      if (!engine.isEntityInPlay(c) || !engine.isEntityInPlay(ANTIMISSILES.get(c))) {
        iter.remove();
      }
    }
    forceCheck = false;
  }

  public void setLocation(CombatEntityAPI entity, Vector2f location) {
    Vector2f dif = new Vector2f(location);
    Vector2f.sub(location, entity.getLocation(), dif);
    Vector2f.add(entity.getLocation(), dif, entity.getLocation());
  }

  @Override
  public void renderInUICoords(ViewportAPI viewport) {}
}

// By Tartiflette
package org.scy.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.magiclib.plugins.MagicFakeBeamPlugin;
import org.scy.plugins.SCY_muzzleFlashesPlugin;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_orionTracking implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

  private boolean runOnce = false;
  private boolean hidden = false;

  private final Color SMOKE_COLOR = new Color(100, 100, 100, 200);
    private final float REDIRECT_TIC = 0.25f;

  private final Map<DamagingProjectileAPI, projData> ORION = new HashMap<>();

  @Override
  public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {

    // find direct target
    ShipAPI target =
        getDirectTarget(
            engine, weapon.getShip(), weapon, projectile.getLocation(), projectile.getFacing(), 60);
    float accuracy = weapon.getShip().getMutableStats().getAutofireAimAccuracy().getModifiedValue();
    ORION.put(projectile, new projData(target, accuracy, REDIRECT_TIC));

    // custom muzzle flash
    if (!hidden && MagicRender.screenCheck(0.25f, weapon.getLocation())) {
      SCY_muzzleFlashesPlugin.addMuzzle(weapon, 0, Math.random() > 0.5);
    }
  }

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

    if (engine.isPaused()) {
      return;
    }

    if (!runOnce) {
      runOnce = true;
      // check if the mount is hidden
      if (weapon.getSlot().isHidden()) {
        hidden = true;
        return;
      }
    }
    // orion tracking
    if (!ORION.isEmpty()) {
      for (Iterator<Map.Entry<DamagingProjectileAPI, projData>> iter = ORION.entrySet().iterator();
          iter.hasNext(); ) {
        Map.Entry<DamagingProjectileAPI, projData> entry = iter.next();

        // remove unneeded projs
        if (entry.getKey().didDamage() || entry.getKey().isFading()) {
          iter.remove();
        }

        float elapsed = entry.getValue().TIC - amount;
        if (elapsed > 0) {
          // deplete time interval between redirections
          entry.getValue().TIC = elapsed;
        } else {
          // reset interval
          entry.getValue().TIC = elapsed + REDIRECT_TIC;

          // redirect
          DamagingProjectileAPI p = entry.getKey();
          Vector2f loc = p.getLocation();
          Vector2f pVel = p.getVelocity();
          float velFacing = VectorUtils.getFacing(pVel);
          float offset = 0;

          if (entry.getValue().TARGET != null) {

            Vector2f aim =
                AIUtils.getBestInterceptPoint(
                    loc,
                    pVel.length() * (1 / entry.getValue().ACCURACY),
                    entry.getValue().TARGET.getLocation(),
                    entry.getValue().TARGET.getVelocity());

            if (aim == null) {
              aim = entry.getValue().TARGET.getLocation();
            }

            offset =
                Math.min(
                    2f,
                    Math.max(
                        -2f,
                        MathUtils.getShortestRotation(velFacing, VectorUtils.getAngle(loc, aim))));
          }

          VectorUtils.rotate(pVel, offset);
          p.setFacing(velFacing + offset);

          // visual effect
          if (MagicRender.screenCheck(0.15f, loc)) {
            // Add the beam to the plugin
            // public static void addBeam(float duration, float fading, float width, Vector2f from,
            // float angle, float length, Color core, Color fringe)
            MagicFakeBeamPlugin.addBeam(
                0f,
                0.05f,
                5,
                loc,
                VectorUtils.getAngle(loc, p.getWeapon().getLocation()),
                MathUtils.getDistance(p, p.getWeapon().getLocation()) - 50,
                new Color(255, 200, 150, 150),
                new Color(255, 150, 50, 50));

            engine.addSmokeParticle(loc, new Vector2f(0, 0), 30, 0.25f, 0.5f, SMOKE_COLOR);

            engine.addHitParticle(
                loc,
                new Vector2f(pVel.x * 0.2f, pVel.y * 0.2f),
                50,
                0.75f,
                0.2f,
                new Color(255, 100, 50));

            for (int i = 0; i <= 5; i++) {
              engine.addHitParticle(
                  loc,
                  MathUtils.getPoint(
                      new Vector2f(),
                      (float) Math.random() * 200,
                      p.getFacing() + 180 + 20 * ((float) Math.random() - 0.5f) + offset * 20),
                  10 * (1 - (float) Math.random() / 2),
                  0.75f * (1 - (float) Math.random() / 2),
                  0.75f * (1 - (float) Math.random() / 2),
                  new Color(255, 100, 50));
            }
          }

          // audio effect
            String ORION_ID = "SCY_orionStage";
            Global.getSoundPlayer().playSound(ORION_ID, 1.2f, 0.5f, loc, new Vector2f(0, 0));
        }
      }
    }
  }

  private static class projData {
    private final ShipAPI TARGET;
    private final float ACCURACY;
    private float TIC;

    public projData(ShipAPI target, float accuracy, float tic) {
      this.TARGET = target;
      this.ACCURACY = accuracy;
      this.TIC = tic;
    }
  }

  private ShipAPI getDirectTarget(
      CombatEngineAPI engine,
      ShipAPI source,
      WeaponAPI checkWeaponGroup,
      Vector2f loc,
      float aim,
      Integer searchCone) {
    if (source != null && source.isAlive()) { // SOURCE IS ALIVE
      boolean allAspect = (searchCone >= 360);
      // AUTO FIRE TARGET
      if (checkWeaponGroup != null) {
        if (source.getWeaponGroupFor(checkWeaponGroup) != null) {
          // WEAPON IN AUTOFIRE
          if (source
                  .getWeaponGroupFor(checkWeaponGroup)
                  .isAutofiring() // weapon group is autofiring
              && source.getSelectedGroupAPI()
                  != source.getWeaponGroupFor(
                      checkWeaponGroup)) { // weapon group is not the selected group
            ShipAPI weaponTarget =
                source
                    .getWeaponGroupFor(checkWeaponGroup)
                    .getAutofirePlugin(checkWeaponGroup)
                    .getTargetShip();
            if (weaponTarget != null // weapon target exist
                && (allAspect // either missile has full arc
                    || Math.abs(
                            MathUtils.getShortestRotation(
                                aim, VectorUtils.getAngle(loc, weaponTarget.getLocation())))
                        < searchCone / 2f // or missile has limited arc and the target is within
                )) {
              // then return the auto-fire target
              return source
                  .getWeaponGroupFor(checkWeaponGroup)
                  .getAutofirePlugin(checkWeaponGroup)
                  .getTargetShip();
            }
          }
        }
      }

      // SHIP TARGET
      ShipAPI shipTarget = source.getShipTarget();
      if (shipTarget != null
          && shipTarget.isAlive()
          && shipTarget.getOwner() != source.getOwner()
          && CombatUtils.isVisibleToSide(shipTarget, source.getOwner())
          && (allAspect
              || Math.abs(
                      MathUtils.getShortestRotation(
                          aim, VectorUtils.getAngle(loc, shipTarget.getLocation())))
                  < searchCone / 2f)) {
        return shipTarget;
      }

      // POINTER TARGET
      for (ShipAPI s : engine.getShips()) {
        if (s.isAlive()
            && s.getOwner() != source.getOwner()
            && CombatUtils.isVisibleToSide(s, source.getOwner())
            && MathUtils.isWithinRange(s, source.getMouseTarget(), 100)
            && (allAspect
                || Math.abs(
                        MathUtils.getShortestRotation(
                            aim, VectorUtils.getAngle(loc, s.getLocation())))
                    < searchCone / 2f)) {
          return s;
        }
      }
    }

    // nothing fits
    return null;
  }
}

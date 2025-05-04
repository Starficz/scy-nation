// By Tartiflette
package org.scy.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
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

public class SCY_orionRedirect implements EveryFrameWeaponEffectPlugin {

  private boolean put = false;
  private boolean runOnce = false;
  private boolean hidden = false;

  private final Color SMOKE_COLOR = new Color(100, 100, 100, 200);

    private final Map<DamagingProjectileAPI, ShipAPI> ORION = new HashMap<>();

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

    // assign the weapon for the muzzle flash plugin
    if (weapon.getChargeLevel() == 1) {
      // proj replacement

      for (DamagingProjectileAPI p :
          CombatUtils.getProjectilesWithinRange(weapon.getLocation(), 100)) {
        if (!p.didDamage() && !p.isFading() && p.getWeapon() == weapon && !ORION.containsKey(p)) {
          // find direct target
          ShipAPI target =
              getDirectTarget(engine, weapon.getShip(), weapon, p.getLocation(), p.getFacing(), 60);

          ORION.put(p, target);
        }
      }
      if (!hidden) {
        // add the weapon to the MEMBERS map when it fires
        // "put" is to make sure it's added only once
        if (!put) {
          put = true;
          SCY_muzzleFlashesPlugin.addMuzzle(weapon, 0, false);
        }
      }
    } else if (!hidden && weapon.getChargeLevel() == 0) {
      // reinitialise "put"
      if (put) {
        put = false;
      }
    }

    // orion tracking
    if (!ORION.isEmpty()) {
      for (Iterator<Map.Entry<DamagingProjectileAPI, ShipAPI>> iter = ORION.entrySet().iterator();
          iter.hasNext(); ) {
        Map.Entry<DamagingProjectileAPI, ShipAPI> entry = iter.next();

        // remove unneeded projs
        if (entry.getKey().didDamage() || entry.getKey().isFading()) {
          iter.remove();
        }

        if (entry.getKey().getElapsed() == 0) {
          entry.getKey().getVelocity().scale(0.6f);
        } else if (entry.getKey().getDamageAmount() > 500
            && 11 - ((int) (entry.getKey().getElapsed() * 3.5f))
                < entry.getKey().getDamageAmount() / 100) {

          // reduce damage
          entry.getKey().setDamageAmount(entry.getKey().getBaseDamageAmount() - 100);

          //                    //debug
          //                    engine.addFloatingText(
          //                            entry.getKey().getLocation(),
          //                            " "+entry.getKey().getDamageAmount(),
          //                            10,
          //                            Color.RED,
          //                            null,
          //                            1,
          //                            1
          //                    );

          // redirect
          DamagingProjectileAPI p = entry.getKey();
          Vector2f loc = p.getLocation();
          Vector2f pVel = p.getVelocity();
          float velFacing = VectorUtils.getFacing(pVel);
          float offset = 0;

          if (entry.getValue() != null) {

            Vector2f aim =
                AIUtils.getBestInterceptPoint(
                    loc,
                    pVel.length(),
                    entry.getValue().getLocation(),
                    entry.getValue().getVelocity());
            if (aim == null) {
              aim = entry.getValue().getLocation();
            }

            offset =
                Math.min(
                    2f,
                    Math.max(
                        -2f,
                        MathUtils.getShortestRotation(velFacing, VectorUtils.getAngle(loc, aim))));
          }

          VectorUtils.rotate(pVel, offset);
          pVel.scale(1.2f);
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

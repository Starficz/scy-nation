package org.scy.shipsystems.ai;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.scy.shipsystems.SCY_targetingStats;
import org.scy.shipsystems.SCY_targetingStats.targetingMode;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

public class SCY_targetingAI implements ShipSystemAIScript {
  private CombatEngineAPI engine;
  private ShipAPI ship;
  private ShipwideAIFlags flags;
  private final IntervalUtil timer = new IntervalUtil(0.25f, 0.5f);
  private float maxRange = 0;
  private float baseRangePercent = 0;
  private boolean runOnce = false;

  @Override
  public void init(
      ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
    this.ship = ship;
    this.engine = engine;
    this.flags = flags;
    timer.randomize();
  }

  @Override
  public void advance(
      float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {

    if (engine.isPaused() || ship.getShipAI() == null) {
      return;
    }

    if (!runOnce) {
      runOnce = true;
      baseRangePercent = ship.getMutableStats().getBallisticWeaponRangeBonus().getPercentMod();
      for(WeaponAPI weapon : ship.getAllWeapons()){
        if (!weapon.isDecorative() && weapon.getType() != WeaponType.MISSILE && !weapon.hasAIHint(WeaponAPI.AIHints.PD)){
          maxRange = maxRange == 0 ? weapon.getRange() : Math.min(maxRange, weapon.getRange());
        }
      }
    }


    timer.advance(amount);
    if (timer.intervalElapsed()) {
      float targetDistance = 0f;
      List<ShipAPI> nearbyEnemies = AIUtils.getNearbyEnemies(ship, maxRange * (50 + baseRangePercent) / baseRangePercent);
      for(ShipAPI enemy : nearbyEnemies){
        if (!enemy.isFighter() && enemy.isAlive() && Misc.isInArc(ship.getFacing(), 90f, ship.getLocation(), enemy.getLocation())){
          float enemyDistance = MathUtils.getDistance(enemy, ship);
          targetDistance = targetDistance == 0 ? enemyDistance : Math.min(targetDistance, enemyDistance);
        }
      }

      ship.getAIFlags().setFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE, 1.0F, maxRange);

      if (targetDistance > maxRange) {
        if (SCY_targetingStats.getMode(ship) != targetingMode.HEAVY && AIUtils.canUseSystemThisFrame(ship)) {
          ship.useSystem();
        }
      } else if (targetDistance < maxRange / 2) {
        if (SCY_targetingStats.getMode(ship) != targetingMode.PD && AIUtils.canUseSystemThisFrame(ship)) {
          ship.useSystem();
        }
      } else {
        if (SCY_targetingStats.getMode(ship) != targetingMode.BALANCED && AIUtils.canUseSystemThisFrame(ship)) {
          ship.useSystem();
        }
      }
    }
  }
}

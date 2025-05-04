package org.scy.shipsystems.ai;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.IntervalUtil;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_pingAI implements ShipSystemAIScript {

  private ShipAPI ship;
  private ShipSystemAPI system;
  private final IntervalUtil timer = new IntervalUtil(2, 4);

  @Override
  public void init(
      ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
    this.ship = ship;
    this.system = system;
    timer.randomize();
  }

  @Override
  public void advance(
      float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
    timer.advance(amount);
    if (timer.intervalElapsed()) {
      if (!system.isActive() && AIUtils.canUseSystemThisFrame(ship)) {
        ship.useSystem();
      }
    }
  }
}

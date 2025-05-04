package org.scy.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import org.scy.plugins.SCY_projectilesEffectPlugin;
import org.magiclib.util.MagicRender;
import org.scy.SCY_graphicLibEffects;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_singularity_effect implements OnHitEffectPlugin {

  private final Color EXPLOSION_COLOR = new Color(50, 150, 250, 255);
  private final Color PARTICLE_COLOR = new Color(100, 200, 250, 255);

  private final Vector2f SIZE = new Vector2f(2048, 2048);

  private final Map<ShipAPI.HullSize, Float> SIZE_MULT = new HashMap<>();

  {
    SIZE_MULT.put(ShipAPI.HullSize.FIGHTER, 1f);
    SIZE_MULT.put(ShipAPI.HullSize.FRIGATE, 1.5f);
    SIZE_MULT.put(ShipAPI.HullSize.DESTROYER, 1.6f);
    SIZE_MULT.put(ShipAPI.HullSize.CRUISER, 1.8f);
    SIZE_MULT.put(ShipAPI.HullSize.CAPITAL_SHIP, 2f);
    SIZE_MULT.put(ShipAPI.HullSize.DEFAULT, 1f);
  }

  @Override
  public void onHit(
      DamagingProjectileAPI projectile,
      CombatEntityAPI target,
      Vector2f point,
      boolean shieldHit,
      ApplyDamageResultAPI damageResult,
      CombatEngineAPI engine) {

    /////////////////////////////////////////
    //                                     //
    //             MAIN CHECK              //
    //                                     //
    /////////////////////////////////////////

    // shield check

    float setPower;
    if (shieldHit) {
      setPower = 1f;
    } else {
      setPower = 3f;
    }

    // class check

    float shipClass = 1f;
    if (target instanceof ShipAPI) {

      // check for templar hull because their special shield isn't recognized as such
      ShipAPI templarCheck = (ShipAPI) target;
      if (templarCheck.getHullSpec().getHullId().startsWith("tem_")) {
        setPower = 2f;
      }

      if (((ShipAPI) target).getParentStation() != null) {
        target = ((ShipAPI) target).getParentStation();
      }

      shipClass = (float) SIZE_MULT.get(((ShipAPI) target).getHullSize());
    }

    /////////////////////////////////////////
    //                                     //
    //         SINGULARITY EFFECT          //
    //                                     //
    /////////////////////////////////////////

    float power = 5 * setPower + shipClass;
    // power is within 10 and 25

    Map<CombatEntityAPI, CollisionClass> lightCrafts = new HashMap<>();

    // Apply force to nearby entities

    List<ShipAPI> neighbours = new ArrayList<>();
    for (ShipAPI s : CombatUtils.getShipsWithinRange(point, 30 * power)) {

      // ignore phased ships and fighters taking off/docking
      if (s.getCollisionClass() != null) {
        //                neighbours.add(s);

        //                if(MagicRender.screenCheck(0.25f, point)){
        engine.spawnEmpArc(
            projectile.getSource(),
            point,
            null,
            target,
            DamageType.KINETIC,
            0,
            1000,
            5000,
            null,
            3 + power / 5,
            PARTICLE_COLOR,
            Color.WHITE);
        //                }
      }

      // make fighters collide with everything.
      if (s.isDrone() || s.isFighter()) {
        lightCrafts.put(s, s.getCollisionClass());
        s.setCollisionClass(CollisionClass.ASTEROID);
        neighbours.add(s);
      }
    }

    // add to the pulling effect
    SCY_projectilesEffectPlugin.addSingularity(
        new Vector2f(point), power, neighbours, lightCrafts, projectile.getSource());

    Vector2f size = (Vector2f) (new Vector2f(SIZE)).scale(0.5f + power / 50);

    MagicRender.battlespace(
        Global.getSettings().getSprite("fx", "SCY_singularity"),
        point,
        new Vector2f(),
        size,
        // (Vector2f)(new Vector2f(size)).scale(-0.1f),
        new Vector2f(),
        MathUtils.getRandomNumberInRange(0, 360),
        30,
        new Color(255, 255, 255, 70),
        true,
        0.1f,
        2 * power / 5,
        3 * power / 5);
    MagicRender.battlespace(
        Global.getSettings().getSprite("fx", "SCY_singularity"),
        point,
        new Vector2f(),
        (Vector2f) (new Vector2f(size)).scale(0.9f),
        // (Vector2f)(new Vector2f(size)).scale(-0.1f),
        new Vector2f(),
        MathUtils.getRandomNumberInRange(0, 360),
        60,
        new Color(255, 255, 255, 60),
        true,
        power / 5,
        2 * power / 5,
        2 * power / 5);
    MagicRender.battlespace(
        Global.getSettings().getSprite("fx", "SCY_singularity"),
        point,
        new Vector2f(),
        (Vector2f) (new Vector2f(size)).scale(0.8f),
        // (Vector2f)(new Vector2f(size)).scale(-0.1f),
        new Vector2f(),
        MathUtils.getRandomNumberInRange(0, 360),
        90,
        new Color(255, 255, 255, 50),
        true,
        2 * power / 5,
        2 * power / 5,
        power / 5);
    MagicRender.battlespace(
        Global.getSettings().getSprite("fx", "SCY_singularity"),
        point,
        new Vector2f(),
        (Vector2f) (new Vector2f(size)).scale(0.7f),
        // (Vector2f)(new Vector2f(size)).scale(-0.1f),
        new Vector2f(),
        MathUtils.getRandomNumberInRange(0, 360),
        120,
        new Color(255, 255, 255, 40),
        true,
        3 * power / 5,
        power / 5,
        power / 5);

    // Spawn visual effects
    engine.spawnExplosion(point, (Vector2f) new Vector2f(0, 0), EXPLOSION_COLOR, 40f * power, 1f);

    // Spawn distortion
    if (Global.getSettings().getModManager().isModEnabled("shaderLib")) {
      SCY_graphicLibEffects.CustomBubbleDistortion(
          point,
          new Vector2f(),
          size.getX() / 2,
          power * 2,
          true,
          0,
          360,
          0,
          power / 10,
          power / 5 + power / 10,
          3 * power / 5,
          power / 10,
          0);
    }

    // Vortex sound
    Global.getSoundPlayer().playSound("SCY_vortex", 1f, 15f, point, new Vector2f(0, 0));
  }
}

package org.scy.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import org.magiclib.util.MagicLensFlare;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import java.util.List;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lazywizard.lazylib.combat.entities.SimpleEntity;
import org.lwjgl.util.vector.Vector2f;

public class SCY_ketoMainGunEffect implements OnHitEffectPlugin {
  private final Color PARTICLE_COLOR = new Color(250, 75, 25, 255);

  @Override
  public void onHit(
      DamagingProjectileAPI projectile,
      CombatEntityAPI target,
      Vector2f point,
      boolean shieldHit,
      ApplyDamageResultAPI damageResult,
      CombatEngineAPI engine) {

    Global.getSoundPlayer().playSound("SCY_keto_hit", 1, 1, point, new Vector2f());

    if (MagicRender.screenCheck(0.5f, point)) {
      // visual effect
      engine.spawnExplosion(
          // where
          point,
          // speed
          new Vector2f(),
          // color
          Color.DARK_GRAY,
          // size
          750,
          // duration
          5);
      engine.spawnExplosion(
          // where
          point,
          // speed
          new Vector2f(),
          // color
          PARTICLE_COLOR,
          // size
          500,
          // duration
          2);

      // big splashes
      // public static void battlespaceRender(SpriteAPI sprite, Vector2f loc, Vector2f vel, Vector2f
      // size, Vector2f growth, float angle, float spin, Color color, boolean additive, float
      // fadein, float full, float fadeout)
      MagicRender.battlespace(
          Global.getSettings().getSprite("fx", "SCY_splashFull"),
          new Vector2f(point),
          new Vector2f(),
          new Vector2f(250, 250),
          new Vector2f(800, 800),
          MathUtils.getRandomNumberInRange(0, 360),
          0,
          new Color(255, 50, 150, 255),
          true,
          0f,
          0.1f,
          0.25f);

      MagicRender.battlespace(
          Global.getSettings().getSprite("fx", "SCY_splashFull"),
          new Vector2f(point),
          new Vector2f(),
          new Vector2f(250, 250),
          new Vector2f(400, 400),
          MathUtils.getRandomNumberInRange(0, 360),
          0,
          new Color(255, 255, 255, 255),
          true,
          0.25f,
          0.1f,
          0.25f);

      // small splashes
      for (int i = 0; i < 20; i++) {

        float angle = MathUtils.getRandomNumberInRange(0, 360);
        float speed = MathUtils.getRandomNumberInRange(0.25f, 1f);

        MagicRender.battlespace(
            Global.getSettings().getSprite("fx", "SCY_splashPart"),
            MathUtils.getPoint(point, 50 + speed * 100, angle),
            new Vector2f(MathUtils.getPoint(new Vector2f(), speed * 100, angle)),
            new Vector2f(50 + 50 * speed, 100),
            new Vector2f(600 * speed, 200),
            angle,
            0,
            new Color(1, 1, speed, 1),
            true,
            0.1f,
            0.1f,
            MathUtils.getRandomNumberInRange(speed / 3, speed / 2));
      }

      // flare
      MagicLensFlare.createSharpFlare(
          engine,
          projectile.getSource(),
          point,
          15,
          1000,
          0,
          new Color(255, 100, 0),
          new Color(255, 200, 100));
    }

    List<ShipAPI> FAR = CombatUtils.getShipsWithinRange(point, 750);

    for (ShipAPI c : FAR) {
      if (c.getCollisionClass() != CollisionClass.NONE) {

        Integer numArcs = 1;

        if (c.isFrigate()) {
          numArcs = 2;
        } else if (c.isDestroyer()) {
          numArcs = 4;
        } else if (c.isCruiser()) {
          numArcs = 7;
        } else if (c.isCapital()) {
          numArcs = 12;
        }

        if (c.getOwner() == projectile.getOwner()) {
          numArcs = (int) numArcs / 3;
        }

        for (int i = 0; i < numArcs; i++) {
          engine.spawnEmpArcPierceShields(
              projectile.getSource(),
              point,
              new SimpleEntity(point),
              c,
              DamageType.KINETIC,
              200,
              500,
              2000,
              null,
              2f,
              new Color(250, 50, 25), // fringe
              new Color(250, 200, 150) // core
              );
        }
      }
      for (MissileAPI m : CombatUtils.getMissilesWithinRange(point, 750)) {
        m.flameOut();
      }
    }
  }
}

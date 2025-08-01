// By Tartiflette
package org.scy.weapons;

import com.fs.graphics.Sprite;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.scy.ReflectionUtils;
import org.scy.plugins.SCY_muzzleFlashesPlugin;
import org.magiclib.util.MagicRender;

import java.util.List;

public class SCY_addToMuzzlePlugin implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {}

  @Override
  public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
    if (weapon.getSlot().isHidden()) return;
    if (MagicRender.screenCheck(0.25f, weapon.getLocation())) {
      SCY_muzzleFlashesPlugin.addMuzzle(weapon, 0, Math.random() > 0.5);
    }
//    Object damageTracker = ReflectionUtils.invoke(deco, "getDamageTracker");
//    List<ReflectionUtils.ReflectedField> damageTrackerFields = ReflectionUtils.getFieldsMatching(damageTracker.getClass());
//
//    for(ReflectionUtils.ReflectedField damageTrackerField : damageTrackerFields){
//      if(damageTrackerField.getType().isArray() && damageTrackerField.getType().getComponentType() != int.class){
//        Object[] renderers = (Object[]) damageTrackerField.get(damageTracker);
//        for(Object renderer : renderers){
//          List<ReflectionUtils.ReflectedField> rendererFields = ReflectionUtils.getFieldsMatching(renderer, null, Sprite.class);
//          for (ReflectionUtils.ReflectedField rendererField : rendererFields) {
//            Sprite sprite = (Sprite) rendererField.get(renderer);
//            sprite.setTexture((com.fs.graphics.Object) Global.getSettings().getSprite("graphics/fx/blank.png"));
//          }
//        }
//      }
//    }
  }
}

// By Tartiflette
package org.scy.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.scy.plugins.SCY_muzzleFlashesPlugin;
import org.magiclib.util.MagicRender;

public class SCY_addToMuzzlePlugin implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {}

  @Override
  public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
    if (weapon.getSlot().isHidden()) return;
    if (MagicRender.screenCheck(0.25f, weapon.getLocation())) {
      SCY_muzzleFlashesPlugin.addMuzzle(weapon, 0, Math.random() > 0.5);
    }
  }
}

// By Tartiflette
package org.scy.weapons;

import com.fs.starfarer.api.combat.*;
import org.magiclib.util.MagicRender;
import org.scy.plugins.SCY_muzzleFlashesPlugin;

public class SCY_hemorMuzzle implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

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

package org.scy.weapons;

import static org.scy.SCY_txt.txt;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicUI;
import java.awt.Color;

public class SCY_khalkotauroiMainEffect implements EveryFrameWeaponEffectPlugin {

  private ShipAPI SHIP;
  private WeaponAPI HEAT;
  private float heat = 0;
    private float charge = 0;
    private float modules = 0;
  private boolean runOnce = false, firing = false, fired = false;
  private final IntervalUtil timer = new IntervalUtil(0.05f, 0.15f);

  @Override
  public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

    if (engine.isPaused()) return;

    if (!runOnce) {
      runOnce = true;

      if (weapon.getShip().getOriginalOwner() == -1 && weapon.getShip().getOwner() == -1) {
        return;
      }

      SHIP = weapon.getShip();
      // get the weapon, all the sprites, sizes, and set the frames to the visible ones
      for (WeaponAPI w : SHIP.getAllWeapons()) {
        if (w.getSlot().getId().equals("Z_HEAT")) {
          HEAT = w;
        }
      }
      timer.randomize();
      return;
    }

    float newCharge = weapon.getChargeLevel();

    UIElement(modules * newCharge);

    // skip the script if the weapon isn't firing or cooling
    if (newCharge > 0 || firing) {

      if (newCharge > 0) {
        if (!fired) {
          Global.getSoundPlayer()
              .playSound("SCY_khalk_intro", 1, 0.1f, SHIP.getLocation(), SHIP.getVelocity());
          fired = true;
        }
      } else {
        fired = false;
      }

      firing = true;
      timer.advance(amount);
      if (timer.intervalElapsed()) {

        // modules for UI
        modules = 0;
        for (ShipAPI s : SHIP.getChildModulesCopy()) {
          if (s.isAlive()) {
            modules++;
          }
        }

        if (newCharge == 0 && heat == 0) {
          firing = false;
          HEAT.getAnimation().setFrame(0);
          charge = 0;
          return;
        }

          float direction = 1;
          if (newCharge >= charge && weapon.isFiring()) {
          direction = 0.02f;
        } else {
          direction = -0.005f;
        }
        charge = newCharge;

        heat = Math.min(1, Math.max(0, heat + direction));

        HEAT.getAnimation().setFrame(1);
        HEAT.getSprite().setColor(new Color(1, heat, heat, heat));
      }
    }
  }

  private void UIElement(Float level) {
    MagicUI.drawInterfaceStatusBar(
        SHIP, level / 6, null, null, level, txt("wpn_khalkUI"), Math.round(100 * level / 6));
  }
}

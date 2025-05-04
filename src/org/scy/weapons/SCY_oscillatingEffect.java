// by Tartiflette, this script allow for a beam weapon to vibrate at a predetermined frequency
// during the main firing sequence
// feel free to use it, credit is appreciated but not mandatory
package org.scy.weapons;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import org.lazywizard.lazylib.FastTrig;

public class SCY_oscillatingEffect implements BeamEffectPlugin {
  private float p = 0, random = 0;
  private boolean runOnce = false;

  @Override
  public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
    // Don't bother with any checks if the game is paused
    if (engine.isPaused()) {
      return;
    }

    if (!runOnce) {
      random = (float) Math.random() - 0.5f;
      runOnce = true;
    }

    if (beam.getBrightness() > 0.1) {

      p += amount * 2;
      float frequencyA = (float) FastTrig.sin((2 + random) * p);
      float frequencyB = (float) FastTrig.cos((4 - random) * p);

      float AMPLITUDE = 0.3f;
      float offset =
          Math.min(p, 4)
              * AMPLITUDE
              * ((frequencyA * (frequencyB)) + (float) FastTrig.sin(20 * random * p));

      for (int i = 0; i < beam.getWeapon().getSpec().getTurretAngleOffsets().size(); i++) {
        beam.getWeapon().getSpec().getHardpointAngleOffsets().set(i, offset);
        beam.getWeapon().getSpec().getTurretAngleOffsets().set(i, offset);
        beam.getWeapon().getSpec().getHiddenAngleOffsets().set(i, offset);
      }
    }
  }
}

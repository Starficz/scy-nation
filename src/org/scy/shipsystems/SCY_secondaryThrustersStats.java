package org.scy.shipsystems;

import static org.scy.SCY_txt.txt;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

public class SCY_secondaryThrustersStats extends BaseShipSystemScript {

  @Override
  public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
    stats.getMaxSpeed().modifyFlat(id, 100f * effectLevel);
    stats.getAcceleration().modifyPercent(id, 200f * effectLevel);
    stats.getDeceleration().modifyPercent(id, 200f * effectLevel);
    stats.getTurnAcceleration().modifyPercent(id, -75f * effectLevel);
    stats.getMaxTurnRate().modifyPercent(id, -75f * effectLevel);
  }

  @Override
  public void unapply(MutableShipStatsAPI stats, String id) {
    stats.getMaxSpeed().unmodify(id);
    stats.getMaxTurnRate().unmodify(id);
    stats.getTurnAcceleration().unmodify(id);
    stats.getAcceleration().unmodify(id);
    stats.getDeceleration().unmodify(id);
  }

  @Override
  public StatusData getStatusData(int index, State state, float effectLevel) {
    if (index == 0) {
      return new StatusData("-" + (int) Math.round(75 * effectLevel) + txt("sstm_thrust0"), false);
    } else if (index == 1) {
      return new StatusData("+" + (int) Math.round(100 * effectLevel) + txt("sstm_thrust1"), false);
    }
    return null;
  }
}

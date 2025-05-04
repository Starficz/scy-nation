package org.scy.shipsystems;

import static org.scy.SCY_txt.txt;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

public class SCY_twinShieldStats extends BaseShipSystemScript {

  @Override
  public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
    stats.getShieldDamageTakenMult().modifyMult(id, 1 - (effectLevel / 2));
    stats.getShieldTurnRateMult().modifyMult(id, 4 * effectLevel);
  }

  @Override
  public void unapply(MutableShipStatsAPI stats, String id) {
    stats.getShieldDamageTakenMult().unmodify(id);
    stats.getShieldTurnRateMult().unmodify(id);
  }

  @Override
  public StatusData getStatusData(int index, State state, float effectLevel) {
    if (index == 0) {
      return new StatusData("+" + (int) Math.round(50 * effectLevel) + txt("sstm_shield0"), false);
    }
    if (index == 1) {
      return new StatusData("-" + (int) Math.round(50 * effectLevel) + txt("sstm_shield1"), false);
    }
    return null;
  }
}

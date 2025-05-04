package org.scy.shipsystems;

import static org.scy.SCY_txt.txt;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

public class SCY_stasisShieldStats extends BaseShipSystemScript {

  @Override
  public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
    stats.getShieldDamageTakenMult().modifyMult(id, 1 - (0.9f * effectLevel));
    stats.getShieldUpkeepMult().modifyMult(id, 1 - effectLevel);
    stats.getShieldUnfoldRateMult().modifyMult(id, 5);

    stats.getAcceleration().modifyMult(id, 1 - (effectLevel / 2));
    stats.getDeceleration().modifyMult(id, 1 - (effectLevel / 2));
    stats.getTurnAcceleration().modifyMult(id, 1 - (effectLevel / 2));
  }

  @Override
  public void unapply(MutableShipStatsAPI stats, String id) {
    stats.getShieldDamageTakenMult().unmodify(id);
    stats.getShieldUpkeepMult().unmodify(id);
    stats.getShieldUnfoldRateMult().unmodify(id);

    stats.getAcceleration().unmodify(id);
    stats.getDeceleration().unmodify(id);
    stats.getTurnAcceleration().unmodify(id);
  }

  @Override
  public StatusData getStatusData(int index, State state, float effectLevel) {
    if (index == 0) {
      return new StatusData((int) Math.round(effectLevel * 90) + txt("sstm_stasis"), false);
    }
    return null;
  }
}

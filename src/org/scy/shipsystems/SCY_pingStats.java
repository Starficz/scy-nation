package org.scy.shipsystems;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import static org.scy.SCY_txt.txt;

public class SCY_pingStats extends BaseShipSystemScript {

    @Override
  public void apply(
      MutableShipStatsAPI stats, String id, ShipSystemStatsScript.State state, float effectLevel) {
      float PERCENT_BONUS_FOR_SENSORS = 250f;
      if (state == State.IN || state == State.OUT) {
      float sensorRangePercent = PERCENT_BONUS_FOR_SENSORS * effectLevel;
      stats.getSightRadiusMod().modifyPercent(id, sensorRangePercent);
    }
    if (state == State.ACTIVE) {
      stats.getSightRadiusMod().modifyPercent(id, PERCENT_BONUS_FOR_SENSORS);
    }
  }

  @Override
  public void unapply(MutableShipStatsAPI stats, String id) {
    stats.getSightRadiusMod().unmodify(id);
  }

  @Override
  public ShipSystemStatsScript.StatusData getStatusData(
      int index, ShipSystemStatsScript.State state, float effectLevel) {
    if (index == 0) {
      return new ShipSystemStatsScript.StatusData(
          "+" + (int) Math.round(effectLevel * 250) + txt("sstm_ping"), false);
    }
    return null;
  }
}

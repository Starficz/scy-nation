package org.scy.shipsystems;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import static org.scy.SCY_txt.txt;

public class SCY_teleportingShellStats extends BaseShipSystemScript {

  @Override
  public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {}

  @Override
  public void unapply(MutableShipStatsAPI stats, String id) {}

  @Override
  public StatusData getStatusData(int index, State state, float effectLevel) {
    if (index == 0) {
      return new StatusData(txt("sstm_shell"), false);
    }
    return null;
  }
}

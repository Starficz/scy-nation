package org.scy;

import static org.scy.SCY_txt.txt;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import java.util.ArrayList;
import java.util.List;

public class SCY_shipsStealth implements EveryFrameScript {

  private final IntervalUtil globalTimer = new IntervalUtil(0.1f, 0.2f);
  private boolean checked = false;

  @Override
  public void advance(float amount) {

    // added ramdom timer for better performances
    globalTimer.advance(amount);
    if (!globalTimer.intervalElapsed()) return;

    // Returns if we detect a seemingly impossible situation (no player fleet, for example)
    if (Global.getSector() == null
        || Global.getSector().getPlayerFleet() == null
        || Global.getSector().getPlayerFleet().getContainingLocation() == null) {
      return;
    }

    CampaignFleetAPI player = Global.getSector().getPlayerFleet();
    float scy = 0, total = 0;
    List<FleetMemberAPI> SHIPS = new ArrayList<>();
    for (FleetMemberAPI s : player.getFleetData().getMembersListCopy()) {
      total++;
      if (s.getHullId().startsWith("SCY_")) {
        SHIPS.add(s);
        scy++;
      }
    }

    // bypass if there are no Scy ships in the player fleet and the effect has been removed
    String ID = "SCY_engineering";
    if (SHIPS.isEmpty()) {
      if (!checked) {
        checked = true;
        player.getStats().getDetectedRangeMod().unmodify(ID);
      }
    } else {
      checked = false;
    }

    float PLAYER_MOD = 25; // percent
    float PLAYER_SUPPLIES = 0.5f; // mult
    if (player.getCurrBurnLevel() < 3) {
      player
          .getStats()
          .getDetectedRangeMod()
          .modifyPercent(ID, -PLAYER_MOD * (scy / total), txt("stealth_0"));
      for (FleetMemberAPI s : SHIPS) {
        s.getStats().getSuppliesPerMonth().modifyMult(ID, PLAYER_SUPPLIES, ID);
      }
    } else {
      player
          .getStats()
          .getDetectedRangeMod()
          .modifyPercent(ID, PLAYER_MOD * (scy / total), txt("stealth_1"));
      for (FleetMemberAPI s : SHIPS) {
        s.getStats().getSuppliesPerMonth().unmodify(ID);
      }
    }
  }

  @Override
  public boolean isDone() {
    return false;
  }

  @Override
  public boolean runWhilePaused() {
    return false;
  }
}

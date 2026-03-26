package org.scy.world;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import org.lazywizard.lazylib.MathUtils;

public class SCY_loot extends BaseCampaignEventListener {

  @Override
  public void reportEncounterLootGenerated(FleetEncounterContextPlugin plugin, CargoAPI loot) {

    FactionAPI scy = Global.getSector().getFaction("SCY");
    CampaignFleetAPI loser = plugin.getLoser();

    if (loser == null || loser.getFaction() != scy) return;
    loot.addCommodity("SCY_intelChip", MathUtils.getRandomNumberInRange(0, 3));
  }

  public SCY_loot() {
    super(true);
  }

  public boolean isDone() {
    return false;
  }

  public boolean runWhilePaused() {
    return false;
  }
}

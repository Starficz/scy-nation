package org.scy.world;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetEncounterContextPlugin;
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

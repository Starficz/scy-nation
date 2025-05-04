package org.scy.campaign.econ.industries;

import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
// import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.util.Misc;

// import com.fs.starfarer.api.util.Pair;

public class SCY_shipDealer extends BaseIndustry {

  @Override
  public void apply() {

    super.apply(true);
    market.addSubmarket("SCY_amityDealer");
    int size = market.getSize();

    demand(Commodities.CREW, size - 1);
    demand(Commodities.SUPPLIES, size - 1);
    demand(Commodities.FUEL, size - 1);
    demand(Commodities.SHIPS, size - 3);

    if (!isFunctional()) {
      supply.clear();
    }

    //        //Shortage
    //        Pair<String, Integer> deficit = getMaxDeficit(Commodities.CREW, Commodities.SUPPLIES,
    // Commodities.FUEL);
    //        int maxDeficit = size - 3; // to allow *some* production so economy doesn't get into
    // an unrecoverable state
    //        if (deficit.two > maxDeficit) deficit.two = maxDeficit;
    //        applyDeficitToProduction(2, deficit, Commodities.SHIPS);

    // Quality boost
    //        market.getStats().getDynamic().getMod(Stats.PRODUCTION_QUALITY_MOD).modifyFlat(
    //                id,
    //                0.3f,
    //                "Used Ship Seller"
    //        );
    //        float stability = market.getPrevStability();
    //        if (stability < 5) {
    //            float stabilityMod = (stability - 5f) / 5f;
    //            stabilityMod *= 0.5f;
    //
    // //market.getStats().getDynamic().getMod(Stats.PRODUCTION_QUALITY_MOD).modifyFlat(getModId(0),
    // stabilityMod, "Low stability at production source");
    //
    // market.getStats().getDynamic().getMod(Stats.PRODUCTION_QUALITY_MOD).modifyFlat(getModId(0),
    // stabilityMod, getNameForModifier() + " - low stability");
    //        }

    // Military Market
    boolean militaryBase = getSpec().hasTag(Industries.TAG_MILITARY);
    boolean command = getSpec().hasTag(Industries.TAG_COMMAND);
    MemoryAPI memory = market.getMemoryWithoutUpdate();
    if (militaryBase || command) {
      Misc.setFlagWithReason(memory, MemFlags.MARKET_MILITARY, getModId(), true, -1);
    }
  }

  @Override
  public void unapply() {
    super.unapply();
  }

  @Override
  public boolean isAvailableToBuild() {
    return false;
  }

  @Override
  public boolean showWhenUnavailable() {
    return false;
  }
}

package org.scy.campaign.econ.industries;

import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.util.Pair;

public class SCY_greenhouses extends BaseIndustry {

  @Override
  public void apply() {
    super.apply(true);

    int size = market.getSize();

    demand(Commodities.CREW, size - 1);
    demand(Commodities.HEAVY_MACHINERY, size - 2);
    demand(Commodities.VOLATILES, size - 3);

    Pair<String, Integer> deficit =
        getMaxDeficit(Commodities.HEAVY_MACHINERY, Commodities.CREW, Commodities.VOLATILES);

    supply(Commodities.FOOD, size - 2 - deficit.two);
    supply(Commodities.ORGANICS, size - 3 - deficit.two);

    //        applyDeficitToProduction(0, deficit, Commodities.FOOD, Commodities.ORGANICS);

    if (!isFunctional()) {
      supply.clear();
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

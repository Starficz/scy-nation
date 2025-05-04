package org.scy.campaign.econ.industries;

import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.util.Pair;

public class SCY_antimatter extends BaseIndustry {

  @Override
  public void apply() {

    super.apply(true);
    int size = market.getSize();

    demand(Commodities.HEAVY_MACHINERY, size - 2);
    demand(Commodities.CREW, size - 1);

    supply(Commodities.FUEL, size);

    Pair<String, Integer> deficit = getMaxDeficit(Commodities.HEAVY_MACHINERY, Commodities.CREW);

    applyDeficitToProduction(1, deficit, Commodities.FUEL);

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

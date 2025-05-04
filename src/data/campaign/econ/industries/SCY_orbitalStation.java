package data.campaign.econ.industries;

import com.fs.starfarer.api.impl.campaign.econ.impl.OrbitalStation;

import static org.scy.SCY_txt.txt;

public class SCY_orbitalStation extends OrbitalStation {

  @Override
  public boolean isAvailableToBuild() {
    return false;
  }

  @Override
  public String getUnavailableReason() {
    return txt("indus_station");
  }

  @Override
  public boolean showWhenUnavailable() {
    return false;
  }
}

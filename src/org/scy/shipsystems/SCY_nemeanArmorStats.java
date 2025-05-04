package org.scy.shipsystems;

import static org.scy.SCY_txt.txt;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import java.util.ArrayList;
import java.util.List;

public class SCY_nemeanArmorStats extends BaseShipSystemScript {

  @Override
  public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
    stats.getShieldDamageTakenMult().modifyMult(id, 1 - (0.9f * effectLevel));
    stats.getFluxDissipation().modifyMult(id, 2 * effectLevel);
    stats.getCombatEngineRepairTimeMult().modifyMult(id, 1 - (0.5f * effectLevel));
    stats.getCombatWeaponRepairTimeMult().modifyMult(id, 1 - (0.5f * effectLevel));

    List<ShipAPI> armors = new ArrayList<>();
    for (ShipAPI a : ((ShipAPI) stats.getEntity()).getChildModulesCopy()) {
      if (a.isAlive()) {
        armors.add(a);
      }
    }
    if (!armors.isEmpty()) {
      float DAMAGE_REDUCTION = 20;
      float reduction = -armors.size() * DAMAGE_REDUCTION * effectLevel;
      for (ShipAPI m : armors) {
        m.getMutableStats().getArmorDamageTakenMult().modifyPercent(id, reduction);
        m.getMutableStats().getHullDamageTakenMult().modifyPercent(id, reduction);
      }
      stats.getArmorDamageTakenMult().modifyPercent(id, reduction);
      stats.getHullDamageTakenMult().modifyPercent(id, reduction);
    }
  }

  @Override
  public void unapply(MutableShipStatsAPI stats, String id) {
    stats.getShieldDamageTakenMult().unmodify(id);
    stats.getFluxDissipation().unmodify(id);
    stats.getCombatEngineRepairTimeMult().unmodify(id);
    stats.getCombatWeaponRepairTimeMult().unmodify(id);

    List<ShipAPI> armors = ((ShipAPI) stats.getEntity()).getChildModulesCopy();
    if (!armors.isEmpty()) {
      for (ShipAPI m : armors) {
        m.getMutableStats().getArmorDamageTakenMult().unmodify(id);
        m.getMutableStats().getHullDamageTakenMult().unmodify(id);
      }
      stats.getArmorDamageTakenMult().unmodify(id);
      stats.getHullDamageTakenMult().unmodify(id);
    }
  }

  @Override
  public StatusData getStatusData(int index, State state, float effectLevel) {
    if (index == 0) {
      return new StatusData((int) Math.round(90 * effectLevel) + txt("sstm_lion0"), false);
    }
    if (index == 1) {
      return new StatusData((int) Math.round(200 * effectLevel) + txt("sstm_lion1"), false);
    }
    return null;
  }
}

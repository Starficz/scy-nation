package org.scy.shipsystems;

// import com.fs.starfarer.api.Global;
import static org.scy.SCY_txt.txt;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

public class SCY_targetingStats extends BaseShipSystemScript {

  private static final Map<String, targetingMode> MODE = new WeakHashMap<>();
  private final Map<targetingMode, String> ID = new HashMap<>();

  {
    ID.put(targetingMode.BALANCED, txt("tgt_balanced"));
    ID.put(targetingMode.HEAVY, txt("tgt_heavy"));
    ID.put(targetingMode.PD, txt("tgt_pd"));
  }

  //    private final String BALANCED = txt("tgt_balanced");
  private final String HEAVY = txt("tgt_heavy");
  private final String PD = txt("tgt_pd");

  private targetingMode toggle = targetingMode.BALANCED;

  private final float HEAVY_BONUS = 0.5f, HEAVY_MALUS = 0.1666f;
  private final float PD_BONUS = 3f, PD_MALUS = 0.5f;

  @Override
  public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {

    ShipAPI ship = (ShipAPI) stats.getEntity();
    if (ship == null) {
      return;
    }

    if (effectLevel == 1) {
      if (MODE.containsKey(ship.getId())) {
        toggle = MODE.get(ship.getId());
      } else {
        toggle = targetingMode.BALANCED;
      }

      switch (toggle) {
        case BALANCED:
          toggle = targetingMode.PD;
          applyPd(stats);
          break;
        case PD:
          toggle = targetingMode.HEAVY;
          applyHeavy(stats);
          break;
        case HEAVY:
          toggle = targetingMode.BALANCED;
          applyBalanced(stats);
          break;
      }
      MODE.put(ship.getId(), toggle);
    }
  }

  private void applyBalanced(MutableShipStatsAPI stats) {
    // reset
    stats.getBallisticWeaponRangeBonus().unmodify(HEAVY);
    stats.getEnergyWeaponRangeBonus().unmodify(HEAVY);
    stats.getNonBeamPDWeaponRangeBonus().unmodify(HEAVY);
    stats.getBeamPDWeaponRangeBonus().unmodify(HEAVY);

    stats.getBallisticWeaponRangeBonus().unmodify(PD);
    stats.getEnergyWeaponRangeBonus().unmodify(PD);
    stats.getNonBeamPDWeaponRangeBonus().unmodify(PD);
    stats.getBeamPDWeaponRangeBonus().unmodify(PD);
    stats.getAutofireAimAccuracy().unmodify(PD);

  }

  private void applyHeavy(MutableShipStatsAPI stats) {
    // non PD boost
    stats.getBallisticWeaponRangeBonus().modifyPercent(HEAVY, HEAVY_BONUS * 100);
    stats.getEnergyWeaponRangeBonus().modifyPercent(HEAVY, HEAVY_BONUS * 100);

    stats.getNonBeamPDWeaponRangeBonus().modifyMult(HEAVY, HEAVY_MALUS);
    stats.getBeamPDWeaponRangeBonus().modifyMult(HEAVY, HEAVY_MALUS);

    stats.getBallisticWeaponRangeBonus().unmodify(PD);
    stats.getEnergyWeaponRangeBonus().unmodify(PD);
    stats.getNonBeamPDWeaponRangeBonus().unmodify(PD);
    stats.getBeamPDWeaponRangeBonus().unmodify(PD);
    stats.getAutofireAimAccuracy().unmodify(PD);
  }

  private void applyPd(MutableShipStatsAPI stats) {
    // PD boost
    stats.getNonBeamPDWeaponRangeBonus().modifyPercent(PD, PD_BONUS * 100);
    stats.getBeamPDWeaponRangeBonus().modifyPercent(PD, PD_BONUS * 100);
    stats.getAutofireAimAccuracy().modifyPercent(PD, PD_BONUS * 100);

    stats.getBallisticWeaponRangeBonus().modifyMult(PD, PD_MALUS);
    stats.getEnergyWeaponRangeBonus().modifyMult(PD, PD_MALUS);

    stats.getBallisticWeaponRangeBonus().unmodify(HEAVY);
    stats.getEnergyWeaponRangeBonus().unmodify(HEAVY);
    stats.getNonBeamPDWeaponRangeBonus().unmodify(HEAVY);
    stats.getBeamPDWeaponRangeBonus().unmodify(HEAVY);
  }

  @Override
  public void unapply(MutableShipStatsAPI stats, String id) {
    // reset
    stats.getBallisticWeaponRangeBonus().unmodify(HEAVY);
    stats.getEnergyWeaponRangeBonus().unmodify(HEAVY);
    stats.getNonBeamPDWeaponRangeBonus().unmodify(HEAVY);
    stats.getBeamPDWeaponRangeBonus().unmodify(HEAVY);

    stats.getBallisticWeaponRangeBonus().unmodify(PD);
    stats.getEnergyWeaponRangeBonus().unmodify(PD);
    stats.getNonBeamPDWeaponRangeBonus().unmodify(PD);
    stats.getBeamPDWeaponRangeBonus().unmodify(PD);
  }

  @Override
  public StatusData getStatusData(int index, State state, float effectLevel) {

    if (toggle == targetingMode.BALANCED) {
      return null;
    } else if (toggle == targetingMode.HEAVY) {
      switch (index) {
        case 0:
          return new StatusData(
              txt("tgt_heavyBonus") + (int) (HEAVY_BONUS * 100) + txt("%"), false);
        case 1:
          return new StatusData(
              txt("tgt_heavyMalus")
                  + (int) ((1 - (1 + HEAVY_BONUS) * HEAVY_MALUS) * 100)
                  + txt("%"),
              true);
        default:
          break;
      }
    } else {
      switch (index) {
        case 0:
          return new StatusData(
              txt("tgt_pdBonus") + (int) (((1 + PD_BONUS) * PD_MALUS - 1) * 100) + txt("%"), false);
        case 1:
          return new StatusData(txt("tgt_pdMalus") + (int) (PD_MALUS * 100) + txt("%"), true);
        default:
          break;
      }
    }

    return null;
  }

  public static targetingMode getMode(ShipAPI ship) {
    return MODE.get(ship.getId());
  }

  public void clear() {
    MODE.clear();
  }

  @Override
  public String getInfoText(ShipSystemAPI system, ShipAPI ship) {
    return ID.get(toggle);
  }

  @Override
  public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
    if (ship != null) {
      return !ship.getFluxTracker().isOverloadedOrVenting();
    }
    return true;
  }

  public enum targetingMode {
    BALANCED,
    HEAVY,
    PD
  }
}

package data.hullmods;

import static data.scripts.util.SCY_settingsData.engineering_noncompatible;
import static data.scripts.util.SCY_txt.txt;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import data.scripts.util.StarficzAIUtils;
import org.magiclib.util.MagicIncompatibleHullmods;

import java.util.*;

public class SCY_engineering extends BaseHullMod {

  private final Map<HullSize, Float> VENTING_BONUS = new HashMap();

  {
    VENTING_BONUS.put(HullSize.FIGHTER, 2.5f);
    VENTING_BONUS.put(HullSize.FRIGATE, 2.5f);
    VENTING_BONUS.put(HullSize.DESTROYER, 2.25f);
    VENTING_BONUS.put(HullSize.CRUISER, 2f);
    VENTING_BONUS.put(HullSize.CAPITAL_SHIP, 1.75f);
  }

  private final float SENSOT_STILL = -25, SENSOR_MOVE = 25;
  private String ID;
  private float VENT_MULT = 3.5f;
  private float VENT_PERCENT_PER_CAP = 1f;
  private float CAP_MULT = 2f;


  @Override
  public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
    stats.getVentRateMult().modifyMult(id, VENT_MULT);
    stats.getEngineDamageTakenMult().modifyMult(id, 0.75f);
    stats.getCombatEngineRepairTimeMult().modifyMult(id, 0.75f);
    ID = id;
  }

  @Override
  public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
    for (String tmp : engineering_noncompatible) {
      if (ship.getVariant().getHullMods().contains(tmp)) {
        MagicIncompatibleHullmods.removeHullmodWithWarning(ship.getVariant(), tmp, "SCY_engineering");
      }
    }
    ship.getMutableStats().getFluxCapacity().modifyMult(id, CAP_MULT);
    ship.getMutableStats().getVentRateMult().modifyPercent(id, ship.getVariant().getNumFluxCapacitors()*VENT_PERCENT_PER_CAP);
    if(!ship.hasListenerOfClass(SCYVentingAI.class) && ship.getHullSize() != HullSize.FIGHTER) ship.addListener(new SCYVentingAI(ship));
  }

  public static class SCYVentingAI implements AdvanceableListener{
    public IntervalUtil damageTracker = new IntervalUtil(0.2F, 0.3F);
    public CombatEngineAPI engine;
    public ShipAPI ship;
    public ShipAPI target;
    public float lastUpdatedTime = 0f;
    public List<StarficzAIUtils.FutureHit> incomingProjectiles = new ArrayList<>();
    public List<StarficzAIUtils.FutureHit> predictedWeaponHits = new ArrayList<>();
    public List<StarficzAIUtils.FutureHit> combinedHits = new ArrayList<>();
    SCYVentingAI(ShipAPI ship){
      this.ship = ship;
    }

    @Override
    public void advance(float amount) {
      engine = Global.getCombatEngine();
      if (!ship.isAlive() || ship.getParentStation() != null || engine == null || !engine.isEntityInPlay(ship)) {
        return;
      }

      // Calculate Decision Flags

      damageTracker.advance(amount);
      if (damageTracker.intervalElapsed()) {
        lastUpdatedTime = Global.getCombatEngine().getTotalElapsedTime(false);
        incomingProjectiles = StarficzAIUtils.incomingProjectileHits(ship, ship.getLocation());
        float timeToPredict = Math.max(ship.getFluxTracker().getTimeToVent() + damageTracker.getMaxInterval(), 3f);
        predictedWeaponHits = StarficzAIUtils.generatePredictedWeaponHits(ship, ship.getLocation(), timeToPredict);
        combinedHits = new ArrayList<>();
        combinedHits.addAll(incomingProjectiles);
        combinedHits.addAll(predictedWeaponHits);
      }

      // calculate how much damage the ship would take if unphased/vent/used system
      float currentTime = Global.getCombatEngine().getTotalElapsedTime(false);
      float timeElapsed = currentTime - lastUpdatedTime;
      float bufferTime = 0.2f; // 0.2 sec of buffer time before getting hit
      float armorBase = StarficzAIUtils.getCurrentArmorRating(ship);
      float armorMax = ship.getArmorGrid().getArmorRating();
      float armorMinLevel = ship.getMutableStats().getMinArmorFraction().getModifiedValue();
      float armorVent = armorBase;

      float hullDamageIfVent = 0f;
      float empDamageIfVent = 0f;

      float mountHP = 0f;
      for (WeaponAPI weapon : ship.getAllWeapons()) {
        mountHP += weapon.getCurrHealth();
      }
      float empDamageLevelVent = empDamageIfVent / mountHP;

      for (StarficzAIUtils.FutureHit hit : combinedHits) {
        float timeToHit = (hit.timeToHit - timeElapsed);
        if (timeToHit < -0.1f) continue; // skip hits that have already happened
        if (timeToHit < ship.getFluxTracker().getTimeToVent() + bufferTime) {
          Pair<Float, Float> trueDamage = StarficzAIUtils.damageAfterArmor(hit.damageType, hit.damage, hit.hitStrength, armorVent, ship);
          hullDamageIfVent += trueDamage.two;
          empDamageIfVent += hit.empDamage;
          armorVent = Math.max(armorVent - trueDamage.one, armorMinLevel * armorMax);
        }
      }

      float armorDamageLevelVent = (armorBase - armorVent) / armorMax;
      float hullDamageLevelVent = hullDamageIfVent / (ship.getHitpoints() * ship.getHullLevel());

      ShipVariantAPI variant = Global.getSettings().getVariant(ship.getHullSpec().getBaseHullId() + "_combat");
      float modules = variant == null ? 0 : variant.getStationModules().size();

      float alive = 0;
      for(ShipAPI module : ship.getChildModulesCopy()) {
        if (module.getHitpoints() <= 0f) continue;
        alive++;
      }

      float damageRiskMult = Misc.interpolate(1,5, modules > 0 ? alive/modules : 0);


      if (!engine.isUIAutopilotOn() || engine.getPlayerShip() != ship) {
        // vent control
        if (ship.getFluxLevel() > 0.3f && armorDamageLevelVent < (0.03f*damageRiskMult) && hullDamageLevelVent < (0.03f*damageRiskMult) && empDamageLevelVent < (0.5f*damageRiskMult)) {
          ship.giveCommand(ShipCommand.VENT_FLUX, null, 0);
        } else {
          ship.blockCommandForOneFrame(ShipCommand.VENT_FLUX);
        }
      }
    }
  }

  @Override
  public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
    if (index == 0) return "25" + txt("%");
    if (index == 1) return CAP_MULT + "x";
    if (index == 2) return VENT_MULT + "x";
    if (index == 3) return "+" + VENT_PERCENT_PER_CAP + txt("%");

    return null;
  }

  @Override
  public void advanceInCampaign(FleetMemberAPI member, float amount) {
    if (member.getFleetData() != null
        && member.getFleetData().getFleet() != null
        && !member.isMothballed()
        && !member.getFleetCommander().isPlayer()) {
      if (member.getFleetData().getFleet().getCurrBurnLevel() < 3) {
        //                member.getStats().getSensorProfile().modifyPercent(ID, -SENSOR_OFFSET);
        member.getStats().getSensorProfile().modifyPercent(ID, SENSOT_STILL);
      } else {
        //                member.getStats().getSensorProfile().modifyPercent(ID, +SENSOR_OFFSET);
        member.getStats().getSensorProfile().modifyPercent(ID, SENSOR_MOVE);
      }
    }
  }
}

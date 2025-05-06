package org.scy.hullmods;

import static org.scy.SCY_txt.txt;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import com.fs.starfarer.combat.entities.DamagingExplosion;
import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.DefenseUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SCY_modularArmor extends BaseHullMod {

  private final String SCY_ARMOR_MOD = "SCY_ARMOR_MOD";
  protected Object SPEED_STATUS_KEY = new Object();
  private final float SPEED_BONUS = 0.25f;

  @Override
  public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
    if(!ship.hasListenerOfClass(ExplosionOcclusionRaycast.class)) ship.addListener(new ExplosionOcclusionRaycast());
  }

  @Override
  public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
    if (index == 0) return (int) (100 * SPEED_BONUS) + txt("%");
    return null;
  }

  @Override
  public void addPostDescriptionSection(
          TooltipMakerAPI tooltip,
          ShipAPI.HullSize hullSize,
          ShipAPI ship,
          float width,
          boolean isForModSpec) {

    if (ship == null) return;
    ShipVariantAPI variant = Global.getSettings().getVariant(ship.getHullSpec().getBaseHullId() + "_combat");


    if (variant == null) return;
    if (ship.getVariant().getStationModules().isEmpty()) return;

    // title
    tooltip.addSectionHeading(txt("hm_armorStat0"), Alignment.MID, 15);

    tooltip.beginTable(
            Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(),
            Misc.getBrightPlayerColor(),
            20f,
            true,
            true,
            new Object[]{"Armor Location", width - 80f * 2 - 8f, "Hull", 80f, "Armor", 80f});

    // getting the stats of child modules in refit shouldn't have to be this hard
    Pattern scyPattern = Pattern.compile("SCY_.+?[AFR][FLR][LR]?", Pattern.CASE_INSENSITIVE);

    for (String module : variant.getStationModules().values()) {
      Matcher matcher = scyPattern.matcher(module);

      if(matcher.find()){
        ShipHullSpecAPI hull = Global.getSettings().getHullSpec(matcher.group());
        HPmults mults = getTotalMults(ship.getVariant(), hull.getArmorRating(), hull.getHitpoints());

        Color hullTextColor = mults.hullMult < 0.99f ? Misc.getPositiveHighlightColor() : (mults.hullMult > 1.01f ? Misc.getNegativeHighlightColor() : Misc.getTextColor());
        Color armorTextColor = mults.armorMult < 0.99f ? Misc.getPositiveHighlightColor() : (mults.armorMult > 1.01f ? Misc.getNegativeHighlightColor() : Misc.getTextColor());

        tooltip.addRow(Alignment.MID, Misc.getTextColor(), hull.getHullName(),
                Alignment.MID, hullTextColor, String.valueOf(Math.round(hull.getHitpoints() / mults.hullMult)),
                Alignment.MID, armorTextColor, String.valueOf(Math.round(hull.getArmorRating() / mults.armorMult)));
      }
    }
    tooltip.addTable("-", 0, 4f);

    tooltip.setBulletedListMode("");
    tooltip.setBulletWidth(0f);

    tooltip.addPara("Hold 1 to highlight armor locations, 2 to revert.", Misc.getGrayColor(), 10);
    Color fadeAwayColor = Keyboard.isKeyDown(2) ? new Color(200,200,255, 80) : Color.white;
    if (Keyboard.isKeyDown(2) || Keyboard.isKeyDown(3)) {
      ship.getSpriteAPI().setColor(fadeAwayColor);
      for(WeaponAPI weapon : ship.getAllWeapons()){
        if(Objects.equals(weapon.getSlot().getId(), "PUSHER_PLATE")){
          weapon.getSprite().setColor(fadeAwayColor);
        }
        else if(weapon.getSprite() != null && weapon.isDecorative() &&
                !weapon.getId().contains("flame") && !weapon.getId().contains("Heat")){
          weapon.getSprite().setColor(Keyboard.isKeyDown(2) ? new Color(255,255,255, 0) : Color.white);
        }
        else if(weapon.getSprite() != null && !weapon.isDecorative()){
          weapon.getSprite().setColor(fadeAwayColor);
          if(weapon.getBarrelSpriteAPI() != null)
            weapon.getBarrelSpriteAPI().setColor(fadeAwayColor);
        }
      }
    }
  }

  @Override
  public void advanceInCombat(ShipAPI ship, float amount) {
    //Yoinked whole-cloth from SCY. <3 ya Tarty
    //Note by Starficz, I've refactored this code so much I have no clue how much was from Tart anymore
    if (ship==null || Global.getCombatEngine().isPaused()) {
      return;
    }

    if (!ship.isAlive()) {
      removeStats(ship);
      return;
    }

    // apply speed boost for main ship and durability buffs to modules from main ships hullmods
    ShipVariantAPI variant = Global.getSettings().getVariant(ship.getHullSpec().getBaseHullId() + "_combat");
    float modules = variant == null ? 0 : variant.getStationModules().size();

    float alive = 0;
    for(ShipAPI module : ship.getChildModulesCopy()){
      if (module.getHitpoints() <= 0f) continue;
      alive++;
      if(ship.getVariant() == null || module.getVariant() == null) continue;

      ShipHullSpecAPI hullSpec = module.getVariant().getHullSpec();
      HPmults mults = getTotalMults(ship.getVariant(), hullSpec.getArmorRating(), hullSpec.getHitpoints());

      module.getMutableStats().getHullDamageTakenMult().modifyMult(SCY_ARMOR_MOD, mults.hullMult);
      module.getMutableStats().getArmorDamageTakenMult().modifyMult(SCY_ARMOR_MOD, mults.armorMult);
      module.getMutableStats().getKineticDamageTakenMult().modifyMult(SCY_ARMOR_MOD, mults.keMult);
      module.getMutableStats().getEnergyDamageTakenMult().modifyMult(SCY_ARMOR_MOD, mults.energyMult);
      module.getMutableStats().getHighExplosiveDamageTakenMult().modifyMult(SCY_ARMOR_MOD, mults.heMult);
    }

    if(modules!=0){
      //speed bonus applies linearly
      float speedRatio=1 - (alive / modules);
      applyStats(speedRatio, ship);
    }
  }

  private void removeStats(ShipAPI ship) {
    ship.getMutableStats().getMaxSpeed().unmodify(SCY_ARMOR_MOD);
    ship.getMutableStats().getAcceleration().unmodify(SCY_ARMOR_MOD);
    ship.getMutableStats().getDeceleration().unmodify(SCY_ARMOR_MOD);
    ship.getMutableStats().getMaxTurnRate().unmodify(SCY_ARMOR_MOD);
    ship.getMutableStats().getTurnAcceleration().unmodify(SCY_ARMOR_MOD);
  }

  private void applyStats(float speedRatio, ShipAPI ship) {
    ship.getMutableStats().getMaxSpeed().modifyMult(SCY_ARMOR_MOD, (1 + (speedRatio * SPEED_BONUS)));
    ship.getMutableStats().getAcceleration().modifyMult(SCY_ARMOR_MOD, (1 + (speedRatio * SPEED_BONUS)));
    ship.getMutableStats().getDeceleration().modifyMult(SCY_ARMOR_MOD, (1 + (speedRatio * SPEED_BONUS)));
    ship.getMutableStats().getMaxTurnRate().modifyMult(SCY_ARMOR_MOD, (1 + (speedRatio * SPEED_BONUS)));
    ship.getMutableStats().getTurnAcceleration().modifyMult(SCY_ARMOR_MOD, (1 + (speedRatio * SPEED_BONUS)));

    CombatEngineAPI engine = Global.getCombatEngine();
    if(engine.getPlayerShip() == ship && speedRatio > 0.01f){
      String modularIcon = Global.getSettings().getSpriteName("icons", "scy_modules");
      engine.maintainStatusForPlayerShip(SPEED_STATUS_KEY, modularIcon, "Damaged Modular Armor", "+" + Math.round((speedRatio * SPEED_BONUS * 100)) + " top speed" , false);
    }
  }

  public static class HPmults{
    public float armorMult = 1f, hullMult = 1f;
    public float keMult = 1f, energyMult = 1f, heMult = 1f;
  }

  public HPmults getTotalMults(ShipVariantAPI variant, float baseArmor, float baseHull){
    HPmults mults = new HPmults();
    if(variant == null) return mults;

    Map<String, ArmorEffect> effects = HULLMOD_EFFECTS.get(variant.getHullSize());
    float totalArmorFlat = 0;
    float totalArmorPercent = 0;
    float totalHullFlat = 0;
    float totalHullPercent = 0;

    for(String hullmodID : variant.getHullMods()){
      if(effects.containsKey(hullmodID)){
        ArmorEffect effect = effects.get(hullmodID);
        totalArmorFlat += effect.armorFlat;
        totalArmorPercent += effect.armorPercent;
        totalHullFlat += effect.hullFlat;
        totalHullPercent += effect.hullPercent;
        mults.keMult *= effect.keMult;
        mults.energyMult *= effect.energyMult;
        mults.heMult *= effect.heMult;
      }
    }

    mults.armorMult = baseArmor / (baseArmor + totalArmorFlat + (baseArmor * totalArmorPercent));
    mults.hullMult = baseHull / (baseHull + totalHullFlat + (baseHull * totalHullPercent));

    return mults;
  }

  private static final Map<ShipAPI.HullSize, Map<String, ArmorEffect>> HULLMOD_EFFECTS = new HashMap<>();

  static {
    Map<String, ArmorEffect> hullmodMap = new HashMap<>();
    hullmodMap.put(HullMods.REINFORCEDHULL, new ArmorEffect(0,0,0,0.4f));
    hullmodMap.put(HullMods.BLAST_DOORS, new ArmorEffect(0,0,0,0.2f));
    hullmodMap.put(HullMods.INSULATEDENGINE, new ArmorEffect(0,0,0,0.1f));
    hullmodMap.put(HullMods.ARMOREDWEAPONS, new ArmorEffect(0,0.1f,0,0));
    hullmodMap.put(HullMods.SHIELD_SHUNT, new ArmorEffect(0,0.15f,0,0));
    hullmodMap.put(HullMods.COMP_HULL, new ArmorEffect(0,0f,0,-0.2f));
    hullmodMap.put(HullMods.COMP_ARMOR, new ArmorEffect(0,-0.2f,0,0));
    hullmodMap.put(HullMods.COMP_STRUCTURE, new ArmorEffect(0,-0.2f,0,-0.2f));
    hullmodMap.put("TADA_lightArmor", new ArmorEffect(0,-1.0f,0,0f));

    hullmodMap.put("TADA_reactiveArmor", new ArmorEffect(1.25f, 1.25f, 0.66f));

    Map<String, ArmorEffect> capitalHullmodMap = new HashMap<>(hullmodMap);
    capitalHullmodMap.put(HullMods.HEAVYARMOR, new ArmorEffect(500,0,0,0));
    HULLMOD_EFFECTS.put(ShipAPI.HullSize.CAPITAL_SHIP, capitalHullmodMap);

    Map<String, ArmorEffect> cruiserHullmodMap = new HashMap<>(hullmodMap);
    cruiserHullmodMap.put(HullMods.HEAVYARMOR, new ArmorEffect(400,0,0,0));
    HULLMOD_EFFECTS.put(ShipAPI.HullSize.CRUISER, cruiserHullmodMap);

    Map<String, ArmorEffect> destroyerHullmodMap = new HashMap<>(hullmodMap);
    destroyerHullmodMap.put(HullMods.HEAVYARMOR, new ArmorEffect(300,0,0,0));
    HULLMOD_EFFECTS.put(ShipAPI.HullSize.DESTROYER, destroyerHullmodMap);

    Map<String, ArmorEffect> frigateHullmodMap = new HashMap<>(hullmodMap);
    frigateHullmodMap.put(HullMods.HEAVYARMOR, new ArmorEffect(150,0,0,0));
    HULLMOD_EFFECTS.put(ShipAPI.HullSize.FRIGATE, frigateHullmodMap);

    Map<String, ArmorEffect> fighterHullmodMap = new HashMap<>(hullmodMap);
    fighterHullmodMap.put(HullMods.HEAVYARMOR, new ArmorEffect(75,0,0,0));
    HULLMOD_EFFECTS.put(ShipAPI.HullSize.FIGHTER, fighterHullmodMap);
  }

  public static class ArmorEffect {
    public float armorFlat = 0f, armorPercent = 0f, hullFlat = 0f, hullPercent = 0f;
    public float keMult = 1f, energyMult = 1f, heMult = 1f;

    ArmorEffect(float aF, float aP, float hF, float hP){
      armorFlat = aF; armorPercent = aP; hullFlat = hF; hullPercent = hP;
    }

    ArmorEffect(float keM, float eneM, float heM){
      keMult = keM; energyMult = eneM; heMult = heM;
    }
  }
}

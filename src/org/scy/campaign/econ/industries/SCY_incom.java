package org.scy.campaign.econ.industries;

import static org.scy.SCY_txt.txt;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.comm.CommMessageAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathBaseIntel;
// import com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathCellsIntel;
// import com.fs.starfarer.api.impl.campaign.intel.bases.PirateActivityIntel;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel;
// import com.fs.starfarer.api.impl.campaign.intel.raid.BaseRaidStage;
// import com.fs.starfarer.api.impl.campaign.intel.raid.RaidIntel;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import org.scy.campaign.econ.SCY_items;
import java.awt.Color;
import org.apache.log4j.Logger;

public class SCY_incom extends BaseIndustry {

  @Override
  public void apply() {
    super.apply(true);

    int size = market.getSize();

    demand(Commodities.CREW, size + 1);
    demand(Commodities.SUPPLIES, size);
    demand(Commodities.MARINES, size - 1);
    demand(Commodities.HEAVY_MACHINERY, size - 2);
    demand(Commodities.FUEL, size - 3);
    demand(Commodities.SHIPS, size - 4);
    demand(Commodities.LUXURY_GOODS, size - 5);
    demand(Commodities.DRUGS, size - 6);
    demand(Commodities.ORGANS, size - 7);
    demand(Commodities.HAND_WEAPONS, size - 8);

    Pair<String, Integer> deficit =
        getMaxDeficit(
            Commodities.CREW,
            Commodities.SUPPLIES,
            Commodities.MARINES,
            Commodities.HEAVY_MACHINERY,
            Commodities.FUEL,
            Commodities.SHIPS,
            Commodities.LUXURY_GOODS,
            Commodities.DRUGS,
            Commodities.ORGANS,
            Commodities.HAND_WEAPONS);

    supply(SCY_items.INTEL, size - 4 - deficit.two);

    //        applyDeficitToProduction(0, deficit, SCY_items.INTEL);

    if (!isFunctional()) {
      supply.clear();
    }
  }

  private final IntervalUtil tracker = new IntervalUtil(30, 90);
  // DEBUG
  //    private Integer maxDelay=3, minDelay=1;
  static Logger log = Global.getLogger(SCY_incom.class);
  private boolean bestFriends = false, aiCore = false;

  @Override
  public void advance(float amount) {

    // Allow the industry when well connected
    if (!bestFriends) {
      if (Global.getSector()
          .getPlayerFaction()
          .getRelationshipLevel("SCY")
          .isAtWorst(RepLevel.WELCOMING)) {
        Global.getSector().getPlayerFaction().addKnownIndustry(getId());
        log.error("Intelligence Command: added to player");
        bestFriends = true;
      }
    }

    // skip the rest if not a player market
    if (!market.isPlayerOwned()) {
      return;
    }

    super.advance(amount);
    if (Global.getSector().getEconomy().isSimMode()) return;
    if (!isFunctional()) return;

    float days = Global.getSector().getClock().convertToDays(amount);
    tracker.advance(days);

    // alpha core removed or downgraded
    Integer minDelay = 30;
    Integer maxDelay = 90;
    if (aiCore
        && (market.getIndustry("SCY_incom").getAICoreId() == null
            || !market.getIndustry("SCY_incom").getAICoreId().equals("alpha_core"))) {
      log.error("Max reveal delay set to " + maxDelay + " for " + market.getName());
      tracker.setInterval(minDelay, maxDelay);
      aiCore = false;
    } else if (!aiCore
        && market.getIndustry("SCY_incom").getAICoreId() != null
        && market.getIndustry("SCY_incom").getAICoreId().equals("alpha_core")) {
      // ALPHA ai core installed
      log.error("Max reveal delay set to " + 2 * maxDelay / 3 + " for " + market.getName());
      tracker.setInterval(minDelay, 2f * maxDelay / 3);
      aiCore = true;
    }

    if (tracker.intervalElapsed()) {
      if (!market.getIndustry("SCY_incom").getAllDeficit().isEmpty()) {
        return;
      }
      boolean revealed = false;
      //            boolean removed = false;

      //            //size/stability removal chance
      //            float removal = 1f+(market.getSize()/40);
      //            removal *= 0.5f+(market.getStabilityValue()/5);

      // reveal threats
      MarketAPI intel_market = market;

      for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel()) {
        log.info("Found intel: " + i.getClass().toString());
        if (i instanceof LuddicPathBaseIntel && ((LuddicPathBaseIntel) i).isHidden()) {
          ((LuddicPathBaseIntel) i).makeKnown();
          log.info("Revealing: " + ((LuddicPathBaseIntel) i).getName());
          intel_market = ((LuddicPathBaseIntel) i).getMarket();
          revealed = true;
          break;
        }
        if (i instanceof PirateBaseIntel && ((PirateBaseIntel) i).isHidden()) {
          ((PirateBaseIntel) i).makeKnown();
          log.info("Revealing: " + ((PirateBaseIntel) i).getName());
          intel_market = ((PirateBaseIntel) i).getMarket();
          revealed = true;
          break;
        }
      }
      //            if(!revealed && Math.random()<removal){
      //                log.info("No hiden base to reveal, disrupting their operations instead.");
      //                for(IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel()){
      //                    log.info("Found intel: "+i.getClass().toString());
      //
      //                    if(i instanceof LuddicPathCellsIntel
      ////                            && ((LuddicPathCellsIntel)i).getMarket().isPlayerOwned()
      //                            ){
      //                        ((LuddicPathCellsIntel)i).endImmediately();
      //                        log.info("Dissolving Pather cell on:
      // "+((LuddicPathCellsIntel)i).getMarket().getName());
      //                        intel_market = ((LuddicPathCellsIntel)i).getMarket();
      //                        removed=true;
      //                        break;
      //                    }
      //
      //                    if(i instanceof PirateActivityIntel
      ////                            &&
      // ((PirateActivityIntel)i).getSystem()==market.getStarSystem()
      //                            ){
      //                        ((PirateActivityIntel)i).endImmediately();
      //                        log.info("Disrupting Pirates operations in:
      // "+((PirateActivityIntel)i).getSystem().getName());
      //                        intel_market = market;
      //                        removed=true;
      //                        break;
      //                    }
      ////
      ////                    if(i instanceof RaidIntel
      ////                            && ((RaidIntel)i).isPlayerTargeted()
      ////                            ){
      ////                        ((RaidIntel)i).
      ////                    }
      //                }
      //            }

      if (revealed) {
        if (market.isPlayerOwned()) {
          MessageIntel intel =
              new MessageIntel(
                  getCurrentName() + txt("indus_intel_0") + market.getName(),
                  Misc.getBasePlayerColor());
          intel.addLine(
              BaseIntelPlugin.BULLET
                  + txt("indus_intel_1")
                  + intel_market.getStarSystem().getName());
          intel.setIcon(Global.getSector().getPlayerFaction().getCrest());
          intel.setSound(BaseIntelPlugin.getSoundStandardUpdate());
          //                    intel.setPostingLocation(intel_market.getPrimaryEntity());
          Global.getSector()
              .getCampaignUI()
              .addMessage(intel, CommMessageAPI.MessageClickAction.INTEL_TAB, market);
        }
      }

    }
  }

  @Override
  public void unapply() {
    super.unapply();
  }

  @Override
  public boolean isAvailableToBuild() {
    if (!Global.getSector().getPlayerFaction().knowsIndustry(getId())) {
      return false;
    }
    return market.getPlanetEntity() != null;
  }

  @Override
  public boolean showWhenUnavailable() {
    return Global.getSector().getPlayerFaction().knowsIndustry(getId());
  }

  @Override
  protected void addAlphaCoreDescription(TooltipMakerAPI tooltip, AICoreDescriptionMode mode) {
    float opad = 10f;
    Color highlight = Misc.getHighlightColor();

    String pre = txt("indus_intel_2");
    if (mode == AICoreDescriptionMode.MANAGE_CORE_DIALOG_LIST
        || mode == AICoreDescriptionMode.INDUSTRY_TOOLTIP) {
      pre = txt("indus_intel_3");
    }

    if (mode == AICoreDescriptionMode.INDUSTRY_TOOLTIP) {
      CommoditySpecAPI coreSpec = Global.getSettings().getCommoditySpec(aiCoreId);
      TooltipMakerAPI text = tooltip.beginImageWithText(coreSpec.getIconName(), 48);
      text.addPara(pre + txt("indus_intel_4"), 0f, highlight, txt("indus_intel_5"));
      tooltip.addImageWithText(opad);
      return;
    }

    tooltip.addPara(pre + txt("indus_intel_4"), 0f, highlight, txt("indus_intel_5"));
  }
}

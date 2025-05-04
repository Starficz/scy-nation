package org.scy.world;

import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorGeneratorPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.shared.SharedData;

// import java.util.List;

public class SCY_gen implements SectorGeneratorPlugin {

  @Override
  public void generate(SectorAPI sector) {

    new SCY_acheron().generate(sector);
    new SCY_outposts().generate(sector);

    FactionAPI scy = sector.getFaction("SCY");

      FactionAPI player = sector.getFaction(Factions.PLAYER);
    FactionAPI hegemony = sector.getFaction(Factions.HEGEMONY);
    FactionAPI tritachyon = sector.getFaction(Factions.TRITACHYON);
    FactionAPI pirates = sector.getFaction(Factions.PIRATES);
    FactionAPI independent = sector.getFaction(Factions.INDEPENDENT);
    FactionAPI church = sector.getFaction(Factions.LUDDIC_CHURCH);
    FactionAPI path = sector.getFaction(Factions.LUDDIC_PATH);
    FactionAPI kol = sector.getFaction(Factions.KOL);
    FactionAPI diktat = sector.getFaction(Factions.DIKTAT);
    FactionAPI persean = sector.getFaction(Factions.PERSEAN);
    FactionAPI guard = sector.getFaction(Factions.LIONS_GUARD);
    FactionAPI remnant = sector.getFaction(Factions.REMNANTS);
    FactionAPI derelict = sector.getFaction(Factions.DERELICT);

    scy.setRelationship(player.getId(), -0.1f);
    scy.setRelationship(hegemony.getId(), -0.2f);
    scy.setRelationship(tritachyon.getId(), -0.15f);
    scy.setRelationship(pirates.getId(), -0.7f);
    scy.setRelationship(independent.getId(), 0.1f);
    scy.setRelationship(persean.getId(), 0.15f);
    scy.setRelationship(church.getId(), -0.2f);
    scy.setRelationship(path.getId(), -0.7f);
    scy.setRelationship(kol.getId(), -0.25f);
    scy.setRelationship(diktat.getId(), -0.1f);
    scy.setRelationship(guard.getId(), -0.15f);
    scy.setRelationship(remnant.getId(), -0.7f);
    scy.setRelationship(derelict.getId(), -0.25f);

    // modded factions
    scy.setRelationship("ORA", RepLevel.WELCOMING);
    scy.setRelationship("shadow_industry", RepLevel.WELCOMING);
    scy.setRelationship("syndicate_asp", RepLevel.WELCOMING);

    scy.setRelationship("citadeldefenders", RepLevel.FAVORABLE);

    scy.setRelationship("sun_ice", RepLevel.NEUTRAL);
    scy.setRelationship("pn_colony", RepLevel.NEUTRAL);

    scy.setRelationship("interstellarimperium", RepLevel.SUSPICIOUS);
    scy.setRelationship("neutrinocorp", RepLevel.SUSPICIOUS);
    scy.setRelationship("tiandong", RepLevel.SUSPICIOUS);
    scy.setRelationship("metelson", RepLevel.SUSPICIOUS);
    scy.setRelationship("dassault_mikoyan", RepLevel.SUSPICIOUS);

    scy.setRelationship("pack", RepLevel.INHOSPITABLE);
    scy.setRelationship("diableavionics", RepLevel.INHOSPITABLE);
    scy.setRelationship("Coalition", RepLevel.INHOSPITABLE);
    scy.setRelationship("6eme_bureau", RepLevel.INHOSPITABLE);

    scy.setRelationship("mayorate", RepLevel.HOSTILE);
    scy.setRelationship("pirateAnar", RepLevel.HOSTILE);
    scy.setRelationship("sun_ici", RepLevel.HOSTILE);
    scy.setRelationship("blackrock_driveyards", RepLevel.HOSTILE);
    scy.setRelationship("junk_pirates", RepLevel.HOSTILE);
    scy.setRelationship("exigency", RepLevel.HOSTILE);
    scy.setRelationship("exipirated", RepLevel.HOSTILE);
    scy.setRelationship("cabal", RepLevel.HOSTILE);
    scy.setRelationship("the_deserter", RepLevel.HOSTILE);
    scy.setRelationship("blade_breakers", RepLevel.HOSTILE);

    scy.setRelationship("the_deserter", RepLevel.HOSTILE);

    scy.setRelationship("crystanite", RepLevel.VENGEFUL);
    scy.setRelationship("new_galactic_order", RepLevel.VENGEFUL);
    scy.setRelationship("explorer_society", RepLevel.VENGEFUL);

    scy.setRelationship("noir", RepLevel.NEUTRAL);
    scy.setRelationship("Lte", RepLevel.NEUTRAL);
    scy.setRelationship("GKSec", RepLevel.NEUTRAL);
    scy.setRelationship("gmda", RepLevel.NEUTRAL);
    scy.setRelationship("oculus", RepLevel.NEUTRAL);
    scy.setRelationship("nomads", RepLevel.NEUTRAL);
    scy.setRelationship("thulelegacy", RepLevel.NEUTRAL);
    scy.setRelationship("infected", RepLevel.NEUTRAL);

    SharedData.getData().getPersonBountyEventData().addParticipatingFaction("SCY");
  }
}

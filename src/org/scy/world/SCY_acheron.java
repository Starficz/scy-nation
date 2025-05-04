package org.scy.world;

import static org.scy.SCY_txt.txt;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.OrbitAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.RingBandAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SectorGeneratorPlugin;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
// import com.fs.starfarer.api.impl.campaign.procgen.NebulaEditor;
import com.fs.starfarer.api.impl.campaign.procgen.ProcgenUsedNames;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseThemeGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.themes.SalvageSpecialAssigner;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial;
import com.fs.starfarer.api.impl.campaign.submarkets.StoragePlugin;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldSource;
// import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.util.Misc;
import java.awt.Color;

public class SCY_acheron implements SectorGeneratorPlugin {

  @Override
  public void generate(SectorAPI sector) {

    StarSystemAPI system = sector.createStarSystem(txt("stm_system"));
    //        ProcgenUsedNames.notifyUsed("Acheron");
    system.setBackgroundTextureFilename("graphics/SCY/backgrounds/SCY_acheron.jpg");
    system.setOptionalUniqueId("SCY_acheron");
    ProcgenUsedNames.isUsed("Acheron");

    // create the star and generate the hyperspace anchor for this system
    PlanetAPI acheron_star =
        system.initStar(
            txt("stm_starA"), // unique id for this star
            "SCY_companionStar", // id in planets.json
            550f,
            800); // radius (in pixels at default zoom)
    system.setLightColor(
        new Color(255, 215, 200)); // light color in entire system, affects all entities

    //        system.getLocation().set(8000f, -19500f); //set the hyperspace location

    // set a fake hyperspace location due to the way proc gen avoid adding systems near handmade
    // ones
    system.getLocation().set(6000f, -15000f); // set the hyperspace location

    SectorEntityToken acheron_nebula =
        Misc.addNebulaFromPNG(
            "data/campaign/terrain/SCY_acheron.png",
            0,
            0, // center of nebula
            system, // location to add to
            "terrain",
            "nebula", // "nebula_blue", // texture to use, uses xxx_map for map
            4,
            4,
            StarAge.AVERAGE); // number of cells in texture

    SectorEntityToken barycenter =
        system.addCustomEntity("SCY_barycenter", txt("stm_center"), "SCY_barycenter", null);
    barycenter.setFixedLocation(0, 0);

    acheron_star.setCircularOrbit(barycenter, 105, 8000, 5000);

    // CLOUD RING
    RingBandAPI accretion =
        (RingBandAPI)
            system.addRingBand(
                acheron_star, "misc", "SCY_ringsC", 1024f, 0, Color.WHITE, 512f, 1750, 44f);
    accretion.setSpiral(true);
    accretion.setMinSpiralRadius(250);
    accretion.setSpiralFactor(1f);

    system.addRingBand(acheron_star, "misc", "SCY_ringsD", 1024f, 0, Color.WHITE, 1024f, 1100, 36f);
    system.addRingBand(acheron_star, "misc", "SCY_ringsD", 1024f, 0, Color.WHITE, 1024f, 1200, 41f);
    system.addRingBand(acheron_star, "misc", "SCY_ringsD", 1024f, 0, Color.WHITE, 1024f, 1300, 46f);
    system.addRingBand(acheron_star, "misc", "SCY_ringsD", 1024f, 0, Color.WHITE, 1024f, 1400, 52f);
    system.addRingBand(acheron_star, "misc", "SCY_ringsD", 1024f, 0, Color.WHITE, 1024f, 1500, 58f);
    system.addRingBand(acheron_star, "misc", "SCY_ringsD", 1024f, 0, Color.WHITE, 1024f, 1600, 64f);

    system.addRingBand(acheron_star, "misc", "SCY_ringsR", 1024f, 0, Color.WHITE, 1024f, 1100, 36f);
    system.addRingBand(acheron_star, "misc", "SCY_ringsR", 1024f, 0, Color.WHITE, 1024f, 1200, 41f);
    system.addRingBand(
        acheron_star,
        "misc",
        "SCY_ringsR",
        1024f,
        0,
        Color.WHITE,
        1024f,
        1300,
        46f,
        Terrain.RING,
        txt("stm_ringA1"));
    system.addRingBand(acheron_star, "misc", "SCY_ringsR", 1024f, 0, Color.WHITE, 1024f, 1400, 52f);
    system.addRingBand(acheron_star, "misc", "SCY_ringsR", 1024f, 0, Color.WHITE, 1024f, 1500, 58f);

    // 2000
    PlanetAPI ach1 =
        system.addPlanet(
            "ACH_a", acheron_star, txt("stm_planetA1"), "SCY_burntPlanet", 25, 80, 2000, 89);
    // JUMP POINT
    JumpPointAPI jumpPoint1 = Global.getFactory().createJumpPoint("ACH_jumpPointA", txt("stm_jpA"));
    OrbitAPI orbit = Global.getFactory().createCircularOrbit(acheron_star, 225 + 60, 2000, 89);
    jumpPoint1.setOrbit(orbit);
    jumpPoint1.setRelatedPlanet(ach1);
    jumpPoint1.setStandardWormholeToHyperspaceVisual();
    system.addEntity(jumpPoint1);
    // HULKS
    addDerelict(
        system,
        ach1,
        "lasher_CS",
        ShipRecoverySpecial.ShipCondition.BATTERED,
        270f,
        (Math.random() < 0.5));
    addDerelict(
        system,
        ach1,
        "kite_Standard",
        ShipRecoverySpecial.ShipCondition.AVERAGE,
        300f,
        (Math.random() < 0.5));
    addDerelict(
        system,
        ach1,
        "kite_Standard",
        ShipRecoverySpecial.ShipCondition.WRECKED,
        350f,
        (Math.random() < 0.5));
    addDerelict(
        system,
        ach1,
        "tarsus_d_Standard",
        ShipRecoverySpecial.ShipCondition.BATTERED,
        375f,
        (Math.random() < 0.5));
    addDerelict(
        system,
        ach1,
        "buffalo2_FS",
        ShipRecoverySpecial.ShipCondition.AVERAGE,
        400f,
        (Math.random() < 0.5));

    // 2500
    PlanetAPI ach2 =
        system.addPlanet("ACH_b", acheron_star, txt("stm_planetA2"), "lava", 225, 150, 2750, 114f);

    // 3250
    // ASTEROID BELT
    system.addAsteroidBelt(
        acheron_star, 250, 3750, 1536, 198, 262, Terrain.ASTEROID_BELT, txt("stm_ringA2"));
    system.addRingBand(
        acheron_star, "misc", "SCY_ringsD", 1024f, 0, Color.white, 1024f, 3500, 207f);
    system.addRingBand(
        acheron_star, "misc", "SCY_ringsD", 1024f, 0, Color.white, 1024f, 3600, 216f);
    system.addRingBand(
        acheron_star, "misc", "SCY_ringsD", 1024f, 0, Color.white, 1024f, 3700, 225f);
    system.addRingBand(
        acheron_star, "misc", "SCY_ringsD", 1024f, 0, Color.white, 1024f, 3800, 234f);
    system.addRingBand(
        acheron_star, "misc", "SCY_ringsD", 1024f, 0, Color.white, 1024f, 3900, 243f);
    system.addRingBand(
        acheron_star, "misc", "SCY_ringsD", 1024f, 0, Color.white, 1024f, 4000, 252f);

    system.addRingBand(
        acheron_star, "misc", "SCY_ringsR", 1024f, 0, Color.white, 1024f, 3600, 211f);
    system.addRingBand(
        acheron_star, "misc", "SCY_ringsR", 1024f, 0, Color.white, 1024f, 3700, 220f);
    system.addRingBand(
        acheron_star, "misc", "SCY_ringsR", 1024f, 0, Color.white, 1024f, 3800, 239f);
    system.addRingBand(
        acheron_star, "misc", "SCY_ringsR", 1024f, 0, Color.white, 1024f, 3900, 247f);
    // OLD RELAY
    SectorEntityToken relay =
        system.addCustomEntity(
            "ACH_abandonned_relay", // unique id
            txt("stm_relay"), // name - if null, defaultName from custom_entities.json will be used
            "comm_relay", // type of object, defined in custom_entities.json
            "neutral"); // faction
    relay.setCircularOrbit(acheron_star, 360 * (float) Math.random(), 3750, 228);

    // 4000
    PlanetAPI ach3 =
        system.addPlanet(
            "ACH_c", acheron_star, txt("stm_planetA3"), "SCY_acid", -35, 100, 5000, 585);
    // FREEPORT
    SectorEntityToken SCY_amityFreeport =
        system.addCustomEntity(
            "SCY_amityFreeport", txt("stm_stationA3"), "SCY_amity_type", "independent");
    SCY_amityFreeport.setCircularOrbitPointingDown(ach3, 360 * (float) Math.random(), 350, 20);
    SCY_amityFreeport.setCustomDescriptionId("SCY_amityFreeport");
    // FREEPORT MARKET

    PlanetAPI ach4 =
        system.addPlanet(
            "ACH_d",
            acheron_star,
            txt("stm_planetA4"),
            "SCY_redRock",
            360 * (float) Math.random(),
            120,
            6500,
            585);

    // 8000
    // ASTEROIDS
    system.addAsteroidBelt(
        acheron_star, 350, 8000, 1024, 1300, 1330, Terrain.ASTEROID_BELT, txt("stm_ringA3"));
    PlanetAPI ach5 =
        system.addPlanet(
            "ACH_e",
            acheron_star,
            txt("stm_planetA5"),
            "barren",
            360 * (float) Math.random(),
            70,
            8000,
            1314);
    // PIRATE STATION
    SectorEntityToken SCY_piratePort =
        system.addCustomEntity(
            "SCY_piratePort", txt("stm_stationA5"), "SCY_pirate_type", "pirates");
    SCY_piratePort.setCircularOrbitPointingDown(ach5, 62, 500, 25);
    SCY_piratePort.setCustomDescriptionId("SCY_piratePort");

    //        //PIRATE MARKET
    //        addMarketplace(
    //            "pirates",
    //            SCY_piratePort,
    //            null,
    //            "Chaos Hideout",
    //            2,
    //                new ArrayList(),
    //                new ArrayList<>(Arrays.asList(
    //                        Industries.POPULATION
    //                )),
    //                new ArrayList<>(Arrays.asList(
    //                        Submarkets.SUBMARKET_BLACK
    //                )),
    //            0.3f,
    //            true
    //        );

    // trojans
    SectorEntityToken acheronL4 =
        system.addTerrain(
            Terrain.ASTEROID_FIELD,
            new AsteroidFieldTerrainPlugin.AsteroidFieldParams(
                500f, 700f, 30, 40, 4f, 24f, txt("stm_asteroidAL4")));

    SectorEntityToken acheronL5 =
        system.addTerrain(
            Terrain.ASTEROID_FIELD,
            new AsteroidFieldTerrainPlugin.AsteroidFieldParams(
                500f, 700f, 30, 40, 4f, 24f, txt("stm_asteroidAL5")));

    acheronL4.setCircularOrbit(barycenter, 180 + 45 + 60, 18000, 5000);
    acheronL5.setCircularOrbit(barycenter, 180 + 45 - 60, 18000, 5000);

    /////////////////////////////////
    //                             //
    //         SECOND STAR         //
    //                             //
    /////////////////////////////////

    PlanetAPI star =
        system.addPlanet(
            "SCY_acheron", barycenter, txt("stm_starB"), "SCY_star", 180 + 105, 450f, 14000, 5000);

    system.addCorona(star, Terrain.CORONA, 1, 3, 0.25f, 0.5f);
    star.setCustomDescriptionId("SCY_star");

    system.setType(StarSystemGenerator.StarSystemType.BINARY_FAR);
    system.setSecondary(star);

    // 600
    // ANTIMATTER REFINERY
    SectorEntityToken SCY_refinery =
        system.addCustomEntity("SCY_refinery", txt("stm_stationBa"), "SCY_refinery_type", "SCY");
    SCY_refinery.setCircularOrbitPointingDown(star, 360 * (float) Math.random(), 900, 14);
    SCY_refinery.setCustomDescriptionId("SCY_refinery");

    //        addMarketplace(
    //                "SCY",
    //                SCY_refinery,
    //                null,
    //                "Scy Antimatter Collector",
    //                4,
    //                new ArrayList(Arrays.asList(
    //                        Conditions.VERY_HOT
    //                )),
    //                new ArrayList<>(Arrays.asList(
    //                        Industries.POPULATION,
    //                        Industries.SPACEPORT,
    //                        SCY_industries.ANTIMATTER,
    //                        Industries.ORBITALSTATION_MID,
    //                        Industries.PATROLHQ,
    //                        Industries.GROUNDDEFENSES
    //                )),
    //                new ArrayList<>(Arrays.asList(
    //                        Submarkets.SUBMARKET_OPEN,
    //                        Submarkets.SUBMARKET_BLACK,
    //                        Submarkets.SUBMARKET_STORAGE
    //                )),
    //                0.3f,
    //                true
    //        );

    // 1750
    // ASTEROID BELT
    system.addAsteroidBelt(
        star, 150, 1750, 512, 101, 131, Terrain.ASTEROID_BELT, txt("stm_ringB1"));

    system.addRingBand(star, "misc", "SCY_ringsD", 1024f, 0, Color.white, 512, 1600, 101f);
    system.addRingBand(star, "misc", "SCY_ringsR", 1024f, 0, Color.white, 512, 1700, 113f);
    system.addRingBand(star, "misc", "SCY_ringsR", 1024f, 0, Color.white, 512, 1800, 125f);
    system.addRingBand(star, "misc", "SCY_ringsD", 1024f, 0, Color.white, 512, 1900, 131f);

    // ORE PROCESSING
    SectorEntityToken SCY_processing =
        system.addCustomEntity(
            "SCY_processing", txt("stm_stationBb"), "SCY_processing_type", "SCY");
    SCY_processing.setCircularOrbitPointingDown(star, 360 * (float) Math.random(), 1900, 122);
    SCY_processing.setCustomDescriptionId("SCY_processing");
    //        addMarketplace(
    //                "SCY",
    //                SCY_processing,
    //                null,
    //                "Scy Ore Processing",
    //                4,
    //                new ArrayList(Arrays.asList(
    //                        Conditions.METEOR_IMPACTS,
    //                        Conditions.ORE_SPARSE,
    //                        Conditions.RARE_ORE_SPARSE
    //                )),
    //                new ArrayList<>(Arrays.asList(
    //                        Industries.POPULATION,
    //                        Industries.SPACEPORT,
    //                        Industries.MINING,
    //                        Industries.REFINING,
    //                        Industries.PATROLHQ,
    //                        Industries.GROUNDDEFENSES
    //                )),
    //                new ArrayList<>(Arrays.asList(
    //                        Submarkets.SUBMARKET_OPEN,
    //                        Submarkets.SUBMARKET_BLACK,
    //                        Submarkets.SUBMARKET_STORAGE
    //                )),
    //                0.3f,
    //                true);

    // 2500
    // ELYSEE
    PlanetAPI tar4 =
        system.addPlanet(
            "TAR_elysee", star, txt("stm_planetB1"), "SCY_homePlanet", 30, 150, 2500, 328);
    tar4.setInteractionImage("illustrations", "SCY_elysee");
    tar4.setCustomDescriptionId("SCY_homePlanet");
    // HEPHAISTOS
    SectorEntityToken SCY_station =
        system.addCustomEntity(
            "SCY_hephaistosStation", txt("stm_stationB1"), "SCY_hephaistosStation_type", "SCY");
    SCY_station.setCircularOrbitPointingDown(tar4, 360 * (float) Math.random(), 350, 50);
    SCY_station.setCustomDescriptionId("SCY_hephaistosStation");
    //        addMarketplace(
    //            "SCY",
    //            Global.getSector().getEntityById("TAR_elysee"),
    //            new ArrayList<>(Arrays.asList(
    //                    Global.getSector().getEntityById("TAR_elysee"),
    //                    SCY_station
    //            )),
    //            "Elysee",
    //            6,
    //            new ArrayList<>(Arrays.asList(
    //                    Conditions.LOW_GRAVITY,
    //                    Conditions.HABITABLE,
    //                    Conditions.THIN_ATMOSPHERE,
    //                    Conditions.IRRADIATED
    //            )),
    //            new ArrayList<>(Arrays.asList(
    //                    Industries.POPULATION,
    //                    Industries.MEGAPORT,
    //                    Industries.LIGHTINDUSTRY,
    //                    SCY_industries.GREENHOUSES,
    //                    Industries.ORBITALWORKS,
    //                    Industries.HIGHCOMMAND,
    //                    Industries.HEAVYBATTERIES,
    //                    Industries.STARFORTRESS_MID
    //            )),
    //            new ArrayList<>(Arrays.asList(
    //                    Submarkets.SUBMARKET_OPEN,
    //                    Submarkets.GENERIC_MILITARY,
    //                    Submarkets.SUBMARKET_BLACK,
    //                    Submarkets.SUBMARKET_STORAGE
    //            )),
    //            0.3f,
    //            true
    //        );

    // 3500
    PlanetAPI tar5 =
        system.addPlanet(
            "TAR_sisyphus",
            star,
            txt("stm_planetB2"),
            "toxic",
            360 * (float) Math.random(),
            75,
            3500,
            505);
    // moon
    PlanetAPI tar51 =
        system.addPlanet(
            "TAR_boulder",
            tar5,
            txt("stm_planetB2a"),
            "barren",
            360 * (float) Math.random(),
            30,
            350,
            8);

    // 4000
    PlanetAPI tar7 =
        system.addPlanet(
            "TAR_mine",
            star,
            txt("stm_planetB3"),
            "SCY_miningColony",
            360 * (float) Math.random(),
            90,
            4000,
            707);
    tar7.setInteractionImage("illustrations", "SCY_miningColony");
    tar7.setCustomDescriptionId("SCY_miningColony");
    // MINING COLONY OVERWATCH
    SectorEntityToken SCY_outpost =
        system.addCustomEntity(
            "SCY_overwatchStation", txt("stm_stationB3"), "SCY_overwatchStation_type", "SCY");
    SCY_outpost.setCircularOrbitPointingDown(tar7, 360 * (float) Math.random(), 200, 35);
    SCY_outpost.setCustomDescriptionId("SCY_overwatchStation");
    //                addMarketplace(
    //                        "SCY",
    //                        Global.getSector().getEntityById("TAR_mine"),
    //                        new ArrayList<>(Arrays.asList(
    //                                Global.getSector().getEntityById("TAR_mine"),
    //                                SCY_outpost
    //                        )),
    //                        "Scy Overwatch Station",
    //                        4,
    //                        new ArrayList<>(Arrays.asList(
    //                                Conditions.LOW_GRAVITY,
    //                                Conditions.ORE_RICH,
    //                                Conditions.RARE_ORE_ABUNDANT,
    //                                Conditions.NO_ATMOSPHERE
    //                        )),
    //                        new ArrayList<>(Arrays.asList(
    //                                Industries.POPULATION,
    //                                Industries.SPACEPORT,
    //                                Industries.MINING,
    //                                Industries.BATTLESTATION_MID,
    //                                Industries.HEAVYINDUSTRY,
    //                                Industries.HIGHCOMMAND,
    //                                Industries.HEAVYBATTERIES,
    //                                SCY_industries.INCOM
    //                        )),
    //                        new ArrayList<>(Arrays.asList(
    //                                Submarkets.SUBMARKET_OPEN,
    //                                Submarkets.GENERIC_MILITARY,
    //                                Submarkets.SUBMARKET_BLACK,
    //                                Submarkets.SUBMARKET_STORAGE
    //                        )),
    //                        0.3f,
    //                        true);

    // 6000
    PlanetAPI tar8 =
        system.addPlanet(
            "TAR_tantalus",
            star,
            txt("stm_planetB4"),
            "cryovolcanic",
            360 * (float) Math.random(),
            80,
            6000,
            929);
    // BIO CONDITIONING
    SectorEntityToken SCY_conditioning =
        system.addCustomEntity(
            "SCY_conditioning", txt("stm_stationB4"), "SCY_conditioning_type", "SCY");
    SCY_conditioning.setCircularOrbitPointingDown(tar8, 180, 200, 35);
    SCY_conditioning.setCustomDescriptionId("SCY_conditioning");
    //                addMarketplace(
    //                        "SCY",
    //                        SCY_conditioning,
    //                        null,
    //                        "Scy Volatiles Plant",
    //                        4,
    //                        new ArrayList<>(Arrays.asList(
    //                                Conditions.ORGANICS_COMMON,
    //                                Conditions.VOLATILES_ABUNDANT,
    //                                Conditions.DARK
    //                        )),
    //                        new ArrayList<>(Arrays.asList(
    //                                Industries.POPULATION,
    //                                Industries.SPACEPORT,
    //                                Industries.MINING,
    //                                Industries.WAYSTATION
    //                        )),
    //                        new ArrayList<>(Arrays.asList(
    //                                Submarkets.SUBMARKET_OPEN,
    //                                Submarkets.SUBMARKET_STORAGE
    //                        )),
    //                        0.3f,
    //                        true);

    // 7500
    PlanetAPI tar9 =
        system.addPlanet("TAR_ixion", star, txt("stm_planetB5"), "barren", 100, 60, 7500, 1299);
    PlanetAPI tar10 =
        system.addPlanet("TAR_tityos", star, txt("stm_planetB6"), "frozen", 280, 70, 7500, 1299);
    // ASTEROID BELT
    system.addAsteroidBelt(
        star, 100, 7500, 720, 1225, 1375, Terrain.ASTEROID_BELT, txt("stm_ringC"));
    system.addRingBand(star, "misc", "SCY_ringsR", 1024f, 0, Color.WHITE, 1024f, 7500, 1250f);
    system.addRingBand(star, "misc", "SCY_ringsD", 1024f, 0, Color.white, 1024f, 7500, 1350f);
    // YGGDRASILL WRECKAGE
    SectorEntityToken neutralStation =
        system.addCustomEntity(
            "SCY_yggdrasillWreckage",
            txt("stm_stationB7"),
            "SCY_yggdrasillWreckage_type",
            "neutral");
    neutralStation.setCircularOrbitPointingDown(star, 157, 7500, 1275);
    neutralStation.getMemory().set("$abandonedStation", true);
    neutralStation.setDiscoverable(true);
    neutralStation.setDiscoveryXP(2000f);
    neutralStation.setSensorProfile(0.3f);
    MarketAPI market =
        Global.getFactory().createMarket(txt("stm_stationB7"), neutralStation.getName(), 0);
    market.setPrimaryEntity(neutralStation);
    market.setFactionId(neutralStation.getFaction().getId());
    market.addCondition(Conditions.ABANDONED_STATION);
    market.addSubmarket(Submarkets.SUBMARKET_STORAGE);
    ((StoragePlugin) market.getSubmarket(Submarkets.SUBMARKET_STORAGE).getPlugin())
        .setPlayerPaidToUnlock(true);
    neutralStation.setMarket(market);
    neutralStation.setCustomDescriptionId("SCY_yggdrasillWreckage");
    neutralStation.setInteractionImage("illustrations", "abandoned_station");
    neutralStation
        .getMarket()
        .getSubmarket(Submarkets.SUBMARKET_STORAGE)
        .getCargo()
        .addCommodity("SCY_intelChip", 1);

    DebrisFieldParams params =
        new DebrisFieldParams(
            300f, // field radius - should not go above 1000 for performance reasons
            1f, // density, visual - affects number of debris pieces
            10000000f, // duration in days
            0f); // days the field will keep generating glowing pieces
    params.source = DebrisFieldSource.MIXED;
    params.baseSalvageXP = 250; // base XP for scavenging in field
    SectorEntityToken debrisWreckage =
        Misc.addDebrisField(system, params, StarSystemGenerator.random);
    debrisWreckage.setDiscoverable(true);
    debrisWreckage.setCircularOrbit(star, 157, 7500, 1275);
    debrisWreckage.setId("acheron_debrisWreckage");

    // JUMP POINT
    JumpPointAPI jumpPoint2 = Global.getFactory().createJumpPoint("ACH_jumpPointB", txt("stm_jpB"));
    OrbitAPI orbit2 =
        Global.getFactory().createCircularOrbit(star, (float) Math.random() * 360, 8000, 1500);
    jumpPoint2.setOrbit(orbit2);
    jumpPoint2.setRelatedPlanet(tar4);
    jumpPoint2.setStandardWormholeToHyperspaceVisual();
    system.addEntity(jumpPoint2);

    system.autogenerateHyperspaceJumpPoints(true, true, true);
    system.setEnteredByPlayer(true);
    Misc.setAllPlanetsSurveyed(system, true);
    for (MarketAPI sysMarket : Global.getSector().getEconomy().getMarkets(system)) {
      sysMarket.setSurveyLevel(MarketAPI.SurveyLevel.FULL); // could also be a station, not a planet
    }
  }

  /////////////////////////////////
  //                             //
  //       MARKET CREATION       //
  //                             //
  /////////////////////////////////

  protected void addDerelict(
      StarSystemAPI system,
      SectorEntityToken focus,
      String variantId,
      ShipRecoverySpecial.ShipCondition condition,
      float orbitRadius,
      boolean recoverable) {
    DerelictShipEntityPlugin.DerelictShipData params =
        new DerelictShipEntityPlugin.DerelictShipData(
            new ShipRecoverySpecial.PerShipData(variantId, condition), false);
    SectorEntityToken ship =
        BaseThemeGenerator.addSalvageEntity(system, Entities.WRECK, Factions.NEUTRAL, params);
    ship.setDiscoverable(true);

    float orbitDays = orbitRadius / (10f + (float) Math.random() * 5f);
    ship.setCircularOrbit(focus, (float) Math.random() * 360f, orbitRadius, orbitDays);

    if (recoverable) {
      SalvageSpecialAssigner.ShipRecoverySpecialCreator creator =
          new SalvageSpecialAssigner.ShipRecoverySpecialCreator(null, 0, 0, false, null, null);
      Misc.setSalvageSpecial(ship, creator.createSpecial(ship, null));
    }
  }
}

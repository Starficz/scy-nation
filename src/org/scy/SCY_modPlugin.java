package org.scy;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import org.scy.plugins.SCY_muzzleFlashesPlugin;
import org.scy.plugins.SCY_projectilesEffectPlugin;
import org.scy.world.MarketHelpers;
import org.scy.world.SCY_gen;
import exerelin.campaign.SectorManager;
import org.dark.shaders.light.LightData;
import org.dark.shaders.util.ShaderLib;
import org.dark.shaders.util.TextureData;
import org.magiclib.util.MagicCampaign;
import org.scy.weapons.ai.*;

// import org.scy.SCY_shipsStealth;

public class SCY_modPlugin extends BaseModPlugin {

    public static final String antiMissile_ID = "SCY_antiS";
    public static final String swarmer_ID = "SCY_swarmS";
    public static final String coasting_ID = "SCY_coastingS";
    public static final String laser_ID = "SCY_laserS";
    public static final String bomberTorpedo_ID = "SCY_bomberTorpedo";
    public static final String cluster_ID = "SCY_clusterS";
    public static final String rocket_ID = "SCY_rocketS";
    public static final String arc_ID = "SCY_arcS";
    public static final String phase_ID = "SCY_phasedS";

    public static final String MEMKEY_VERSION = "$scy_version";
    public static final String MEMKEY_INTIALIZED = "$scy_initialized";
    public static final boolean haveNexerelin =
            Global.getSettings().getModManager().isModEnabled("nexerelin");

    ////////////////////////////////////////
    //                                    //
    //      MISSILES AI OVERRIDES         //
    //                                    //
    ////////////////////////////////////////

    @Override
    public PluginPick<MissileAIPlugin> pickMissileAI(MissileAPI missile, ShipAPI launchingShip) {
        switch (missile.getProjectileSpecId()) {
            case rocket_ID:
                return new PluginPick<MissileAIPlugin>(
                        new SCY_rocketAI(missile, launchingShip), CampaignPlugin.PickPriority.MOD_SPECIFIC);
            case antiMissile_ID:
                return new PluginPick<MissileAIPlugin>(
                        new SCY_antiMissileAI(missile, launchingShip),
                        CampaignPlugin.PickPriority.MOD_SPECIFIC);
            case swarmer_ID:
                return new PluginPick<MissileAIPlugin>(
                        new SCY_swarmerAI(missile, launchingShip), CampaignPlugin.PickPriority.MOD_SPECIFIC);
            case coasting_ID:
                return new PluginPick<MissileAIPlugin>(
                        new SCY_coastingMissileAI(missile, launchingShip),
                        CampaignPlugin.PickPriority.MOD_SPECIFIC);
            case laser_ID:
                return new PluginPick<MissileAIPlugin>(
                        new SCY_laserTorpedoAI(missile, launchingShip),
                        CampaignPlugin.PickPriority.MOD_SPECIFIC);
            case bomberTorpedo_ID:
                return new PluginPick<MissileAIPlugin>(
                        new SCY_laserTorpedoAI(missile, launchingShip),
                        CampaignPlugin.PickPriority.MOD_SPECIFIC);
            case cluster_ID:
                return new PluginPick<MissileAIPlugin>(
                        new SCY_clusterTorpedoAI(missile, launchingShip),
                        CampaignPlugin.PickPriority.MOD_SPECIFIC);
            case arc_ID:
                return new PluginPick<MissileAIPlugin>(
                        new SCY_arcMissileAI(missile, launchingShip), CampaignPlugin.PickPriority.MOD_SPECIFIC);
            case phase_ID:
                return new PluginPick<MissileAIPlugin>(
                        new SCY_phaseTorpedoAI(missile, launchingShip),
                        CampaignPlugin.PickPriority.MOD_SPECIFIC);
            default:
        }
        return null;
    }

    ////////////////////////////////////////
    //                                    //
    //       ON APPLICATION LOAD          //
    //                                    //
    ////////////////////////////////////////

    @Override
    public void onApplicationLoad() {
        // Check ShaderLib for lights
        try {
            Global.getSettings().getScriptClassLoader().loadClass("org.dark.shaders.util.ShaderLib");
            ShaderLib.init();
            LightData.readLightDataCSV("data/config/modFiles/SCY_lights.csv");
            TextureData.readTextureDataCSV("data/config/modFiles/SCY_maps.csv");
        } catch (ClassNotFoundException ex) {
        }
        SCY_settingsData.loadHullmodData();
    }

    ////////////////////////////////////////
    //                                    //
    //        ON NEW GAME CREATION        //
    //                                    //
    ////////////////////////////////////////
    @Override
    public void onNewGame() {
        if (!haveNexerelin || SectorManager.getManager().isCorvusMode()) {
            new SCY_gen().generate(Global.getSector());
        }
    }

    @Override
    public void onNewGameAfterEconomyLoad() {
        // special admins
        MarketAPI market = Global.getSector().getEconomy().getMarket("TAR_elysee");
        if (market != null) {
            if (!market.getAdmin().getName().getLast().equals("Railey")) {
                PersonAPI person = Global.getFactory().createPerson();
                person.setFaction("SCY");
                person.setGender(FullName.Gender.FEMALE);
                person.setRankId(Ranks.FACTION_LEADER);
                person.setPostId(Ranks.POST_FACTION_LEADER);
                person.getName().setFirst("Amanda");
                person.getName().setLast("Railey");
                person.setPortraitSprite(Global.getSettings().getSpriteName("characters", "railey"));

                if (Global.getSettings().getModManager().isModEnabled("IndEvo")) {
                    person.getStats().setSkillLevel("indevo_industrial_planning", 1);
                    person.getStats().setSkillLevel("indevo_planetary_operations", 1);
                    person.getStats().setSkillLevel("indevo_fleet_logistics", 1);
                } else {
                    person.getStats().setSkillLevel(Skills.INDUSTRIAL_PLANNING, 1);
                }

                market.setAdmin(person);
                market.getCommDirectory().addPerson(person, 0);
                market.addPerson(person);
            }

            // move the home system to its proper location after the proc gen added its systems
            market.getStarSystem().getLocation().set(8000f, -19500f);
            MagicCampaign.hyperspaceCleanup(market.getStarSystem());
        }

        Global.getSector().getMemoryWithoutUpdate().set(MEMKEY_VERSION, 1.80);
    }

    ////////////////////////////////////////
    //                                    //
    //            ON GAME LOAD            //
    //                                    //
    ////////////////////////////////////////

    @Override
    public void onGameLoad(boolean newGame) {
        // register our listener that gets notified when battles complete
        Global.getSector().addTransientListener(new ReportPlayerEngagementCampaignEventListener());
        Global.getSector().removeScriptsOfClass(data.scripts.SCY_shipsStealth.class); // this was redundant I think
        SCY_muzzleFlashesPlugin.cleanSlate();
        SCY_projectilesEffectPlugin.cleanSlate();

        if (!haveNexerelin || SectorManager.getManager().isCorvusMode()) {
            if (!Global.getSector().getMemoryWithoutUpdate().contains(MEMKEY_INTIALIZED)) {
                addToOngoingGame();
                Global.getSector().getMemoryWithoutUpdate().set(MEMKEY_INTIALIZED, true);
            }
        }

        if (!Global.getSector().getMemoryWithoutUpdate().contains(MEMKEY_VERSION)
                || ((Double) Global.getSector().getMemoryWithoutUpdate().get(MEMKEY_VERSION)) < 1.80) {
            Global.getSector().getMemoryWithoutUpdate().set(MEMKEY_VERSION, 1.80);
        }
    }

    protected void addToOngoingGame() {
        if (!haveNexerelin || SectorManager.getManager().isCorvusMode()) {
            if (Global.getSector().getStarSystem("Acheron's Domain") != null || Global.getSector().getStarSystem("SCY_acheron") != null) return;

            new SCY_gen().generate(Global.getSector());

            MarketHelpers.generateMarketsFromEconJson("SCY_acheron");
            MarketHelpers.generateMarketsFromEconJson("SCY_askonia");
            MarketHelpers.generateMarketsFromEconJson("SCY_tyle");
        }
    }

    private static class ReportPlayerEngagementCampaignEventListener
            extends BaseCampaignEventListener {

        public ReportPlayerEngagementCampaignEventListener() {
            super(false);
        }

        @Override
        public void reportBattleOccurred(CampaignFleetAPI primaryWinner, BattleAPI battle) {
            clearState();
        }

        @Override
        public void reportBattleFinished(CampaignFleetAPI primaryWinner, BattleAPI battle) {
            clearState();
        }

        @Override
        public void reportPlayerEngagement(EngagementResultAPI result) {
            clearState();
        }

        private void clearState() {
            SCY_muzzleFlashesPlugin.cleanSlate();
            SCY_projectilesEffectPlugin.cleanSlate();
        }
    }
}

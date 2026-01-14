package data.missions.SCY_wargames;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public class MissionDefinition implements MissionDefinitionPlugin {

	public void defineMission(MissionDefinitionAPI api) {

		// Set up the fleets
		api.initFleet(FleetSide.PLAYER, "SNS", FleetGoal.ATTACK, false);
		api.initFleet(FleetSide.ENEMY, "SNS", FleetGoal.ATTACK, true);

		// Set a blurb for each fleet
		api.setFleetTagline(FleetSide.PLAYER, "Combined training fleet");
		api.setFleetTagline(FleetSide.ENEMY, "Elite defense fleet");
		
		// These show up as items in the bulleted list under 
		// "Tactical Objectives" on the mission detail screen
		api.addBriefingItem("The enemy has the same equipement.");
		api.addBriefingItem("Protect your support ships to stand a chance.");
		
		// Set up the player's fleet  
                
                
		api.addToFleet(FleetSide.PLAYER, "SCY_centaur_Standard", FleetMemberType.SHIP, "SNS Carcasses", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_alecto_Brawler", FleetMemberType.SHIP, "SNS Paté", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_tisiphone_Brawler", FleetMemberType.SHIP, "SNS Foie-gras", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_lealaps_Strike", FleetMemberType.SHIP, "SNS Vin", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_talos_Brawler", FleetMemberType.SHIP, "SNS Gauffre", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_megaera_Brawler", FleetMemberType.SHIP, "SNS Saucisson", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_stymphalianbird_Combat", FleetMemberType.SHIP, "SNS Free Buffet", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_argus_Fighter", FleetMemberType.SHIP, "SNS Café", false);

		api.addToFleet(FleetSide.PLAYER, "SCY_lamiaA_Brawler", FleetMemberType.SHIP, "SNS Chocolatine", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_lamia_Brawler", FleetMemberType.SHIP, "SNS Omelette", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_pyraemon_Brawler", FleetMemberType.SHIP, "SNS Confiture", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_geryon_Bomber", FleetMemberType.SHIP, "SNS Confit de canard", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_argus_Bomber", FleetMemberType.SHIP, "SNS Café", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_hydra_Brawler", FleetMemberType.SHIP, "SNS Fromage", false);
                
		api.addToFleet(FleetSide.PLAYER, "SCY_corocotta_Brawler", FleetMemberType.SHIP, "SNS Fondue", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_corocottaA_Elite", FleetMemberType.SHIP, "SNS Tarte", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_dracanae_Assault", FleetMemberType.SHIP, "SNS Reblochon", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_manticore_Fighter", FleetMemberType.SHIP, "SNS Aligot", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_manticore_Bomber", FleetMemberType.SHIP, "SNS Aligot", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_erymanthianboar_Close_Support", FleetMemberType.SHIP, "SNS Fouet Catalan", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_siren_Hunter", FleetMemberType.SHIP, "SNS Gratin Dauphinois", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_khalkotauroi_Close_Support", FleetMemberType.SHIP, "SNS Lasagnes", false);

		api.addToFleet(FleetSide.PLAYER, "SCY_keto_Bomber", FleetMemberType.SHIP, "SNS Croustade", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_keto_Fighter", FleetMemberType.SHIP, "SNS Croustade", false);
		api.addToFleet(FleetSide.PLAYER, "SCY_nemeanlion_Elite", FleetMemberType.SHIP, "SNS Crêpe", true);
		
		// Set up the enemy fleet
                
		api.addToFleet(FleetSide.ENEMY, "SCY_centaur_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_alecto_combat", FleetMemberType.SHIP, false);	
		api.addToFleet(FleetSide.ENEMY, "SCY_tisiphone_combat", FleetMemberType.SHIP, false);	
		api.addToFleet(FleetSide.ENEMY, "SCY_lealaps_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_talos_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_megaera_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_stymphalianbird_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_argus_combat", FleetMemberType.SHIP, false);

		api.addToFleet(FleetSide.ENEMY, "SCY_lamiaA_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_lamia_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_geryon_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_argus_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_telchine_standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_baliusF_auxiliary", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_baliusT_auxiliary", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_hydra_combat", FleetMemberType.SHIP, false);
                        
                
		api.addToFleet(FleetSide.ENEMY, "SCY_corocotta_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_corocottaA_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_dracanae_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_manticore_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_erymanthianboar_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_siren_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_khalkotauroi_combat", FleetMemberType.SHIP, false);
                
                
		api.addToFleet(FleetSide.ENEMY, "SCY_xanthus_standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_keto_combat", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "SCY_nemeanlion_combat", FleetMemberType.SHIP, true);
		
		
		// Set up the map.
		float width = 20000f;
		float height = 12000f;
		api.initMap((float)-width/2f, (float)width/2f, (float)-height/2f, (float)height/2f);
		
		float minX = -width/2;
		float minY = -height/2;
		
		// All the addXXX methods take a pair of coordinates followed by data for
		// whatever object is being added.
		
		// Add two big nebula clouds
		api.addNebula(minX + width * 0.66f, minY + height * 0.5f, 2000);
		api.addNebula(minX + width * 0.25f, minY + height * 0.6f, 1000);
		api.addNebula(minX + width * 0.25f, minY + height * 0.4f, 1000);
		
		// And a few random ones to spice up the playing field.
		// A similar approach can be used to randomize everything
		// else, including fleet composition.
		for (int i = 0; i < 5; i++) {
			float x = (float) Math.random() * width - width/2;
			float y = (float) Math.random() * height - height/2;
			float radius = 100f + (float) Math.random() * 400f; 
			api.addNebula(x, y, radius);
		}
		
		// Add objectives. These can be captured by each side
		// and provide stat bonuses and extra command points to
		// bring in reinforcements.
		// Reinforcements only matter for large fleets - in this
		// case, assuming a 100 command point battle size,
		// both fleets will be able to deploy fully right away.
		api.addObjective(minX + width * 0.25f, minY + height * 0.5f, 
						 "sensor_array", BattleObjectiveAPI.Importance.NORMAL);
		api.addObjective(minX + width * 0.75f, minY + height * 0.5f,
						 "comm_relay", BattleObjectiveAPI.Importance.NORMAL);
		api.addObjective(minX + width * 0.33f, minY + height * 0.25f, 
						 "nav_buoy", BattleObjectiveAPI.Importance.NORMAL);
		api.addObjective(minX + width * 0.66f, minY + height * 0.75f, 
						 "nav_buoy", BattleObjectiveAPI.Importance.NORMAL);
		

		api.addAsteroidField(-(minY + height), minY + height, -90, 500f,
								150f, 200f, 100);

		api.addPlanet(minX + width * 0.8f, minY + height * 0.8f, 300f, "SCY_homePlanet", 300f);
	}

}







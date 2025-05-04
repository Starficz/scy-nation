/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.scy.world;

import static org.scy.SCY_txt.txt;

import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SectorGeneratorPlugin;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import org.lazywizard.lazylib.MathUtils;

/**
 * @author Tartiflette
 */
public class SCY_outposts implements SectorGeneratorPlugin {
  @Override
  public void generate(SectorAPI sector) {
    StarSystemAPI system = sector.getStarSystem("tyle");
    PlanetAPI planet = null;
    for (PlanetAPI p : system.getPlanets()) {
      if (p.getId().equals("antillia_b")) {
        planet = p;
      }
    }
    SectorEntityToken outpostA =
        system.addCustomEntity("SCY_tyleOutpost", txt("stm_tyle"), "SCY_outpost", "SCY");
    outpostA.setCircularOrbitPointingDown(
        planet, MathUtils.getRandomNumberInRange(0, 360), 500, 60);

    system = sector.getStarSystem("askonia");
    SectorEntityToken outpostB =
        system.addCustomEntity("SCY_askoniaOutpost", txt("stm_askonia"), "SCY_outpost", "SCY");
    outpostB.setCircularOrbitPointingDown(system.getStar(), 230, 11000, 600);
  }
}

## Version 1.10.0
- Balance Changes
  - Scyan Engineering Hullmod (All Ships)
    + Overhauled Tooltip, New art by Quacken!
    - Active Vent Mult x3.5 -> x3
    + Active Vent Percent per Cap 1% -> 5%/3%/2%/1%
    + Engine Jumpstarter has been moved from a System to a Subsystem, and given to all ships
    + Stationary Sensor profile/Maintenance bonuses now apply when moving slowly
    
  - Ship Balancing

  Frigates have always been issue for balancing in Starsector, where SCY's limited shield arcs hurt them the most.
  The original change I made to active vent rate per cap was also biased to larger ships with more caps, 
  so to make the lineup more viable, a smattering of various buffs have been applied.
  - All Frigates
    + 90 degree shield arc -> 100 degree shield arc
	+ Increased Flux Capacity by x1.25
    - All Shield Efficiency worse by 0.2 flux/damage (similar effective Shield HP, but deeper flux for weapons)
  - Alecto
    + 40 OP -> 44 OP
  - Tisiphone
    + 6 DP -> 5 DP
    + 38 OP -> 40 OP
  - Laelaps
    + Given Missile Autoforge System
    + 40 OP -> 42 OP
  - Talos 
    - 5 DP -> 6 DP
    - 45 OP -> 48 OP
  - Megaera
    - 5 DP -> 6 DP

  Destroyers, similar to frigates have also always been issue for balancing. With Escort Package, things are much better
  but what its trying to do doesn't mesh with SCY's doctrine very well. As with the Frigates, a smattering of various 
  buffs have been applied to keep them viable into the endgame.
  - All Destroyers
	+ 90 degree shield arc -> 100 degree shield arc
    + Increased Flux Capacity by x1.2
    - All Shield Efficiency worse by 0.1 flux/damage (slighly higher effective Shield HP, and deeper flux for weapons)
  - Lamia / Lamia (Armored) / Hydra
    + Increased OP by 5
  - Pyraemon
    + 60 OP -> 75 OP
    + 200 Dissipation -> 250 Dissipation
  
  - Cruisers
  - Corocotta (Armored)
    + Given the Twin Shield System (Pending testing if this is too strong)
  - Khalkotauroi
    + 100 OP -> 110 OP
  
  - Systems
  - Stasis Shield
  	+ 4 seconds active -> 5 seconds active
    + Much improved AI on when to smartly use it
  
  - Weapons
  - Nano-needle Minigun mk.1
    + Spin-up time 0.5 seconds -> 0.2 seconds (to be better at being PD)
  - Phased Missile Launcher
    + Decreased Unphase range from 800 to 400
	
- AI
  - Venting AI has been overhauled, resulting in smarter, more aggressive active vents

- Bugfixes/Misc
  - Fixed Explosions sometimes damaging modular armor while shields were up
  - Fixed the front armor plate of the Corocotta (Armored) chain exploding the side armor on destruction
  - Fixed armor plates not breaking up into many pieces upon destruction
  - Fixed SCY fighters accidentally having x2 shield HP (this has been in ever since v1.8.0 lol)
  - Fixed Laser Torpedoes not displaying actual damage numbers
  - Fixed Nemean Lion Weapon Lock flux issues by moving away from Flux Mults (fixes VIC auto-replicators)
  - Improved Paperdoll Health color to be more accurate
  - Moved Scy Ship to their own Simulator tab
## Version 1.9.0
- Integrated MagicPaintjob support for the Bluesky Skin
- 0.98a release

## Version 1.8.4
- [Buff] Cluster torpedo full release of bomblets 1.5s -> 0.5s 
- Fixed NPE Crash on Talos and Stymphalian Bird when shield shunted
- Rewrote Stymphalian Bird System to work with extra charges from any source
- Fix Coasting missile Volley Limit

## Version 1.8.3
- Fixed NPE Crash to desktop

## Version 1.8.2
- Ship Balance Changes
	- Nemean Lion
		- 42 DP -> 45 DP
	- Keto 
		+ 190 OP -> 210 OP

	- Logistics ships buffed to more reasonable alternatives vs vanilla
	- Balius (F) 
		+ 350 Cargo -> 400 Cargo
		+ 5 Supplies/month -> 4 Supplies per month
	- Balius (T) 
		+ 600 Fuel -> 650 Fuel
		+ 5 Supplies/month -> 4 Supplies per month
		- 50 Cargo -> 30 Cargo
	- Xanthus 
		+ 20 DP -> 10 DP (Supplies/month were always 10)
		+ 1200 Cargo -> 1600 Cargo
		+ 7 Fuel/LY -> 4 Fuel/LY
		- 700 Fuel -> 300 Fuel

- Weapon Balance Changes
	- Nano-needle Minigun mk.1
		+ Gained PD + PD_ALSO tags by default
	- Arc Missile Rack (small)
		+ Range 1000 -> 1500
		- Refire delay 10s -> 15s
	- Arc Missile Pod (medium)
		+ Reload rate 15s/missile -> 10s/missile

- Misc updates / Fixes
	- Faction doctrine nerfed from its 15 points to a vanilla spread of 7
	- Default aggression moved from Steady to Aggressive
	- encounter tracks switched from Tri-Tachyon to SCY market
	- AI core turn-in rewards slightly nerfed

## Version 1.8.1
- Thanks to Himemi for the following 2:
	- Updated Scy maps 
	- Added new material and surface maps
- Bugfixes
	- Removed KoL dependency
	- Made Singularity Torp way more rare
	- Fixed Armor paperdoll scaling issue
	
## Version 1.8.0
- Scyan Engineering hullmod
	- Updated Text and Effect
	- Now grants double flux capactity from all sources
	- Each cap now also gives 1% higher active vent rate
	- Scyan ships have much smarter and aggressive active vent AI

- Modular Armor
	- Armor module paperdoll HUD in combat
	- Modules now provide true splash damage protection vs explosions
	- Modules now are affected by all vanilla hullmods and Dmods
	- Hullmod now displays all armor/hull changes to modular armor
	- No longer bugs and displays a blank info tootip

- Variants updated
	- Deprecated many old variants
	- New variants should be much more even with vanilla in campaign fights

- Misc updates / Fixes
	- Nemean Lion is smarter with its system AI
	- Keto main gun animation and sound fixed
	- Safeties Switch System AI improved
	- Many weapons now have better Autofit/AI tags
	- Updated select weapons to fire through missiles/fighters when appropriate
	- Allowed markets to generate on loading a save without faction generated
	- Amity Freeport antique ship dealer is better


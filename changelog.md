## Version 1.10.0
### Ship Balance Changes
In a past patch I introduced the bonus where caps gave 1% Active Vent Mult per cap, this however mad it such that the 
larger ship class' being able to mount more caps were able to disproportionally gain from this system. 
As such, the base vent mult for SCY ships has been reduced, with vents now applying different percentages on each size class.

In addition, Engine Jumpstarter has always been a nice to have, but not a true "ability". I have moved them to be a 
subsystem that all Scyan ships have, allowing for more interesting abilities on combat ships and giving the entire faction some flavor.
- Scyan Engineering Hullmod (All Ships)
  + Overhauled Tooltip, New art by Quacken!
  - x3.5 Active Vent Mult -> x3
  + 1% Active Vent Percent per Cap -> 5%/3%/2%/1%
  + Engine Jumpstarter has been moved from a System to a Subsystem, and given to all ships
  + Stationary Sensor profile/Maintenance bonuses now apply when moving slowly

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
  
- System Balancing
- Stasis Shield
  + 4 seconds active -> 5 seconds active
  + Much improved AI on when to smartly use it
  

### Weapon Balance Changes
    
Nano-needle's have always been very special weapons, as they are ballistic beams. This resulted in many different workarounds for their operation, 
First of all, they have a 0 cooldown time, meaning they instantly despinned, even when the spin-up time was quite significant.
Second of which is that the "Energy Hardflux" portion of their damage was from the result of spawning 10 damage, 
KE/HE "splinter" projectiles randomly around where the beam hit (note that this has abysmal hit strength). This resulted in ~66 hardflux dps for the mk.1, 
~125 hardflux dps for the mk.2, and ~225 hardflux dps for the mk.3 (also note that this isn't what the tooltips say at all).

These issues resulted in me overhauling and rewriting this entire weapon line, fixing them. Now they have the following stats:

- All Nano-needle's
  + No longer instantly despin, but they still do stop firing when not at max spin. (adds some leyway when retargeting)
  + No Energy Hardflux DPS is another beam now, hit strength is equivalent to normal beams with the same DPS.

- Nano-needle Minigun mk.1
  + 0.5 seconds spin-up time -> 0.4 seconds spin-up time (to be better at being PD)
  + 180 Frag DPS + 60 Energy DPS at 105 flux/s 

- Nano-needle Minigun mk.2 
  + 700 Range -> 750 Range
  + 450 Frag DPS + 150 Energy DPS at 265 flux/s

- Nano-needle Minigun mk.3
  + 900 Range -> 950 Range
  + 900 Frag DPS + 300 Energy DPS at 525 flux/s

Upon testing, the Scatter Beam was severely underperforming compared to the new buffed Phase Lance.
These change may seem drastic and overpowered, but the inherent limitations of the weapon makes it such that anything less would be drastically worse.
- Scatter Beam
  + 1200 Burst Damage -> 1750 Burst Damage
  + 240 DPS -> 350 DPS
  + 240 flux/s -> 210 flux/s (1.0 flux/damage -> 0.6 flux/damage)

The Orion has always been strong, but with smodded ex-mags being a thing: 1000 range, 1000 damage, perfect accuracy, 375 dps, 1.0 flux/damage KE is too strong.
- Orion Artillery
  - 1.0 flux/damage -> 1.2 flux/damage

With the recent buff to the High Intensity Laser, the Super-Charged Pulse Beam was looking to be entirely outclassed. 
Being a burst weapon however, meant I had to be very careful to not make it stronger than the Tachyon Lance.
- Super-Charged Pulse Beam
  + 4 Max Charges -> 5 Max Charges
  + 250 Sustained DPS -> 300 Sustained DPS
  + 1.4 flux/damage -> 1.35 flux/damage

Considering most PD weapons top out at ~800 range, the Phased Missile Launcher never was very effective, 
being quite slow after its unphase and with relatively low HP. As such, its unphase range has been cut in half (with corresponding velocity damping so it still acts as a mine).
- Phased Missile Launcher
  + Decreased Unphase range from 800 to 400

As an energy sidegrade to the locust that costs flux to fire, the fact that often it performed worse than the Locust was quite disappointing. 
To make it more consistent vs targets you would want energy damage in the first place, the missile hitpoints have been increased.
- Heavy Modular Swarmer
  + 30 Hitpoints per Missile -> 45 Hitpoints per Missile

### AI
- Venting AI has been overhauled, resulting in smarter, more aggressive active vents

###  Bugfixes/Misc
- Fixed Explosions sometimes damaging modular armor while shields were up
- Fixed the front armor plate of the Corocotta (Armored) chain exploding the side armor on destruction
- Fixed armor plates not breaking up into many pieces upon destruction
- Fixed SCY fighters accidentally having x2 shield HP (this has been in ever since v1.8.0 lol)
- Fixed Laser Torpedoes not displaying actual damage numbers
- Fixed Nemean Lion Weapon Lock flux issues by moving away from Flux Mults (fixes VIC auto-replicators)
- Improved Paperdoll Health color to be more accurate
- Moved Scy Ship to their own Simulator tab
- Updated Nex Start Variants

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


package org.scy.shipsystems.ai

import com.fs.starfarer.api.combat.*
import org.lazywizard.lazylib.combat.AIUtils
import org.lwjgl.util.vector.Vector2f

class SCY_stasisShieldAI: ShipSystemAIScript {
    lateinit var ship: ShipAPI
    override fun init(
        ship: ShipAPI?,
        system: ShipSystemAPI?,
        flags: ShipwideAIFlags?,
        engine: CombatEngineAPI?
    ) {
        this.ship = ship!!
    }

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
        val flux = ship.customData["baseFluxGained"] as? Float ?: return
        if (ship.system.fluxPerUse < flux / 2 && AIUtils.canUseSystemThisFrame(ship)) ship.useSystem()
    }
}
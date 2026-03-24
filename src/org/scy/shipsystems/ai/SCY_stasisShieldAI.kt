package org.scy.shipsystems.ai

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import org.lazywizard.lazylib.combat.AIUtils
import org.lwjgl.util.vector.Vector2f
import org.scy.plugins.DamageTimeline

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
        val baseDamage = ship.customData["SCY_baseDamage"] as? DamageTimeline ?: return

        val currentTime = Global.getCombatEngine().getTotalElapsedTime(false)
        if (ship.system.fluxPerUse < baseDamage.fluxToShield(currentTime, ship.system.chargeActiveDur, ship) / 2
            && AIUtils.canUseSystemThisFrame(ship)) ship.useSystem()
    }
}
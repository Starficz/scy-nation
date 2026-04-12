package org.scy.shipsystems.ai

import com.fs.starfarer.api.combat.*
import org.lazywizard.lazylib.combat.AIUtils
import org.lwjgl.util.vector.Vector2f
import org.starficz.combataitweaks.CombatAIv2

class SecondaryThrustersAI : ShipSystemAIScript {
    lateinit var ship: ShipAPI
    override fun init(
        ship: ShipAPI,
        system: ShipSystemAPI?,
        flags: ShipwideAIFlags?,
        engine: CombatEngineAPI?
    ) {
        this.ship = ship
    }

    override fun advance(
        amount: Float,
        missileDangerDir: Vector2f?,
        collisionDangerDir: Vector2f?,
        target: ShipAPI?
    ) {
        if (!AIUtils.canUseSystemThisFrame(ship)) return
        val useSys = ship.customData[CombatAIv2.USE_MOVEMENT_SYSTEM_TO_BACKOFF] as Boolean? ?: false

        if (useSys) {
            ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)
        }
    }
}
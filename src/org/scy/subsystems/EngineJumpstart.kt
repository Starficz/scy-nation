package org.scy.subsystems

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.ext.plus
import org.lwjgl.util.vector.Vector2f
import org.magiclib.subsystems.MagicSubsystem
import java.awt.Color
import kotlin.random.Random

class EngineJumpstart(ship: ShipAPI?) : MagicSubsystem(ship) {
    override fun getDisplayText(): String { return "Engine Jumpstart" }
    override fun getBaseActiveDuration(): Float { return 0.1f }
    override fun getBaseCooldownDuration(): Float { return 20f }
    override fun getFluxCostPercentOnActivation(): Float { return 0.1f }

    override fun shouldActivateAI(amount: Float): Boolean {
        return ship.engineController.shipEngines.all { it.isDisabled || it.isSystemActivated }
    }

    override fun canActivate(): Boolean {
        //return ship.engineController.shipEngines.filterNot { it.isSystemActivated }.any { it.isDisabled }
        return true
    }

    override fun onActivate() {
        val engine = Global.getCombatEngine()
        Global.getSoundPlayer().playSound("SCY_ricochet", 0.7f, 1f, ship.location, ship.velocity)
        for (shipEngine in ship.engineController.shipEngines) {
            shipEngine.hitpoints = shipEngine.maxHitpoints
            shipEngine.repair()

            for (i in 0..9) {
                // -20 to 20 backwards
                val vel = ship.velocity + MathUtils.getRandomPointInCone(
                    Vector2f(), 50f, ship.facing + 160, ship.facing + 200)
                val grey = Random.nextDouble(0.1, 0.3).toFloat()
                engine.addSmokeParticle(
                    MathUtils.getRandomPointInCircle(shipEngine.location, 10f),
                    vel,
                    20 + 5 * Random.nextFloat(),
                    0.1f + 0.2f * Random.nextFloat(),
                    0.5f + 1 * Random.nextFloat(),
                    Color(grey, grey, grey)
                )
            }
        }
    }
}
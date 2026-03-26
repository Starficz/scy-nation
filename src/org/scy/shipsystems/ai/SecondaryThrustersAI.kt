package org.scy.shipsystems.ai

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.AIUtils
import org.lwjgl.util.vector.Vector2f
import org.scy.plugins.DamageTimeline

class SecondaryThrustersAI : ShipSystemAIScript {
    lateinit var ship: ShipAPI
    val interval = IntervalUtil(0.05f, 0.1f)
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
        interval.advance(amount)
        if (!interval.intervalElapsed()) return
        val baseDamage = ship.customData["SCY_baseDamage"] as? DamageTimeline
        val useSysToBackoff = ship.customData["SCY_useMobilitySystemToBackoff"] as Boolean? ?: false

        val currentTime = Global.getCombatEngine().getTotalElapsedTime(false)
        val optimalRange = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.MANEUVER_RANGE_FROM_TARGET) as? Float ?: 0f
        val getCloser = ship.shipTarget?.let{ MathUtils.getDistance(it, ship) > optimalRange } ?: true

        if ((baseDamage == null || baseDamage.fluxToShield(currentTime, 3f, ship) < 1000f) && getCloser) {
            ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)
        }
        if (useSysToBackoff) {
            ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)
        }
    }
}
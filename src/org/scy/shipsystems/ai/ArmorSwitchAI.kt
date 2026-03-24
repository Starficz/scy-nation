package org.scy.shipsystems.ai

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import org.scy.plugins.DamageTimeline
import org.scy.turnTowards

class ArmorSwitchAI : ShipSystemAIScript {
    lateinit var ship: ShipAPI
    val interval = IntervalUtil(0.05f, 0.1f)
    var getCloser = true
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
        interval.advance(amount)
        if (!interval.intervalElapsed()) return

        ship.shipAI?.config?.turnToFaceWithUndamagedArmor = false;

        val baseDamage = ship.customData["SCY_baseDamage"] as? DamageTimeline
        val useSysToBackoff = ship.customData["SCY_useMobilitySystemToBackoff"] as Boolean? ?: false
        val currentTime = Global.getCombatEngine().getTotalElapsedTime(false)
        val optimalRange = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.MANEUVER_RANGE_FROM_TARGET) as? Float ?: 0f

        if (interval.intervalElapsed()){
            getCloser = ship.shipTarget?.let{ MathUtils.getDistance(it, ship) > optimalRange } ?: true
        }

        var armorUp = false
        if ((baseDamage == null || baseDamage.fluxToShield(currentTime, 3f, ship) < 100f) && getCloser) {
            armorUp = true
            //Global.getCombatEngine().addSmoothParticle(ship.location, Misc.ZERO, 50f, 50f, 0.1f, Color.green)
        }
        if (useSysToBackoff) {
            armorUp = true
            // armor tank
            if (ship.shipTarget != null){
                val angleToThreat = VectorUtils.getAngle(ship.location, ship.shipTarget.location)
                val targetFacing = MathUtils.clampAngle(angleToThreat + 45f)

                ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT)
                ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT)

                turnTowards(ship, targetFacing)
            }

            //Global.getCombatEngine().addSmoothParticle(ship.location, Misc.ZERO, 50f, 50f, 0.1f, Color.red)
        }

        if (!ship.system.isCoolingDown && (armorUp xor ship.system.isOn))
            ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)

    }
}
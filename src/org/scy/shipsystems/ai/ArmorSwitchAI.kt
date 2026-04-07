package org.scy.shipsystems.ai

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.util.IntervalUtil
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import org.scy.ReflectionUtils.set
import org.starficz.combatai.predictor.DamageTimeline
import org.scy.turnTowards
import org.starficz.combatai.CombatAIv2

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
        ship.shipAI?.config?.turnToFaceWithUndamagedArmor = false
        ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.PREFER_RIGHT_BROADSIDE, 0.1f)
        ship.aiFlags.unsetFlag(ShipwideAIFlags.AIFlags.PREFER_LEFT_BROADSIDE)

        if (ship.shipTarget != null) {
            val angleToThreat = VectorUtils.getAngle(ship.location, ship.shipTarget.location)
            val targetFacing = MathUtils.clampAngle(angleToThreat + 40f)

            turnTowards(ship, targetFacing)
        }

        return
        interval.advance(amount)
        if (!interval.intervalElapsed()) return

        ship.shipAI?.config?.turnToFaceWithUndamagedArmor = false

        val baseDamage = ship.customData[CombatAIv2.BASE_DAMAGE] as? DamageTimeline
        val useSysToBackoff = ship.customData[CombatAIv2.USE_MOVEMENT_SYSTEM_TO_BACKOFF] as Boolean? ?: false
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

                turnTowards(ship, targetFacing)
            }

            //Global.getCombatEngine().addSmoothParticle(ship.location, Misc.ZERO, 50f, 50f, 0.1f, Color.red)
        }

        if (!ship.system.isCoolingDown && (armorUp xor ship.system.isOn))
            ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)

    }
}
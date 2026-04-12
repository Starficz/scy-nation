package org.scy.shipsystems.ai

import com.fs.starfarer.api.combat.*
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import org.scy.turnTowards
import org.starficz.combataitweaks.CombatAIv2

class ArmorSwitchAI : ShipSystemAIScript {
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
        ship.shipAI?.config?.turnToFaceWithUndamagedArmor = false
        ship.aiFlags.setFlag(ShipwideAIFlags.AIFlags.PREFER_RIGHT_BROADSIDE, 0.1f)
        ship.aiFlags.unsetFlag(ShipwideAIFlags.AIFlags.PREFER_LEFT_BROADSIDE)

        val useSysToBackoff = ship.customData[CombatAIv2.USE_MOVEMENT_SYSTEM_TO_BACKOFF] as Boolean? ?: false
        val useSysToAdvance = ship.customData[CombatAIv2.USE_MOVEMENT_SYSTEM_TO_ADVANCE] as Boolean? ?: false
        val noEnemies = ship.customData[CombatAIv2.BEHAVIOR_STATE] == "VANILLA_FALLBACK"

        val armorTanking = ship.shipTarget != null && !noEnemies && ((ship.shield?.activeArc ?: 0f) < 30f
                || ship.aiFlags.hasFlag(ShipwideAIFlags.AIFlags.DO_NOT_USE_SHIELDS))

        val useSystem = noEnemies || armorTanking || useSysToBackoff || useSysToAdvance
        if (!ship.system.isCoolingDown && (useSystem xor ship.system.isOn))
            ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)

        if (armorTanking || useSysToBackoff) {
            val angleToThreat = VectorUtils.getAngle(ship.location, ship.shipTarget.location)
            val targetFacing = MathUtils.clampAngle(angleToThreat + 45f)

            turnTowards(ship, targetFacing)
        }

        if (ship.shield != null && missileDangerDir == null && (armorTanking || useSysToBackoff)){
            val shieldFacing = ship.facing + (ship.shield.activeArc/2) - 15f
            val shieldOverride = MathUtils.getPointOnCircumference(ship.shieldCenterEvenIfNoShield, 100f, shieldFacing)
            ship.setShieldTargetOverride(shieldOverride.x, shieldOverride.y)
        }
    }
}
package org.scy.shipsystems

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.scy.ReflectionUtils
import org.scy.ReflectionUtils.get
import org.scy.ReflectionUtils.getFieldsMatching
import org.scy.ReflectionUtils.invoke
import org.scy.ReflectionUtils.set
import kotlin.math.roundToInt

class ArmorSwitchStats : BaseShipSystemScript() {
    private val baseSlots = mutableMapOf<String, Pair<Float, Float>>()

    private data class TargetState(val angle: Float, val arc: Float)

    private val targetStates = mapOf(
        "BOT_LARGE"     to TargetState(angle = 190f, arc = 40f),
        "TOP_LARGE"     to TargetState(angle = 357.5f, arc = 15f),
        "TOP_MID"       to TargetState(angle = 357.5f, arc = 25f),
        "TOP_BOT_SMALL" to TargetState(angle = 352.5f, arc = 55f),
        "TOP_TOP_SMALL" to TargetState(angle = 355f, arc = 90f)
    )

    private var fieldsInitialized = false
    private var aimTrackerAngleField: ReflectionUtils.ReflectedField? = null
    private var aimTrackerArcField: ReflectionUtils.ReflectedField? = null

    private var originalShieldFacing = 0f
    private var SYSTEM_STATUS_KEY_1: Any = Any()
    private var SYSTEM_STATUS_KEY_2: Any = Any()

    private fun initReflection(ship: ShipAPI) {
        if (fieldsInitialized) return
        fieldsInitialized = true

        val w = ship.allWeapons.find {
            it.slot.arc != it.slot.angle && it.slot.arc != 15f && it.slot.angle != 15f
        } ?: ship.allWeapons.firstOrNull() ?: return

        val tracker = w.invoke("getAimTracker") ?: return
        val floatFields = tracker.getFieldsMatching(type = Float::class.javaPrimitiveType)

        aimTrackerArcField = floatFields.find { (tracker.get(it) as? Float) == w.slot.arc }

        val originalAngle = w.currAngle
        w.currAngle = originalAngle + 7f
        aimTrackerAngleField = floatFields.find { (tracker.get(it) as? Float) == w.slot.angle }
        w.currAngle = originalAngle
    }

    override fun apply(
        stats: MutableShipStatsAPI?,
        id: String?,
        state: ShipSystemStatsScript.State?,
        effectLevel: Float
    ) {
        val ship = stats?.entity as? ShipAPI ?: return

        val speedPercentage = 40f * effectLevel
        val lateralAccelPercentage = 150f * effectLevel
        val rotAccelPercentage = -50f * effectLevel

        stats.maxSpeed.modifyFlat(id, speedPercentage)
        stats.acceleration.modifyPercent(id, lateralAccelPercentage)
        stats.deceleration.modifyPercent(id, lateralAccelPercentage)
        stats.turnAcceleration.modifyPercent(id, rotAccelPercentage)
        stats.maxTurnRate.modifyPercent(id, rotAccelPercentage)

        if (ship.shield != null && effectLevel > 0f) {
            ship.shield.activeArc
            ship.shield.forceFacing(Misc.interpolate(originalShieldFacing, originalShieldFacing + MathUtils.getShortestRotation(originalShieldFacing, ship.facing+25f) , effectLevel))
        } else {
            originalShieldFacing = ship.shield?.facing ?: 0f
        }

        val engine = Global.getCombatEngine()
        if (engine.playerShip === ship) {
            if (effectLevel > 0) {
                val modularIcon = Global.getSettings().getSpriteName("icons", "scy_modules")
                engine.maintainStatusForPlayerShip(SYSTEM_STATUS_KEY_2, modularIcon,
                    "+${lateralAccelPercentage.roundToInt()}% Lateral Accel",
                    "- ${-rotAccelPercentage.roundToInt()}% Rotational Accel", true)
                engine.maintainStatusForPlayerShip(SYSTEM_STATUS_KEY_1, modularIcon,
                    "Shell Mode", "+${speedPercentage.roundToInt()} Max Speed", false)
            }
        }

        initReflection(ship)

        ship.allWeapons.forEach { w ->
            val target = targetStates[w.slot.id] ?: return@forEach

            val (baseAngle, baseArc) = baseSlots.getOrPut(w.slot.id) {
                Pair(w.slot.angle, w.slot.arc)
            }

            val currentAngle = MathUtils.clampAngle(
                baseAngle + (MathUtils.getShortestRotation(baseAngle, target.angle) * effectLevel)
            )
            val currentArc = Misc.interpolate(baseArc, target.arc, effectLevel)

            w.slot.angle = currentAngle
            w.slot.arc = currentArc

            w.invoke("getAimTracker")?.let { tracker ->
                aimTrackerAngleField?.let { tracker.set(it, currentAngle) }
                aimTrackerArcField?.let { tracker.set(it, currentArc) }
            }
        }
    }
}
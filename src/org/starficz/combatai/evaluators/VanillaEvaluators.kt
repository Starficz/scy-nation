package org.starficz.combatai.evaluators

import com.fs.starfarer.api.combat.ShipAPI
import org.starficz.combatai.predictor.MobilityProfile

/**
 * Handles Vanilla Maneuvering Jets
 * Typically provides a flat +50 to max speed and +100% to acceleration/deceleration.
 */
class ManeuveringJetsEvaluator : ActiveMobilityEvaluator() {
    override val enforceOverride = true
    override val systemId = "maneuveringjets"

    override fun getActiveProfile(ship: ShipAPI, base: MobilityProfile): MobilityProfile {
        val sys = ship.system
        val activeTime = sys.chargeUpDur + sys.chargeActiveDur + sys.chargeDownDur

        return MobilityProfile(
            maxSpeedOverride1 = base.maxSpeedOverride1 + 50f,
            accelOverride1 = base.accelOverride1 * 2.0f,
            decelOverride1 = base.decelOverride1 * 2.0f,
            phase1Duration = activeTime,
            maxSpeedOverride2 = base.maxSpeedOverride1,
            accelOverride2 = base.accelOverride1,
            decelOverride2 = base.decelOverride1
        )
    }
}

/**
 * Handles Vanilla Plasma Jets (e.g., Odyssey)
 * Typically provides +125 to max speed and +150% to acceleration/deceleration.
 */
class PlasmaJetsEvaluator : ActiveMobilityEvaluator() {
    override val enforceOverride = true
    override val systemId = "plasmajets"

    override fun getActiveProfile(ship: ShipAPI, base: MobilityProfile): MobilityProfile {
        val sys = ship.system
        val activeTime = sys.chargeUpDur + sys.chargeActiveDur + sys.chargeDownDur

        return MobilityProfile(
            maxSpeedOverride1 = base.maxSpeedOverride1 + 125f,
            accelOverride1 = base.accelOverride1 * 2.5f,
            decelOverride1 = base.decelOverride1 * 2.5f,
            phase1Duration = activeTime,
            maxSpeedOverride2 = base.maxSpeedOverride1,
            accelOverride2 = base.accelOverride1,
            decelOverride2 = base.decelOverride1
        )
    }
}
package org.starficz.combatai.evaluators

import com.fs.starfarer.api.combat.ShipAPI
import org.starficz.combatai.predictor.MobilityProfile

class ScyThrustersEvaluator : ActiveMobilityEvaluator() {
    override val enforceOverride = false
    override val systemId = "SCY_secondaryThrusters"

    override fun getActiveProfile(ship: ShipAPI, base: MobilityProfile): MobilityProfile {
        val sys = ship.system
        val activeTime = sys.chargeUpDur + sys.chargeActiveDur + sys.chargeDownDur

        return MobilityProfile(
            maxSpeedOverride1 = base.maxSpeedOverride1 + 100f,
            accelOverride1 = base.accelOverride1 * 2.5f,
            decelOverride1 = base.decelOverride1 * 2.5f,
            phase1Duration = activeTime,
            maxSpeedOverride2 = base.maxSpeedOverride1,
            accelOverride2 = base.accelOverride1
        )
    }
}

class ScyArmorSwitchEvaluator : ToggleMobilityEvaluator() {
    override val enforceOverride = false
    override val systemId = "SCY_armorSwitch"

    override fun getOnProfile(ship: ShipAPI, base: MobilityProfile): MobilityProfile {
        return MobilityProfile(
            maxSpeedOverride1 = base.maxSpeedOverride1 + 40f,
            accelOverride1 = base.accelOverride1 * 2.0f,
            decelOverride1 = base.decelOverride1 * 2.0f
        )
    }
}
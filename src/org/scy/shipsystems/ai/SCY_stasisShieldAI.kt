package org.scy.shipsystems.ai

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import org.lazywizard.lazylib.combat.AIUtils
import org.lwjgl.util.vector.Vector2f
import org.scy.StarficzAIUtils
import org.scy.hullmods.ScyEngineering.ScyVentingAI

class SCY_stasisShieldAI: ShipSystemAIScript {
    lateinit var ship: ShipAPI
    lateinit var ventingAI: ScyVentingAI
    override fun init(ship: ShipAPI, system: ShipSystemAPI?, flags: ShipwideAIFlags?, engine: CombatEngineAPI?) {
        this.ship = ship
        ventingAI = ship.getListeners(ScyVentingAI::class.java).first()
    }

    override fun advance(amount: Float, missileDangerDir: Vector2f?, collisionDangerDir: Vector2f?, target: ShipAPI?) {
        val currentTime = Global.getCombatEngine().getTotalElapsedTime(false)
        val timeElapsed: Float = currentTime - ventingAI.lastUpdatedTime

        var fluxTakenIfNoSystem = 0f

        for (hit in ventingAI.incomingProjectiles) {
            val timeToHit: Float = (hit.timeToHit - timeElapsed)
            if (timeToHit < -0.1f) continue  // skip hits that have already happened

            if (timeToHit < ship.system.chargeUpDur + ship.system.chargeActiveDur + ship.system.chargeDownDur) {
                fluxTakenIfNoSystem += StarficzAIUtils.fluxToShield(hit.damageType, hit.damage, ship)
            }
        }

        for (hit in ventingAI.predictedWeaponHits) {
            val timeToHit: Float = (hit.timeToHit - timeElapsed)
            if (timeToHit < -0.1f) continue  // skip hits that have already happened

            // limit the predictive horizon to not speculate too much
            if (timeToHit < (ship.system.chargeUpDur + ship.system.chargeActiveDur + ship.system.chargeDownDur)/2f) {
                fluxTakenIfNoSystem += StarficzAIUtils.fluxToShield(hit.damageType, hit.damage, ship)
            }
        }

        if(ship.system.fluxPerUse < fluxTakenIfNoSystem && AIUtils.canUseSystemThisFrame(ship)) ship.useSystem()
    }
}
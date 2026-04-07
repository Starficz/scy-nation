package org.scy.weapons.ai

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.util.IntervalUtil
import org.magiclib.util.MagicTargeting

class SCY_kineticMissileAI(
    val missile: MissileAPI,
    val launchingShip: ShipAPI
) : MissileAIPlugin, GuidedMissileAI {

    private var armingTimer = 0f
    private val interval = IntervalUtil(0.1f, 0.2f)
    private val offsetInterval = IntervalUtil(0.25f, 0.5f)
    private val guidanceState = GeneralMissileAI.WeavingState(0.5f)

    private var _target: CombatEntityAPI? = null
    override fun getTarget(): CombatEntityAPI? = _target
    override fun setTarget(target: CombatEntityAPI?) { _target = target }

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine()

        // Skip AI if the missile is engineless or the game is paused
        if (engine.isPaused || missile.isFading || missile.isFizzling) return

        // Arming phase
        if (armingTimer < 0.5f) {
            armingTimer += amount
            if (armingTimer >= 0.5f) {
                target = launchingShip.shipTarget
            }
            return
        }

        val currentTarget = target

        // Validate target (dead, phased out, or missing) - find a new one if needed
        if (currentTarget == null
            || (currentTarget is ShipAPI && !currentTarget.isAlive)
            || !engine.isEntityInPlay(currentTarget)
            || currentTarget.collisionClass == CollisionClass.NONE
        ) {
            missile.giveCommand(ShipCommand.ACCELERATE)
            target = MagicTargeting.pickTarget(
                missile,
                MagicTargeting.targetSeeking.FULL_RANDOM,
                500, 360,
                0, 1, 1, 1, 1,
                true
            )
            guidanceState.cachedIntercept = null
            guidanceState.cachedTimeToIntercept = null
            return
        }

        // Pass the state variables into the reusable function
        GeneralMissileAI.guideWeavingMissile(
            missile = missile,
            target = currentTarget,
            dt = amount,
            navInterval = interval,
            offsetInterval = offsetInterval,
            state = guidanceState,
            proportionalNavN = 2.5f,
            minSpeed = 1f
        )
    }
}
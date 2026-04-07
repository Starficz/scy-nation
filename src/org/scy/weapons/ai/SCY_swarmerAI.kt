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
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.ext.rotate
import org.magiclib.util.MagicTargeting

class SCY_swarmerAI(
    val missile: MissileAPI,
    val launchingShip: ShipAPI
) : MissileAIPlugin, GuidedMissileAI {

    private var _target: CombatEntityAPI? = null
    override fun getTarget(): CombatEntityAPI? = _target
    override fun setTarget(target: CombatEntityAPI?) { _target = target }

    // Targeting and Arming Data
    private var isSeeking = true
    private var seekingTimer = 0f
    private val seekingDoneTime = MathUtils.getRandomNumberInRange(0.25f, 0.5f)

    // Guidance State
    private val navInterval = IntervalUtil(0.1f, 0.2f)
    private val offsetInterval = IntervalUtil(0.25f, 0.5f)
    private val guidanceState = GeneralMissileAI.WeavingState(0.8f)

    init {
        if (launchingShip.variant?.hasHullMod("eccm") == true) {
            offsetInterval.setInterval(0.1f, 0.2f)
        }
        val offset = MathUtils.getRandomNumberInRange(-2.5f, 2.5f)
        missile.facing += offset
        missile.velocity.rotate(offset)
    }

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return

        // Skip AI if the missile is engineless or the game paused
        if (engine.isPaused || missile.isFading || missile.isFizzling) {
            return
        }

        // The missile takes some time before starting to seek its target
        if (isSeeking) {
            missile.giveCommand(ShipCommand.ACCELERATE)
            seekingTimer += amount
            if (seekingTimer >= seekingDoneTime) {
                isSeeking = false
                target = MagicTargeting.pickTarget(
                    missile,
                    MagicTargeting.targetSeeking.LOCAL_RANDOM,
                    750, 360,
                    3, 2, 1, 1, 1,
                    true
                )

            }
            return
        }

        val currentTarget = target

        // If the missile has no valid target, accelerate blind and pick the nearest one
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
                3, 2, 1, 1, 1,
                true
            )
            guidanceState.cachedIntercept = null
            guidanceState.cachedTimeToIntercept = null
            return
        }

        // Use the shared weaving guidance logic
        GeneralMissileAI.guideWeavingMissile(
            missile = missile,
            target = currentTarget,
            dt = amount,
            navInterval = navInterval,
            offsetInterval = offsetInterval,
            state = guidanceState,
            proportionalNavN = 3f,
            minSpeed = 1f
        )
    }
}
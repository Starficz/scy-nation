package org.scy.weapons.ai

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAIPlugin
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.combat.AIUtils
import org.scy.calculateInterceptTime
import org.scy.plugins.SCY_AntiMissileManager
import java.awt.Color

class SCY_antiMissileAI(
    val missile: MissileAPI,
    val ship: ShipAPI
) : MissileAIPlugin, GuidedMissileAI {

    private val EXPLOSION_COLOR = Color(255, 0, 0, 255)
    private val PARTICLE_COLOR = Color(240, 200, 50, 255)
    private val PROPORTIONAL_NAV_N = 3f
    private var faced = false

    init {
        val engine = Global.getCombatEngine()
        if (engine != null) {
            SCY_AntiMissileManager.setFlag(engine)
            SCY_AntiMissileManager.getInstance(engine).register(missile)
        }
    }

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isPaused || missile.isFading || missile.isFizzling) return

        val manager = SCY_AntiMissileManager.getInstance(engine)
        val target = manager.assignments[missile]

        if (!faced){
            faced = true
            if (target != null)
                missile.facing = VectorUtils.getAngle(missile.location, target.location)
        }

        if (target == null || !engine.isEntityInPlay(target)) {
            missile.giveCommand(ShipCommand.ACCELERATE)
            return
        }

        val distSq = MathUtils.getDistanceSquared(missile.location, target.location)

        // 1. Proximity Detonation
        if (distSq < 2500f) {
            detonate(target, manager)
            return
        }

        // 2. Compute Intercept
        val interceptTime = calculateInterceptTime(
            missile.location, missile.velocity,
            target.location, target.velocity,
            missile.maxSpeed
        )

        val interceptPoint = if (interceptTime != null) {
            org.lwjgl.util.vector.Vector2f(
                target.location.x + target.velocity.x * interceptTime,
                target.location.y + target.velocity.y * interceptTime
            )
        } else {
            target.location // Fallback if kinematic math is impossible
        }

        // 3. Execute Shared Guidance
        GeneralMissileAI.steerToIntercept(
            missile = missile,
            interceptPoint = interceptPoint,
            timeToIntercept = interceptTime,
            proportionalNavN = PROPORTIONAL_NAV_N
        )
    }

    private fun detonate(primaryTarget: MissileAPI, manager: SCY_AntiMissileManager) {
        val engine = Global.getCombatEngine() ?: return

        engine.applyDamage(
            primaryTarget, primaryTarget.location,
            missile.damageAmount, DamageType.FRAGMENTATION,
            0f, false, false, missile.source
        )

        val nearby = AIUtils.getNearbyEnemyMissiles(missile, 100f)
        for (cm in nearby) {
            if (cm != primaryTarget) {
                engine.applyDamage(
                    cm, cm.location,
                    missile.damageAmount * 0.5f,
                    DamageType.FRAGMENTATION,
                    0f, false, true, missile.source
                )
            }
        }

        engine.addHitParticle(missile.location, org.lwjgl.util.vector.Vector2f(), 100f, 1f, 0.25f, EXPLOSION_COLOR)
        for (i in 0 until 10) {
            val axis = (Math.random() * 360f).toFloat()
            val range = (Math.random() * 100f).toFloat()
            engine.addHitParticle(
                MathUtils.getPoint(missile.location, range / 5f, axis),
                MathUtils.getPoint(org.lwjgl.util.vector.Vector2f(), range, axis),
                2f + (Math.random() * 2f).toFloat(),
                1f,
                1f + (Math.random()).toFloat(),
                PARTICLE_COLOR
            )
        }

        manager.unregister(missile)
        engine.applyDamage(
            missile, missile.location,
            missile.hitpoints * 2f,
            DamageType.FRAGMENTATION,
            0f, false, false, missile
        )
    }

    override fun getTarget(): com.fs.starfarer.api.combat.CombatEntityAPI? {
        val engine = Global.getCombatEngine() ?: return null
        return SCY_AntiMissileManager.getInstance(engine).assignments[missile]
    }

    override fun setTarget(target: com.fs.starfarer.api.combat.CombatEntityAPI?) {}
}
package org.scy.weapons

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lazywizard.lazylib.ext.plus
import org.lwjgl.util.vector.Vector2f
import org.scy.ReflectionUtils
import org.scy.ReflectionUtils.get
import org.scy.ReflectionUtils.getFieldsWithMethodsMatching
import org.scy.ReflectionUtils.getMethodsMatching
import org.scy.ReflectionUtils.invoke
import org.scy.getNearestSegmentOnBounds
import org.scy.nextFloat
import org.scy.plugins.SCY_muzzleFlashesPlugin
import java.awt.Color
import kotlin.math.max
import kotlin.random.Random
import kotlin.random.nextInt

class MinigunEffect: EveryFrameWeaponEffectPlugin {
    val SPLINTER_HIT_OFFSET = 5f

    private var init = false
    private lateinit var getWeaponTrackerMethod: ReflectionUtils.ReflectedMethod
    private lateinit var chargeTrackerField: ReflectionUtils.ReflectedField
    private lateinit var getChargeStateMethod: ReflectionUtils.ReflectedMethod
    private lateinit var chargeTrackerAdvanceMethod: ReflectionUtils.ReflectedMethod
    private lateinit var minigunStats: MinigunStats

    private val beamTextureTimer = IntervalUtil(1/40f, 1/15f) // these timings are mostly arbitrary on what looks good
    private val beamImpactSoundTimer = IntervalUtil(1/15f, 1/10f)
    private val beamImpactSplinterTimer = IntervalUtil(1/30f, 1/20f)

    var addedMuzzleFlashes = false
    var currentChargeLevel = 0f
    var animationTimer = 0f
    var currentSpriteFrame: Int = 0
    val ANIMATIONS_PER_FULL_ROTATION = 3f

    data class MinigunStats(
        val maxRPM: Float,
        val pitch: Float,
        val textureRandomnessRange: IntRange,
        val glowSize: Float
    )

    private fun getMinigunStats(weaponSize: WeaponSize): MinigunStats {
        val baseStats = when (weaponSize) {
            WeaponSize.SMALL -> MinigunStats(600f, 1.1f, IntRange(10, 20), 40f)
            WeaponSize.MEDIUM -> MinigunStats(400f, 1f, IntRange(10, 20), 60f)
            WeaponSize.LARGE -> MinigunStats(400f, 0.9f, IntRange(10, 20), 80f)
            else -> MinigunStats(400f, 1f, IntRange(10, 20), 60f)
        }

        return baseStats.copy(
            maxRPM = baseStats.maxRPM + Random.nextInt(-50, 50),
            pitch = baseStats.pitch + Random.nextFloat(-0.02f, 0.02f),
            textureRandomnessRange = baseStats.textureRandomnessRange,
            glowSize = baseStats.glowSize
        )
    }


    override fun advance(amount: Float, engine: CombatEngineAPI?, weapon: WeaponAPI?) {
        if (weapon == null || engine == null || engine.isPaused) return
        if (!::minigunStats.isInitialized) minigunStats = getMinigunStats(weapon.size)

        // -------------------- Update Damages/Add muzzle fx --------------------
        weapon.beams?.firstOrNull()?.let { energyBeam ->
            energyBeam.damage.isForceHardFlux = true
            energyBeam.damage.type = DamageType.ENERGY
            energyBeam.damage.modifier.modifyMult("ScyMinigunVA", 2/3f) // 2/3 of half is 1/3 energy dps
            // make energy beam invisible
            energyBeam.coreColor = Color(0,0,0,0)
            energyBeam.fringeColor = Color(0,0,0,0)
        }
        weapon.beams?.lastOrNull()?.let { fragBeam ->
            fragBeam.damage.modifier.modifyMult("ScyMinigunVA", 2f) // double of half is listed frag dps
            // add flashes
            if (!addedMuzzleFlashes){
                addedMuzzleFlashes = true
                SCY_muzzleFlashesPlugin.addMuzzle(weapon, 0f, Random.nextFloat() > 0.5f)
            }

            randomizeTextureLengthAndSpawnSmoke(amount, fragBeam, engine, weapon)
            playImpactSounds(amount, fragBeam)
            spawnImpactSplinters(amount, fragBeam, engine, weapon)

        } ?: run {
            // remove flashes if no beam
            if (addedMuzzleFlashes){
                addedMuzzleFlashes = false
                SCY_muzzleFlashesPlugin.removeMuzzle(weapon)
            }
        }

        // -------------------- Init for Charge/Sounds/Animations --------------------
        if (!init) {
            getWeaponTrackerMethod = weapon.getMethodsMatching("getWeaponTracker").firstOrNull() ?: return
            val weaponTracker = weapon.invoke(getWeaponTrackerMethod) ?: return
            chargeTrackerField = weaponTracker.getFieldsWithMethodsMatching(
                methodParameterTypes = arrayOf(Boolean::class.java, Float::class.java)
            ).firstOrNull() ?: return
            val chargeTracker = weaponTracker.get(chargeTrackerField) ?: return
            getChargeStateMethod = chargeTracker.getMethodsMatching(
                returnType = Enum::class.java
            ).firstOrNull() ?: return
            chargeTrackerAdvanceMethod = chargeTracker.getMethodsMatching(
                parameterTypes = arrayOf(Boolean::class.java, Float::class.java)
            ).firstOrNull() ?: return
            init = true
        }

        // -------------------- Sync Charge and play sounds --------------------

        // we need actual chargedown time to be 0 to stop the beam from firing the second we let go,
        // but losing all spin feels really bad in gameplay, so we need to manually track """chargedown"""

        val weaponTracker = weapon.invoke(getWeaponTrackerMethod) ?: return
        val chargeTracker = weaponTracker.get(chargeTrackerField) ?: return
        val chargeState = chargeTracker.invoke(getChargeStateMethod) as Enum<*>? ?: return

        syncChargeLevelAndPlaySpinSounds(chargeState, weapon, chargeTracker, amount)

        // -------------------- Spin Animations --------------------
        if (weapon.slot.isHidden || (weapon.animation?.numFrames ?: 0) == 0) return
        animateBarrelSpin(weapon, amount)

    }

    private fun syncChargeLevelAndPlaySpinSounds(
        chargeState: Enum<*>,
        weapon: WeaponAPI,
        chargeTracker: Any,
        amount: Float
    ) {

        val chargeUpTime = weapon.spec.beamChargeupTime/weapon.ship.mutableStats.energyRoFMult.modifiedValue

        if (chargeState.name in listOf("CHARGING_UP", "ACTIVE")) {
            // play spinUp sound when first spinning up
            if (currentChargeLevel == 0f) {
                Global.getSoundPlayer().playSound(
                    "SCY_minigun_spinUp",
                    minigunStats.pitch,
                    0.1f,
                    weapon.location,
                    weapon.ship.velocity
                )
            }

            // sync local and weapon charge levels
            if (currentChargeLevel <= weapon.chargeLevel) currentChargeLevel = weapon.chargeLevel
            else {
                val chargeLevelNeeded = currentChargeLevel - weapon.chargeLevel
                chargeTracker.invoke(chargeTrackerAdvanceMethod, true, chargeLevelNeeded * chargeUpTime)
            }
        } else {
            // play spinDown sound when first spinning down
            if (currentChargeLevel == 1f) {
                Global.getSoundPlayer().playSound(
                    "SCY_minigun_spinDown",
                    minigunStats.pitch,
                    0.1f,
                    weapon.location,
                    weapon.ship.velocity
                )
            }

            // sync local and weapon charge levels
            currentChargeLevel = (currentChargeLevel - amount / chargeUpTime).coerceAtLeast(0f)
        }

        // after charge level sync, play main spin loop
        val volume =
            if (chargeState.name in listOf("CHARGING_UP", "ACTIVE")) Misc.interpolate(0.15f, 0.25f, currentChargeLevel)
            else Misc.interpolate(0f, 0.25f, currentChargeLevel)
        Global.getSoundPlayer().playLoop(
            "SCY_minigun_spin",
            weapon,
            minigunStats.pitch * Misc.interpolate(0.1f, 1f, currentChargeLevel),
            volume,
            weapon.location,
            weapon.ship.velocity
        )
    }

    private fun animateBarrelSpin(
        weapon: WeaponAPI,
        amount: Float
    ) {
        val currentTargetRPM = minigunStats.maxRPM * currentChargeLevel

        if (currentTargetRPM > 0f && currentChargeLevel > 0f) { // Ensure we have RPM and frames to animate
            val currentTargetRPS = currentTargetRPM / 60f
            // How many times the animation *asset* (which is 1/3 of a turn) should cycle per second
            val animationsCyclesPerSecond = currentTargetRPS * ANIMATIONS_PER_FULL_ROTATION
            // How many individual animation sprite frames should be displayed per second
            val spriteFramesPerSecond = animationsCyclesPerSecond * weapon.animation.numFrames

            // The delay between each sprite frame change
            // Add a small epsilon to spriteFramesPerSecond to prevent division by zero if it's extremely close to 0 but positive
            val targetDelayBetweenSpriteFrames = 1.0f / (spriteFramesPerSecond + Float.MIN_VALUE)

            // Ensure the animation doesn't try to update faster than our defined cap
            val actualDelayBetweenSpriteFrames = max(1 / 240f, targetDelayBetweenSpriteFrames)

            animationTimer += amount
            // Use a while loop in case game FPS is very low and multiple animation frames need to advance
            while (animationTimer >= actualDelayBetweenSpriteFrames) {
                animationTimer -= actualDelayBetweenSpriteFrames
                currentSpriteFrame = (currentSpriteFrame + 1) % weapon.animation.numFrames
            }
        } else {
            // If RPM is zero (chargeLevel is zero), reset animation timer.
            // The animation will pause on the currentSpriteFrame.
            animationTimer = 0f
        }
        weapon.animation.frame = currentSpriteFrame
    }

    private fun randomizeTextureLengthAndSpawnSmoke(
        amount: Float,
        fragBeam: BeamAPI,
        engine: CombatEngineAPI,
        weapon: WeaponAPI
    ) {
        // beam texture/smoke fx
        beamTextureTimer.advance(amount)
        if (beamTextureTimer.intervalElapsed()) {
            val vel = fragBeam.source.velocity
            fragBeam.pixelsPerTexel = Random.nextInt(minigunStats.textureRandomnessRange).toFloat()

            engine.addSmoothParticle(
                fragBeam.from,
                vel,
                minigunStats.glowSize + Random.nextFloat(-15f, 15f),
                0.5f,
                0.05f,
                Color(255, 50, 25, 128)
            )
            if (weapon.size in listOf(WeaponSize.MEDIUM, WeaponSize.LARGE)) {
                val grey = Random.nextFloat(0.3f, 0.6f)
                if (Random.nextFloat() > 0.5f) {
                    engine.addSmoothParticle(
                        MathUtils.getRandomPointInCircle(fragBeam.from, 5f),
                        Vector2f(
                            vel.x / 2 + Random.nextFloat(-5, 5),
                            vel.y / 2 + Random.nextFloat(-5, 5)
                        ),
                        Random.nextFloat(10f, 25f),
                        0.5f,
                        Random.nextFloat(1f, 5f),
                        Color(grey, grey, grey, Random.nextFloat(0.02f, 0.08f))
                    )
                } else {
                    engine.addSmokeParticle(
                        MathUtils.getRandomPointInCircle(fragBeam.from, 5f),
                        Vector2f(
                            vel.x / 2 + Random.nextFloat(-5f, 5f),
                            vel.y / 2 + Random.nextFloat(-5f, 5f)
                        ),
                        Random.nextFloat(10f, 25f),
                        0.5f,
                        Random.nextFloat(1f, 5f),
                        Color(grey, grey, grey, Random.nextFloat(0.02f, 0.08f))
                    )
                }
            }
        }
    }

    private fun spawnImpactSplinters(
        amount: Float,
        fragBeam: BeamAPI,
        engine: CombatEngineAPI,
        weapon: WeaponAPI
    ) {
        // splinters on impact, to fake damage vfx
        beamImpactSplinterTimer.advance(amount)
        if (fragBeam.damageTarget != null && beamImpactSplinterTimer.intervalElapsed()) {

            val (perpAngle, pointHit) = run {
                val target = fragBeam.damageTarget

                // It takes the center and base radius, and returns Pair(angle, hitPoint).
                val calculateCircularHit: (center: Vector2f, baseRadius: Float) -> Pair<Float, Vector2f> =
                    { center, baseRadius ->
                        val angle = VectorUtils.getAngle(center, fragBeam.to)
                        val effectiveRadius = baseRadius + SPLINTER_HIT_OFFSET
                        val point = center + Misc.getUnitVectorAtDegreeAngle(angle).scale(effectiveRadius) as Vector2f
                        Pair(angle, point)
                    }
                // check shield first
                if (target is ShipAPI && target.shield != null && target.shield.isWithinArc(fragBeam.to)) {
                    return@run calculateCircularHit(
                        target.shieldCenterEvenIfNoShield,
                        target.shieldRadiusEvenIfNoShield
                    )
                }
                // if no shield or not hitting shield, check bounds
                val (segment, pointOnSegment) = getNearestSegmentOnBounds(fragBeam.to, target)
                // if no bounds, treat collisionRadius as circular hit
                if (segment == null) return@run calculateCircularHit(target.location, target.collisionRadius)

                // if there is a bounds, calculate and return the correct angle/points
                val segmentAngle = VectorUtils.getAngle(segment.p1, segment.p2)

                val outwardAngle1 = segmentAngle + 90f
                val point1 = pointOnSegment + Misc.getUnitVectorAtDegreeAngle(outwardAngle1)
                    .scale(SPLINTER_HIT_OFFSET) as Vector2f
                val point1DistanceSq = MathUtils.getDistanceSquared(fragBeam.from, point1)

                val outwardAngle2 = segmentAngle - 90f
                val point2 = pointOnSegment + Misc.getUnitVectorAtDegreeAngle(outwardAngle2)
                    .scale(SPLINTER_HIT_OFFSET) as Vector2f
                val point2DistanceSq = MathUtils.getDistanceSquared(fragBeam.from, point2)

                // The closer point to the beam's origin is the "outside" point
                return@run if (point1DistanceSq < point2DistanceSq) Pair(outwardAngle1, point1)
                else Pair(outwardAngle2, point2)
            }

            val spawnSplinter: (splinterID: String) -> Unit = { splinterID ->
                (engine.spawnProjectile(
                    fragBeam.source,
                    weapon,
                    splinterID,
                    MathUtils.getRandomPointInCircle(fragBeam.to, fragBeam.width / 2),
                    perpAngle + Random.nextFloat(-90, 90),
                    fragBeam.damageTarget.velocity
                ) as DamagingProjectileAPI).apply {
                    // make sure the splinters don't do actual damage
                    damage.modifier.modifyMult("ScyMinigunSplinter", 0f)
                }
            }

            if (weapon.size == WeaponSize.SMALL) spawnSplinter("SCY_splinterS")
            else spawnSplinter("SCY_splinterM")
            if (weapon.size == WeaponSize.LARGE) spawnSplinter("SCY_splinterM")

        }
    }

    private fun playImpactSounds(
        amount: Float,
        fragBeam: BeamAPI
    ) {
        // beam impact sounds, to fake lots of bullets
        beamImpactSoundTimer.advance(amount)
        if (fragBeam.damageTarget != null && beamImpactSoundTimer.intervalElapsed()) {
            val target = fragBeam.damageTarget
            // if hit shield, play shield sound
            if (target is ShipAPI && target.shield != null && target.shield.isWithinArc(fragBeam.to)) {
                Global.getSoundPlayer().playSound(
                    "SCY_minigun_shieldHit",
                    Random.nextFloat(0.8f, 1.2f) * minigunStats.pitch,
                    Random.nextFloat(0.3f, 0.4f),
                    fragBeam.to,
                    target.velocity
                )
            } else {
                Global.getSoundPlayer().playSound(
                    "SCY_minigun_hullHit",
                    Random.nextFloat(0.8f, 1.2f) * minigunStats.pitch,
                    Random.nextFloat(0.3f, 0.4f),
                    fragBeam.to,
                    target.velocity
                )
            }
        }
    }
}
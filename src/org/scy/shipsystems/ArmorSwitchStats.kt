package org.scy.shipsystems

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CollisionClass
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript
import com.fs.starfarer.api.plugins.ShipSystemStatsScript
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.starficz.ReflectionUtils.get
import org.starficz.ReflectionUtils.getFieldsMatching
import org.starficz.ReflectionUtils.invoke
import org.starficz.ReflectionUtils.set
import kotlin.math.roundToInt
import org.starficz.ReflectionUtils.getMethodsMatching
import org.starficz.ReflectionUtils.ReflectedField
import org.starficz.ReflectionUtils.ReflectedMethod
import java.util.HashMap

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
    private var aimTrackerAngleField: ReflectedField? = null
    private var aimTrackerArcField: ReflectedField? = null

    private var SYSTEM_STATUS_KEY_1: Any = Any()
    private var SYSTEM_STATUS_KEY_2: Any = Any()

    private val decalManager = DamageDecalManager()
    private var currentFrame = 0

    private var combinedEffectLevel = 0f

    private fun initArcReflection(ship: ShipAPI) {
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

        if (ship.childModulesCopy.isEmpty() || !ship.childModulesCopy.firstOrNull()!!.isAlive)
            ship.system.forceState(ShipSystemAPI.SystemState.IDLE, 0f)

        val speedIncrease = 40f * effectLevel
        val lateralAccelPercentage = 150f * effectLevel
        val rotAccelPercentage = -50f * effectLevel

        stats.maxSpeed.modifyFlat(id, speedIncrease)
        stats.acceleration.modifyPercent(id, lateralAccelPercentage)
        stats.deceleration.modifyPercent(id, lateralAccelPercentage)
        stats.turnAcceleration.modifyPercent(id, rotAccelPercentage)
        stats.maxTurnRate.modifyPercent(id, rotAccelPercentage)

        val engine = Global.getCombatEngine()

        if (engine.playerShip === ship) {
            if (effectLevel > 0) {
                val modularIcon = Global.getSettings().getSpriteName("icons", "scy_modules")
                engine.maintainStatusForPlayerShip(SYSTEM_STATUS_KEY_2, modularIcon,
                    "+${lateralAccelPercentage.roundToInt()}% Lateral Accel",
                    "- ${-rotAccelPercentage.roundToInt()}% Rotational Accel", true)
                engine.maintainStatusForPlayerShip(SYSTEM_STATUS_KEY_1, modularIcon,
                    "Shell Mode", "+${speedIncrease.roundToInt()} Max Speed", false)
            }
        }

        if (!engine.isPaused){
            val delta = engine.elapsedInLastFrame / ship.system.chargeUpDur

            combinedEffectLevel = if (ship.fluxTracker.isVenting) {
                (combinedEffectLevel + delta).coerceAtMost(1f)
            } else {
                (combinedEffectLevel - delta).coerceAtLeast(0f)
            }

            combinedEffectLevel = combinedEffectLevel.coerceAtLeast(effectLevel)
        }


        ship.childModulesCopy.firstOrNull()?.let{ armorModule ->
            // animation levels
            val overlap = 0.2f
            val weaponSlotAnimationLevel = (combinedEffectLevel / (0.5f + overlap/2)).coerceIn(0f, 1f)
            val armorAnimationLevel = ((combinedEffectLevel - (0.5f - overlap/2)) / (0.5f + overlap/2)).coerceIn(0f, 1f)

            val frames = 11

            val animationFrame = when {
                armorAnimationLevel <= 0f -> 0
                armorAnimationLevel >= 1f -> frames - 1
                else -> (armorAnimationLevel * (frames - 2)).toInt().coerceIn(0, frames - 3) + 1
            }

            // make module collidable / non collidable, and render at the correct layers
            if (animationFrame >= 3) {
                if (armorModule.collisionClass != CollisionClass.SHIP) {
                    armorModule.collisionClass = CollisionClass.SHIP
                    armorModule.layer = CombatEngineLayers.CONTRAILS_LAYER
                    engine.invoke("getRenderer")?.invoke("recompile", armorModule)
                }
            } else {
                if (armorModule.collisionClass != CollisionClass.NONE) {
                    armorModule.collisionClass = CollisionClass.NONE
                }

                if (ship.fluxTracker.isVenting) {
                    if (armorModule.layer != CombatEngineLayers.ASTEROIDS_LAYER) {
                        armorModule.layer = CombatEngineLayers.ASTEROIDS_LAYER
                        engine.invoke("getRenderer")?.invoke("recompile", armorModule)
                    }
                } else if (armorModule.layer != CombatEngineLayers.FRIGATES_LAYER) {
                    armorModule.layer = CombatEngineLayers.FRIGATES_LAYER
                    engine.invoke("getRenderer")?.invoke("recompile", armorModule)
                }
            }

            // animate the module sprite
            if (currentFrame != animationFrame) {
                armorModule.setSprite("orthrus_armor", "SCY_orthrus_armor_$animationFrame")
                val shift = intArrayOf(0, 2, 4, 6, 9, 11, 13, 16, 17, 18, 19)
                decalManager.update(armorModule, -shift[animationFrame].toFloat(), 0f)
                currentFrame = animationFrame
            }

            // limit/unlimit the arcs of the weapons
            initArcReflection(ship)

            ship.allWeapons.forEach { w ->
                val target = targetStates[w.slot.id] ?: return@forEach

                val (baseAngle, baseArc) = baseSlots.getOrPut(w.slot.id) {
                    Pair(w.slot.angle, w.slot.arc)
                }

                val currentAngle = MathUtils.clampAngle(
                    baseAngle + (MathUtils.getShortestRotation(baseAngle, target.angle) * weaponSlotAnimationLevel)
                )
                val currentArc = Misc.interpolate(baseArc, target.arc, weaponSlotAnimationLevel)

                w.slot.angle = currentAngle
                w.slot.arc = currentArc

                w.invoke("getAimTracker")?.let { tracker ->
                    aimTrackerAngleField?.let { tracker.set(it, currentAngle) }
                    aimTrackerArcField?.let { tracker.set(it, currentArc) }
                }
            }
        }
    }
}


/**
 * A stateful manager designed to be updated every frame.
 * It tracks the applied offset and only calls the reflection shift when the offset changes,
 * while automatically ensuring newly spawned decals catch up to the current offset.
 */
class DamageDecalManager {
    private var currentOffsetX = 0f

    private var currentOffsetY = 0f

    private val trackedDecals = mutableSetOf<Any>()
    /**
     * Call this method every frame.
     * @param ship The target ship object (ShipAPI).
     * @param targetOffsetX The absolute desired offset on the X axis.
     * @param targetOffsetY The absolute desired offset on the Y axis.
     */
    fun update(ship: ShipAPI, targetOffsetX: Float, targetOffsetY: Float) {
        val decalRenderer = ship.invoke("getDecalRenderer") ?: return
        val decalMap = decalRenderer.get(type = HashMap::class.java) as? HashMap<*, *> ?: return

        if (decalMap.isEmpty()) return

        // 1. Initialize our cached mappings if this is the first time we run globally
        if (!Heuristics.isInitialized) {
            if (!Heuristics.initialize(ship, decalMap)) return
        }

        // 2. Calculate the delta
        val deltaX = targetOffsetX - currentOffsetX
        val deltaY = targetOffsetY - currentOffsetY
        val offsetChanged = deltaX != 0f || deltaY != 0f

        // 3. Apply shifts if needed
        for (decal in decalMap.values) {
            if (decal == null) continue

            // add() returns true if the decal wasn't in the set (meaning it spawned this frame).
            val isNew = trackedDecals.add(decal)

            // We ONLY shift if the offset changed AND the decal isn't brand new.
            // A brand new decal spawned at the correct translated bounds, so it needs 0 adjustment today.
            if (offsetChanged && !isNew) {
                shiftDecal(decal, deltaX, deltaY)
            }
        }

        // 4. Memory cleanup: Remove decals from our tracker that no longer exist in the game's map
        if (trackedDecals.size > decalMap.size) {
            trackedDecals.retainAll(decalMap.values.toSet())
        }

        // Update tracked state
        currentOffsetX = targetOffsetX
        currentOffsetY = targetOffsetY
    }

    private fun shiftDecal(decal: Any, shiftX: Float, shiftY: Float) {
        // A. Shift the tracking floats inside class_2578
        val currentDecalX = decal.get(Heuristics.decalXField!!) as? Float ?: return
        val currentDecalY = decal.get(Heuristics.decalYField!!) as? Float ?: return

        decal.set(Heuristics.decalXField!!, currentDecalX + shiftX)
        decal.set(Heuristics.decalYField!!, currentDecalY + shiftY)

        // B. Shift the inner visual components (class_1967$class_1968)
        val v0 = decal.get(Heuristics.visualField0!!)
        val v1 = decal.get(Heuristics.visualField1!!)

        for (visual in listOfNotNull(v0, v1)) {
            val currentVisX = visual.get(Heuristics.visualXField!!) as? Float ?: continue
            val currentVisY = visual.get(Heuristics.visualYField!!) as? Float ?: continue

            visual.set(Heuristics.visualXField!!, currentVisX + shiftX)
            visual.set(Heuristics.visualYField!!, currentVisY + shiftY)

            // C. Flag the component as dirty (true, true, true) so the VBO pushes the new matrix to the GPU
            visual.invoke(Heuristics.visualSetDirtyMethod!!, true, false, false)
        }
    }

    /**
     * A companion object to hold the cached reflected fields globally.
     * This ensures the expensive reflection search is only ever done once per game session.
     */
    private object Heuristics {
        var isInitialized = false
            private set

        var decalXField: ReflectedField? = null
        var decalYField: ReflectedField? = null
        var visualField0: ReflectedField? = null
        var visualField1: ReflectedField? = null
        var visualXField: ReflectedField? = null
        var visualYField: ReflectedField? = null
        var visualSetDirtyMethod: ReflectedMethod? = null

        fun initialize(ship: ShipAPI, decalMap: HashMap<*, *>): Boolean {
            // Using the official Starsector API significantly reduces the reflection overhead!
            val armorGrid = ship.armorGrid ?: return false
            val gridSize = armorGrid.cellSize // Note: getCellSize() maps to the same grid size float
            val leftOf = armorGrid.leftOf
            val below = armorGrid.below

            var referenceDecal: Any? = null
            var refExpectedX = 0f
            var refExpectedY = 0f

            // Find an obfuscated decal object to fingerprint
            for ((key, decal) in decalMap) {
                val keyStr = key as? String ?: continue
                val valInt = keyStr.toIntOrNull() ?: continue
                val cellY = valInt / 2000
                val cellX = valInt % 2000

                val expX = -gridSize * leftOf + cellX * gridSize + gridSize / 2f
                val expY = -gridSize * below + cellY * gridSize + gridSize / 2f

                // Avoid cases where X and Y are identical, causing false positives
                if (Math.abs(expX - expY) > 1f) {
                    val floatFields = decal.getFieldsMatching(type = Float::class.javaPrimitiveType)

                    val matchingX = floatFields.filter { Math.abs((it.get(decal) as Float) - expX) < 0.1f }
                    val matchingY = floatFields.filter { Math.abs((it.get(decal) as Float) - expY) < 0.1f }

                    if (matchingX.size == 1 && matchingY.size == 1) {
                        decalXField = matchingX.first()
                        decalYField = matchingY.first()
                        referenceDecal = decal
                        refExpectedX = expX
                        refExpectedY = expY
                        break
                    }
                }
            }

            if (referenceDecal == null) return false

            // Visual components are nested inner classes ($)
            val innerClasses = referenceDecal.getFieldsMatching().filter { it.type.name.contains("$") }
            if (innerClasses.size >= 2) {
                visualField0 = innerClasses[0]
                visualField1 = innerClasses[1]
            } else return false

            val visualComp = referenceDecal.get(visualField0!!) ?: return false

            // Fingerprint coordinate floats on the inner visual class
            val visFloatFields = visualComp.getFieldsMatching(type = Float::class.javaPrimitiveType)
            val visMatchingX = visFloatFields.filter { Math.abs((it.get(visualComp) as Float) - refExpectedX) < 0.1f }
            val visMatchingY = visFloatFields.filter { Math.abs((it.get(visualComp) as Float) - refExpectedY) < 0.1f }

            if (visMatchingX.size == 1 && visMatchingY.size == 1) {
                visualXField = visMatchingX.first()
                visualYField = visMatchingY.first()
            } else return false

            // Fingerprint the dirty flag method (the only method taking 3 booleans)
            val dirtyMethods = visualComp.getMethodsMatching(
                numOfParams = 3,
                parameterTypes = arrayOf(
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
            )

            if (dirtyMethods.size == 1) {
                visualSetDirtyMethod = dirtyMethods.first()
            } else return false

            isInitialized = true
            return true
        }
    }
}
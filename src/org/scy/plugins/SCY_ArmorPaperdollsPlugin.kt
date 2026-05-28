package org.scy.plugins

import com.fs.graphics.Sprite
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.graphics.SpriteAPI
import org.starficz.staruiframework.StarUIPlugin
import java.awt.Color
import com.fs.starfarer.api.ui.CustomPanelAPI
import org.lazywizard.lazylib.ext.minus
import org.lazywizard.lazylib.ext.plus
import org.lazywizard.lazylib.ext.rotate
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13.*
import org.lwjgl.opengl.GL15.*
import org.lwjgl.util.vector.Vector2f
import org.starficz.ReflectionUtils
import org.starficz.ReflectionUtils.getFieldsMatching
import org.starficz.armorAtCell
import org.starficz.interpolateColorNicely
import org.starficz.staruiframework.*
import org.starficz.times
import org.starficz.weakestArmorRegion
import org.starficz.cloneSpriteAPI
import java.awt.Point
import kotlin.math.max
import kotlin.math.min

class SCY_ArmorPaperdollsPlugin : StarUIPlugin {
    private val noHPColor = Color(200, 30, 30, 255)
    private val fullHPColor = Color(120, 230, 0, 255)

    // Helper data class to detect and manage sprite replacement in combat
    private class SpriteCloneEntry(val sourceSprite: SpriteAPI, val clonedSprite: SpriteAPI)

    override val addPanelAboveCombatShipInfo: (CustomPanelAPI.() -> Unit) = paperdoll@{
        val shipInfo = parent?.parent ?: return@paperdoll

        // --- 1. One-time setup: Discover ShipInfo fields on panel creation ---
        val shipField = shipInfo.getFieldsMatching(fieldAssignableTo = ShipAPI::class.java).firstOrNull() ?: return@paperdoll
        val paperdollField = ReflectionUtils.getFieldsWithMethodsMatching(
            clazz = shipInfo.javaClass,
            methodReturnType = Sprite::class.java,
            numOfMethodParams = 0
        ).firstOrNull { reflectedField ->
            !ShipAPI::class.java.isAssignableFrom(reflectedField.type)
        } ?: return@paperdoll

        CustomPanel(200f, 200f, Anchor.inside.bottomLeft.ofParent()) {
            val center = Vector2f(centerX, centerY)

            // Re-usable vector to prevent GC pressure in the render loop
            val boundsOffset = Vector2f()

            // State caches for paperdoll property lookup
            var cachedPaperdoll: Any? = null
            var boundsOffsetField: ReflectionUtils.ReflectedField? = null
            var scaleField: ReflectionUtils.ReflectedField? = null

            // Cache for cloned module sprites (garbage collected naturally when CustomPanel is discarded)
            val spriteCloneCache = mutableMapOf<ShipAPI, SpriteCloneEntry>()

            Plugin {
                render { alpha ->
                    val ship = shipField.get(shipInfo) as? ShipAPI ?: return@render
                    if (ship.childModulesCopy.isEmpty()) return@render
                    if (!ship.hullSpec?.baseHullId.orEmpty().contains("SCY_")) return@render

                    val paperdoll = paperdollField.get(shipInfo) ?: return@render

                    // --- 2. Lazy paperdoll field resolution: runs only when ship changes ---
                    if (paperdoll !== cachedPaperdoll) {
                        cachedPaperdoll = paperdoll
                        boundsOffsetField = paperdoll.getFieldsMatching(type = Vector2f::class.java).firstOrNull()

                        val floatFields = paperdoll.getFieldsMatching(type = Float::class.javaPrimitiveType)
                        scaleField = floatFields.minByOrNull { (it.get(paperdoll) as? Float) ?: Float.MAX_VALUE }
                    }

                    val boundsOffsetRef = boundsOffsetField?.get(paperdoll) as? Vector2f ?: Vector2f()
                    boundsOffset.set(boundsOffsetRef.x, boundsOffsetRef.y)

                    val scale = scaleField?.get(paperdoll) as? Float ?: 1.0f

                    initRendering()

                    // Determine the paperdoll's rotation angle on the HUD
                    val hudRotation = ship.facing - 90f

                    for (module in ship.childModulesCopy) {
                        if (module.hitpoints <= 0f) continue

                        val baseSprite = module.spriteAPI ?: continue

                        // --- 3. Lazy sprite cloning cache: Clones only on first render or texture replacements ---
                        var cacheEntry = spriteCloneCache[module]
                        if (cacheEntry == null || cacheEntry.sourceSprite !== baseSprite) {
                            val clone = baseSprite.cloneSpriteAPI()
                            if (clone != null) {
                                cacheEntry = SpriteCloneEntry(baseSprite, clone)
                                spriteCloneCache[module] = cacheEntry
                            }
                        }

                        val sprite = cacheEntry?.clonedSprite ?: continue

                        // Relative offset in world space naturally rotates with the parent ship
                        val worldOffset = module.location - ship.location
                        val scaledOffset = worldOffset * scale

                        // Rotate local bounds translation copy to match the parent's screen orientation
                        val parentCenterOffset = if (ship.visualBounds != null) {
                            Vector2f(0f, 0f)
                        } else {
                            boundsOffset.rotate(hudRotation)
                        }

                        val paperDollLocation = center + scaledOffset + parentCenterOffset

                        val weakestRegion = module.armorGrid.weakestArmorRegion() ?: Point(0, 0)
                        val cellArmor = module.armorGrid.armorAtCell(weakestRegion) ?: 0f
                        val armorHealthLevel = (cellArmor + module.hitpoints) / (module.armorGrid.armorRating + module.maxHitpoints)

                        // Mutate the cached clone (completely safe, does not affect main combat rendering)
                        sprite.apply {
                            angle = module.facing - 90f
                            color = interpolateColorNicely(noHPColor, fullHPColor, armorHealthLevel)
                            alphaMult = 0.75f * alpha

                            val origCenterX = centerX
                            val origCenterY = centerY

                            setSize(width * scale, height * scale)

                            if (origCenterX != -1.0f && origCenterY != -1.0f) {
                                setCenter(origCenterX * scale, origCenterY * scale)
                            } else {
                                setCenter(-1.0f, -1.0f)
                            }
                        }

                        sprite.renderAtCenter(paperDollLocation.x, paperDollLocation.y)
                    }

                    glPopAttrib()
                }
            }
        }
    }

    private fun initRendering() {
        // Save GL state (includes texture, blend, matrix modes, etc.)
        glPushAttrib(GL_ALL_ATTRIB_BITS)

        // Re-enable 2D texturing as the framework disables it prior to rendering
        glEnable(GL_TEXTURE_2D)

        // Use GL_COMBINE to allow color interpolation
        glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_COMBINE)

        // RGB Combine -> Use Primary Color
        glTexEnvi(GL_TEXTURE_ENV, GL_COMBINE_RGB, GL_REPLACE)
        glTexEnvi(GL_TEXTURE_ENV, GL_SRC0_RGB, GL_PRIMARY_COLOR)
        glTexEnvi(GL_TEXTURE_ENV, GL_OPERAND0_RGB, GL_SRC_COLOR)

        // Alpha Combine -> Modulate Texture Alpha * Primary Color Alpha
        glTexEnvi(GL_TEXTURE_ENV, GL_COMBINE_ALPHA, GL_MODULATE)
        glTexEnvi(GL_TEXTURE_ENV, GL_SRC0_ALPHA, GL_TEXTURE)
        glTexEnvi(GL_TEXTURE_ENV, GL_OPERAND0_ALPHA, GL_SRC_ALPHA)
        glTexEnvi(GL_TEXTURE_ENV, GL_SRC1_ALPHA, GL_PRIMARY_COLOR)
        glTexEnvi(GL_TEXTURE_ENV, GL_OPERAND1_ALPHA, GL_SRC_ALPHA)
    }
}
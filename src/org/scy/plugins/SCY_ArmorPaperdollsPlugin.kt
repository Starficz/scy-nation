package org.scy.plugins

import com.fs.graphics.util.Fader
import com.fs.starfarer.api.GameState
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.combat.DefenseUtils
import org.lwjgl.util.vector.Vector2f
import org.magiclib.kotlin.setAlpha
import org.magiclib.util.MagicUI
import org.scy.ReflectionUtils
import org.scy.StarficzAIUtils
import org.scy.armorAtCell
import org.scy.weakestArmorRegion
import java.awt.Color
import kotlin.math.pow


class SCY_ArmorPaperdollsPlugin : BaseEveryFrameCombatPlugin() {
    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        super.advance(amount, events)
        if (Global.getCurrentState() != GameState.COMBAT) return
        val engine = Global.getCombatEngine()
        val ship = engine.playerShip ?: return

        if (!ship.isAlive) return

        //TODO: draw modules on the target preview paperdoll
        /*
        val target = engine.combatUI.mainTargetReticleTarget
        if(target != null){
            engine.addSmoothParticle(target.location, Misc.ZERO, 50f, 50f, 0.1f, Color.red);
            val targetLocation = Vector2f(viewport.convertWorldXtoScreenX(target.location.x), viewport.convertWorldYtoScreenY(target.location.y))
            Vector2f.add(targetLocation, Vector2f(200f,-100f), targetLocation)
            viewport.viewMult
            drawPaperdoll(viewport, target, targetLocation, 0.5f)

        }
        */

        // draw paperdoll for armor (note: if in uirendermethod, don't scale)
        val center = Vector2f(120f, 120f).scale(Global.getSettings().screenScaleMult) as Vector2f
        MagicUI.openGLForMiscWithinViewport()
        drawPaperdoll(ship, center, Global.getSettings().screenScaleMult)
        MagicUI.closeGLForMiscWithinViewport()
    }


    //TODO: Hardcoded bullshit, no clue how alex scales sprites here.
    private val PAPERDOLL_SCALE: Map<String, Float> = mapOf(
        "SCY_nemeanlion" to 0.405f, "SCY_keto" to 0.375f,
        "SCY_khalkotauroi" to 0.37f, "SCY_corocottaA" to 0.39f,
        "SCY_lamiaA" to 0.55f
    )

    private val noHPColor = Color(200, 30, 30, 255)
    private val fullHPColor = Color(120, 230, 0, 255)
    private fun drawPaperdoll(ship: ShipAPI, location: Vector2f, scale: Float) {

        if (PAPERDOLL_SCALE.containsKey(ship.hullSpec.baseHullId)) {
            var shipScale = PAPERDOLL_SCALE[ship.hullSpec.baseHullId]!! * scale;
            val alpha = Math.round(Misc.interpolate(0f, 170f, getUIAlpha(false)))
            for (module in ship.childModulesCopy) {
                if (module.hitpoints <= 0f) continue

                val moduleSprite = Global.getSettings().getSprite("paperdolls", module.hullSpec.baseHullId)

                val offset: Vector2f = Vector2f.sub(ship.location, module.location, null).scale(shipScale) as Vector2f
                val paperDollLocation = Vector2f.sub(location, offset, null)

                val armorHealthLevel = with(module.armorGrid) {
                    (armorAtCell(weakestArmorRegion()!!)!! + module.hitpoints) / (armorRating + module.maxHitpoints)
                }
                val paperdollColor = Misc.interpolateColor(noHPColor.setAlpha(alpha), fullHPColor.setAlpha(alpha), armorHealthLevel)

                moduleSprite.setSize(moduleSprite.width * shipScale, moduleSprite.height * shipScale)
                moduleSprite.color = paperdollColor
                //moduleSprite.setAdditiveBlend()
                moduleSprite.angle = ship.facing - 90f
                moduleSprite.renderAtCenter(paperDollLocation.x, paperDollLocation.y)
            }
        }
    }
}

const val DIALOG_ALPHA = 0.33f
const val DIALOG_FADE_OUT_TIME = 333f
const val DIALOG_FADE_IN_TIME = 250f
const val COMMAND_FADE_OUT_TIME = 200f
const val COMMAND_FADE_IN_TIME = 111f
private var dialogTime: Long = 0
private var commandTime: Long = 0
private var hudTime: Long = 0

fun getUIAlpha(isPauseIncluded: Boolean): Float {

    // Used to properly interpolate between UI fade alpha
    val alpha: Float
    if (!Global.getCombatEngine().isUIShowingHUD && !Global.getCombatEngine().isUIShowingDialog) {
        alpha = 0f
    } else if (Global.getCombatEngine().combatUI.isShowingCommandUI) {
        commandTime = System.currentTimeMillis()
        val maxAlpha = if(isPauseIncluded) 1f else 0.5f
        alpha = maxAlpha - ((commandTime - hudTime) / COMMAND_FADE_OUT_TIME).coerceAtMost(1f).pow(10f)
    } else if (Global.getCombatEngine().isUIShowingDialog) {
        dialogTime = System.currentTimeMillis()
        alpha =
            if (isPauseIncluded) 1f
            else Misc.interpolate(1f, DIALOG_ALPHA, ((dialogTime - hudTime) / DIALOG_FADE_OUT_TIME).coerceAtMost(1f))
    } else if (dialogTime > commandTime) {
        hudTime = System.currentTimeMillis()
        alpha =
            if (isPauseIncluded) 1f
            else Misc.interpolate(DIALOG_ALPHA,1f, ((hudTime - dialogTime) / DIALOG_FADE_IN_TIME).coerceAtMost(1f))
    } else {
        hudTime = System.currentTimeMillis()
        alpha = ((hudTime - commandTime) / COMMAND_FADE_IN_TIME).coerceAtMost(1f).pow(0.5f)
    }
    return MathUtils.clamp(alpha, 0f, (ReflectionUtils.get("fader", Global.getCombatEngine().combatUI) as Fader).brightness)
}


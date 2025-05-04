// By Tartiflette, allows for custom muzzle flashes and other weapons animations without damage
// decals troubles
package org.scy.plugins;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

public class SCY_muzzleFlashesPlugin extends BaseEveryFrameCombatPlugin {

  private static final Map<WeaponAPI, muzzleData> MUZZLES = new WeakHashMap<>();

  // global animation tic
  private boolean animate = false;
  private float animTimer = 0;

  // All the info needed, per muzzle type.
  public final String MINIGUN_I_ID = "SCY_minigun1";

  // minigun MKII
  public final String MINIGUN_II_ID = "SCY_minigun2";

  // minigun MKIII
  public final String MINIGUN_III_ID = "SCY_minigun3";

  // orion
  public final String ORION_ID = "SCY_orion";

  // ricochet
  public final String RICOCHET_ID = "SCY_ricochet";

  // hemor3
  public final String HEMOR3_ID = "SCY_hemor3";

  // hemor2
  public final String HEMOR2_ID = "SCY_hemor2";

  // kacc3
  public final String KACC3_ID = "SCY_kacc3";

  // kacc2
  public final String KACC2_ID = "SCY_kacc2";

  // TEB
  public final String TEB_ID = "SCY_teb";

  private void render(
      WeaponAPI weapon,
      SpriteAPI sprite,
      float width,
      float height,
      float offset,
      boolean additive) {
    // where the magic happen
    sprite.setAlphaMult(1);
    sprite.setSize(width, height);
    if (additive) {
      sprite.setAdditiveBlend();
    }
    float aim = weapon.getCurrAngle();
    Vector2f loc =
        MathUtils.getPoint(weapon.getLocation(), offset, aim); // apply the offset to the sprite
    sprite.setAngle(aim - 90);
    sprite.renderAtCenter(loc.x, loc.y);
  }

  private static class muzzleData {
    private final float TIME;
    private final int FLIP;
    private final boolean REMOVE;

    public muzzleData(float time, int flip, boolean remove) {
      this.TIME = time;
      this.FLIP = flip;
      this.REMOVE = remove;
    }
  }

  public static void addMuzzle(WeaponAPI weapon, float time, boolean flip) {
    int thisFlip = 1;
    if (flip) {
      thisFlip = -1;
    }
    MUZZLES.put(weapon, new muzzleData(time, thisFlip, false));
  }

  public static void removeMuzzle(WeaponAPI weapon) {
    MUZZLES.remove(weapon);
  }

  @Override
  public void init(CombatEngineAPI engine) {
    // reinitialize the map
    MUZZLES.clear();
  }

  public static void cleanSlate() {
    MUZZLES.clear();
  }

  @Override
  public void renderInWorldCoords(ViewportAPI view) {

    CombatEngineAPI engine = Global.getCombatEngine();
    if (engine == null) {
      return;
    }

    if (!MUZZLES.isEmpty()) {

      float amount = 0;

      int FPS = 30;
      if (!engine.isPaused()) {

        amount = (engine.getElapsedInLastFrame());
        // Global animation tic
        animTimer += amount;
        if (animTimer >= 1f / FPS) {
          animTimer -= 1f / FPS;
          animate = true;
        } else {
          animate = false;
        }
      }

      // dig through the MEMBERS
      for (Iterator<WeaponAPI> iter = MUZZLES.keySet().iterator(); iter.hasNext(); ) {
        WeaponAPI entry = iter.next();
        muzzleData data = MUZZLES.get(entry);

        if (data.REMOVE) {
          iter.remove();
        } else {

          float frame = data.TIME;
          int flip = data.FLIP;

          // Apply the right effect for the right type of weapon
          int tebFrames = 12;
          int kacc2Frames = 12;
          int kacc3Frames = 15;
          int hemor2Frames = 6;
          int hemor3Frames = 6;
          int ricochetFrames = 6;
          int orionFrames = 7;
          switch (entry.getId()) {
            case MINIGUN_I_ID:
              // random muzzle flash
              // Check if the weapon is still firing in case the ship was destroyed
              if (!entry.isFiring()) {
                iter.remove();
                break;
              }

              // turret or hardpoint offset
              float minigun1Offset;
              if (entry.getSlot().isHardpoint()) {
                float minigun1HOffset = 43;
                minigun1Offset = minigun1HOffset;
              } else {
                // set the offset from the weapon's center. T for turret, H for Hardpoint.
                float minigun1TOffset = 41;
                minigun1Offset = minigun1TOffset;
              }

              // random muzzle flash
              if (!engine.isPaused() && animate) {
                frame = MathUtils.getRandomNumberInRange(0, 6);
                if (Math.random() > 0.5f) {
                  flip *= -1f;
                }
              }
              // skip if the index is 0, it gives nice accidents in the animation
              if (frame >= 1) {
                // call the render
                float minigun1Height = 54;
                float minigun1Width = 21;
                String minigun1Sprite = "SCY_minigun1_0";
                render(
                    entry,
                    Global.getSettings().getSprite("muzzle", (minigun1Sprite + (int) frame)),
                    minigun1Width * flip, // can be flipped randomly
                        minigun1Height,
                    minigun1Offset,
                    true);
              }

              // update the entry
              MUZZLES.put(entry, new muzzleData((int) frame, flip, false));
              break;

            case MINIGUN_II_ID:
              if (!entry.isFiring()) {
                iter.remove();
                break;
              }

              float minigun2Offset;
              if (entry.getSlot().isHardpoint()) {
                float minigun2HOffset = 69;
                minigun2Offset = minigun2HOffset;
              } else {
                float minigun2TOffset = 62;
                minigun2Offset = minigun2TOffset;
              }

              if (!engine.isPaused() && animate) {
                frame = MathUtils.getRandomNumberInRange(0, 6);
                if (Math.random() > 0.5f) {
                  flip *= -1f;
                }
              }

              if (frame >= 1) {
                float minigun2Height = 70;
                float minigun2Width = 22;
                String minigun2Sprite = "SCY_minigun2_0";
                render(
                    entry,
                    Global.getSettings().getSprite("muzzle", (minigun2Sprite + (int) frame)),
                    minigun2Width * flip,
                        minigun2Height,
                    minigun2Offset,
                    true);
              }

              MUZZLES.put(entry, new muzzleData((int) frame, flip, false));
              break;

            case MINIGUN_III_ID:
              if (!entry.isFiring()) {
                iter.remove();
                break;
              }

              float minigun3Offset;
              if (entry.getSlot().isHardpoint()) {
                float minigun3HOffset = 70;
                minigun3Offset = minigun3HOffset;
              } else {
                float minigun3TOffset = 67;
                minigun3Offset = minigun3TOffset;
              }

              if (!engine.isPaused() && animate) {
                frame = MathUtils.getRandomNumberInRange(0, 6);
                if (Math.random() > 0.5f) {
                  flip *= -1f;
                }
              }

              if (frame >= 1) {
                float minigun3Height = 75;
                float minigun3Width = 35;
                String minigun3Sprite = "SCY_minigun3_0";
                render(
                    entry,
                    Global.getSettings().getSprite("muzzle", (minigun3Sprite + (int) frame)),
                    minigun3Width * flip,
                        minigun3Height,
                    minigun3Offset,
                    true);
              }

              MUZZLES.put(entry, new muzzleData((int) frame, flip, false));
              break;

            case ORION_ID:
              // animated muzzle flash
              frame += amount;
              int orionFrame = (int) Math.abs(frame * FPS - 0.5f);

              // check if the animation is finished
              if (orionFrame >= orionFrames - 1) {
                iter.remove();
                break;

              } else {
                float orionOffset;
                if (entry.getSlot().isHardpoint()) {
                  float orionHOffset = 63;
                  orionOffset = orionHOffset;
                } else {
                  float orionTOffset = 61;
                  orionOffset = orionTOffset;
                }

                float orionHeight = 86;
                float orionWidth = 101;
                String orionSprite = "SCY_orionM_0";
                render(
                    entry,
                    Global.getSettings().getSprite("muzzle", (orionSprite + (int) orionFrame)),
                        orionWidth,
                        orionHeight,
                    orionOffset,
                    true);

                MUZZLES.put(entry, new muzzleData(frame, flip, false));
                break;
              }

            case RICOCHET_ID:
              frame += amount;
              int ricochetFrame = (int) Math.abs(frame * FPS - 0.5f);

              if (ricochetFrame >= ricochetFrames - 1) {
                iter.remove();
                break;

              } else {
                float ricochetOffset;
                if (entry.getSlot().isHardpoint()) {
                  float ricochetHOffset = 28;
                  ricochetOffset = ricochetHOffset;
                } else {
                  float ricochetTOffset = 21;
                  ricochetOffset = ricochetTOffset;
                }

                float ricochetHeight = 36;
                float ricochetWidth = 67;
                String ricochetSprite = "SCY_ricochetM_0";
                render(
                    entry,
                    Global.getSettings()
                        .getSprite("muzzle", (ricochetSprite + (int) ricochetFrame)),
                        ricochetWidth,
                        ricochetHeight,
                    ricochetOffset,
                    true);

                MUZZLES.put(entry, new muzzleData(frame, flip, false));
                break;
              }

            case HEMOR3_ID:
              frame += amount;
              int hemor3Frame = (int) Math.abs(frame * FPS - 0.5f);

              if (hemor3Frame >= hemor3Frames - 1) {
                iter.remove();
                break;

              } else {
                float hemor3Offset;
                if (entry.getSlot().isHardpoint()) {
                  float hemor3HOffset = 45;
                  hemor3Offset = hemor3HOffset;
                } else {
                  float hemor3TOffset = 34;
                  hemor3Offset = hemor3TOffset;
                }

                float hemor3Height = 80;
                float hemor3Width = 85;
                String hemor3Sprite = "SCY_hemor3M";
                render(
                    entry,
                    Global.getSettings()
                        .getSprite(
                            "muzzle",
                            (hemor3Sprite
                                + (int) Math.abs(flip)
                                + "_0"
                                + (int)
                                    hemor3Frame)), // this muzzle can have 3 different animation
                                                   // chosen by the absolute value of the FLIP entry
                    hemor3Width
                        * ((float) flip
                            / Math.abs(
                                flip)), // normalize the FLIP entry from the animation selection
                        hemor3Height,
                    hemor3Offset,
                    true);

                MUZZLES.put(entry, new muzzleData(frame, flip, false));
                break;
              }

            case HEMOR2_ID:
              frame += amount;
              int hemor2Frame = (int) Math.abs(frame * FPS - 0.5f);

              if (hemor2Frame >= hemor2Frames - 1) {
                iter.remove();
                break;

              } else {
                float hemor2Offset;
                if (entry.getSlot().isHardpoint()) {
                  float hemor2HOffset = 30;
                  hemor2Offset = hemor2HOffset;
                } else {
                  float hemor2TOffset = 26;
                  hemor2Offset = hemor2TOffset;
                }

                float hemor2Height = 50;
                float hemor2Width = 44;
                String hemor2Sprite = "SCY_hemor2M";
                render(
                    entry,
                    Global.getSettings()
                        .getSprite(
                            "muzzle",
                            (hemor2Sprite + (int) Math.abs(flip) + "_0" + (int) hemor2Frame)),
                    hemor2Width * ((float) flip / Math.abs(flip)),
                        hemor2Height,
                    hemor2Offset,
                    true);

                MUZZLES.put(entry, new muzzleData(frame, flip, false));
                break;
              }

            case KACC3_ID:
              frame += amount;
              int kacc3Frame = (int) Math.abs(frame * FPS - 0.5f);
              if (kacc3Frame != (int) Math.abs((frame - amount) * FPS - 0.5f)
                  && Math.random() > 0.5) {
                flip *= -1;
              }

              if (kacc3Frame >= kacc3Frames - 1) {
                iter.remove();
                break;

              } else {
                float kacc3Offset;
                String type = "TM_";
                if (entry.getSlot().isHardpoint()) {
                  float kacc3HOffset = 22;
                  kacc3Offset = kacc3HOffset;
                  type = "HM_";
                } else {
                  float kacc3TOffset = 16;
                  kacc3Offset = kacc3TOffset;
                }
                float kacc3Height = 79;
                float kacc3Width = 57;
                String kacc3Sprite = "SCY_kacc3";
                render(
                    entry,
                    Global.getSettings()
                        .getSprite("muzzle", (kacc3Sprite + type + (int) kacc3Frame)),
                    kacc3Width * ((float) flip / Math.abs(flip)),
                        kacc3Height,
                    kacc3Offset,
                    true);

                MUZZLES.put(entry, new muzzleData(frame, flip, false));
                break;
              }

            case KACC2_ID:
              frame += amount;
              int kacc2Frame = (int) Math.abs(frame * FPS - 0.5f);
              if (kacc2Frame != (int) Math.abs((frame - amount) * FPS - 0.5f)
                  && Math.random() > 0.5) {
                flip *= -1;
              }

              if (kacc2Frame >= kacc2Frames - 1) {
                iter.remove();
                break;

              } else {
                float kacc2Offset;
                String type = "TM_";
                if (entry.getSlot().isHardpoint()) {
                  float kacc2HOffset = 14.5f;
                  kacc2Offset = kacc2HOffset;
                  type = "HM_";
                } else {
                  float kacc2TOffset = 12.5f;
                  kacc2Offset = kacc2TOffset;
                }
                float kacc2Height = 68;
                float kacc2Width = 43;
                String kacc2Sprite = "SCY_kacc2";
                render(
                    entry,
                    Global.getSettings()
                        .getSprite("muzzle", (kacc2Sprite + type + (int) kacc2Frame)),
                    kacc2Width * ((float) flip / Math.abs(flip)),
                        kacc2Height,
                    kacc2Offset,
                    true);

                MUZZLES.put(entry, new muzzleData(frame, flip, false));
                break;
              }
            case TEB_ID:
              frame += amount;
              int tebFrame = (int) Math.abs(frame * FPS - 0.5f);

              if (tebFrame >= tebFrames - 1) {
                iter.remove();
                break;

              } else {
                float tebOffset;
                if (entry.getSlot().isHardpoint()) {
                  float tebHOffset = 51.5f;
                  tebOffset = tebHOffset;
                } else {
                  float tebTOffset = 44.5f;
                  tebOffset = tebTOffset;
                }

                float tebHeight = 77;
                float tebWidth = 39;
                String tebSprite = "SCY_tebM_";
                render(
                    entry,
                    Global.getSettings().getSprite("muzzle", (tebSprite + (int) tebFrame)),
                        tebWidth,
                        tebHeight,
                    tebOffset,
                    true);

                MUZZLES.put(entry, new muzzleData(frame, flip, false));
                break;
              }
          }
        }
      }
    }
  }
}

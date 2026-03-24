# AGENTS.md - Scy Nation (Starsector Mod)

## Project Overview

Scy Nation is a faction mod for Starsector (space combat game). Mod ID: `SCY`.
Mixed Java/Kotlin codebase (~81 Java, ~12 Kotlin files). Primary package: `org.scy`.
Entry point: `org.scy.SCY_modPlugin` (extends `BaseModPlugin`).

Required mod dependencies: **LazyLib** (`lw_lazylib`), **MagicLib** (`MagicLib`).
Optional dependencies checked at runtime: GraphicsLib/ShaderLib, Nexerelin, IndEvo.

## Build / Run / Test Commands

There is **no Gradle, Maven, or Ant** build system. The project uses IntelliJ IDEA's
built-in compiler exclusively. There are no CLI build commands.

- **Build:** Open in IntelliJ IDEA (JDK 17 / JBR 17.0.14). The artifact
  `ScyNation:jar` is `build-on-make` and produces `jars/SCY_code.jar`.
  The compiled JAR is committed to the repository.
- **Run/Debug:** Use the "Run Starsector" IntelliJ run config (`.run/Run Starsector.run.xml`).
  Main class `com.fs.starfarer.StarfarerLauncher`, working dir `../../starsector-core`.
  Debug agent on port 5005 (`suspend=y`). A "Profile Starsector" config exists without
  the debug agent.
- **Tests:** **None.** No test directories, frameworks, or configs. Testing is manual.
- **Lint/Format:** **None.** No checkstyle, spotless, ktlint, or `.editorconfig`.
- **CI/CD:** GitHub Actions (`.github/workflows/release.yml`) triggers on tag pushes.
  Packages the mod folder into a zip for GitHub Releases (no compilation step).
- **Kotlin:** Language version 2.1, API version 2.1, JVM target 17.

## Project Structure

```
src/
  org/scy/                    # Primary source package
    SCY_modPlugin.java        # Mod entry point
    SCY_settingsData.java     # Settings/config loader
    SCY_txt.java              # Localization helper
    Utils.kt                  # Math/physics utilities (Complex, collision, color)
    ReflectionUtils.kt        # MethodHandle-based reflection for obfuscated code
    campaign/                 # Economy, submarkets, industries
    hullmods/                 # Hull modification scripts
    plugins/                  # Per-frame combat plugins
    shipsystems/              # Ship system stat scripts
      ai/                     # Ship system AI scripts
    subsystems/               # MagicLib subsystems
    weapons/                  # Weapon effect scripts
      ai/                     # Custom missile AI
    world/                    # Star system / world generation
  data/                       # Legacy source tree (older package structure)
```

The `src/data/` tree is legacy code. Some classes are referenced by Starsector CSV
data files via fully-qualified class names -- do not move them without updating CSVs.

## Compile-Time Libraries

| Library        | Provides                                                  |
|----------------|-----------------------------------------------------------|
| StarsectorAPI  | `com.fs.starfarer.api.*` -- core game API                 |
| LazyLib        | `org.lazywizard.lazylib.*` -- math, vectors, combat utils |
| MagicLib       | `org.magiclib.*` -- campaign helpers, subsystems, render   |
| Graphics       | `org.dark.shaders.*` -- ShaderLib visual effects           |
| ExerelinCore   | Nexerelin integration (optional)                          |

Also used: `kotlinx.coroutines`, LWJGL (`org.lwjgl.*`), `java.awt.Color`/`Point`.
Dependencies are IntelliJ application-level libraries, not declared in any build file.

## Code Style

### Naming Conventions
- **Packages:** all lowercase (`org.scy.weapons.ai`)
- **Classes:** `SCY_` prefix + PascalCase for mod-specific classes (`SCY_modPlugin`,
  `SCY_phaseTorpedoAI`). Newer Kotlin classes may omit the prefix (`ScyEngineering`,
  `FlightPathPredictor`). Utility classes also omit it (`StarficzAIUtils`).
- **Methods:** camelCase (`advance`, `applyStats`, `pickMissileAI`)
- **Constants:** `UPPER_SNAKE_CASE` for `static final` / `const val`
- **Fields:** camelCase for mutable (`timer`, `empCharge`); UPPER_SNAKE for final
  references used as lookup keys (`SCORCHER`, `FLAKED`)
- **String IDs:** format varies (`antiMissile_ID`, `FLAK_ID`) -- no single convention.

### Formatting
- **Brace style:** K&R (opening brace on same line)
- **Indentation:** Java files predominantly use 2 spaces; some use 4 spaces (e.g.,
  `StarficzAIUtils.java`, `SCY_modularArmor.java`). Kotlin files consistently use
  4 spaces. **Follow the existing indentation of the file you are editing.**
- **Line length:** no enforced limit; lines commonly exceed 120 characters.
- **Multiple declarations per line** are common for related fields:
  ```java
  private boolean launch = true, arcing = false;
  ```
- **Section comment banners** divide major code blocks:
  ```java
  //////////////////////////////
  //        MAIN LOOP         //
  //////////////////////////////
  ```

### Imports
- **No strict ordering.** General tendency: Starsector API, library imports, project
  imports, then `java.*` / LWJGL -- but not enforced.
- **Both wildcard and specific imports** are used freely, often in the same file.
  Match the style of the file you are editing.

### Access Modifiers & Annotations
- Classes are always `public`. Override methods are `public` (interface requirement).
- Helper methods: `private` or package-private. Inner class fields: often `public`.
- `@Override` is used consistently. No other annotations (`@Nullable`, `@NotNull`, etc.).

### Kotlin-Specific
- Extension functions on API types (`ArmorGridAPI.weakestArmorRegion()`)
- Operator overloading for `Vector2f` and `Complex` arithmetic
- `data class` for immutable snapshots (`ShipStateSnapshot`, `WeaponSnapshot`)
- `internal` visibility for package-internal helpers
- Coroutine-based async computation in `FlightPathPredictor.kt`

### Error Handling
- **Minimal and pragmatic.** No custom exception classes. No logging framework.
- Silent `catch` blocks for optional dependency checks:
  ```java
  try { Global.getSettings().getScriptClassLoader().loadClass("org.dark..."); }
  catch (ClassNotFoundException ex) { }
  ```
- Combat code uses **null checks and early returns** instead of try/catch:
  ```java
  if (ship == null) return;
  ```
- Debug output uses commented-out `engine.addFloatingText(...)` calls, not logging.
  One exception: `ScyEngineering.kt` uses `Global.getLogger()` for reflection info.

### Common Patterns

**Engine pause guard** -- nearly every `advance()` starts with:
```java
if (engine.isPaused()) return;
```

**IntervalUtil timer throttle** for expensive per-frame checks:
```java
private final IntervalUtil timer = new IntervalUtil(0.5f, 1f);
timer.advance(amount);
if (timer.intervalElapsed()) { /* do work */ }
```

**WeakHashMap** for tracking combat entities without preventing GC.

**Stat modification** via the Starsector mutable stats API:
```java
stats.getShieldDamageTakenMult().modifyMult(id, value);  // apply
stats.getShieldDamageTakenMult().unmodify(id);            // remove
```

**Optional mod detection** at class load time:
```java
public static final boolean haveNexerelin =
    Global.getSettings().getModManager().isModEnabled("nexerelin");
```

**Localized strings** via `SCY_txt.txt("key")`.

### Architecture: Common Base Classes / Interfaces
| Pattern               | Extends / Implements                            |
|-----------------------|-------------------------------------------------|
| Mod plugin            | `BaseModPlugin`                                 |
| Hull mods             | `BaseHullMod`                                   |
| Weapon effects        | `EveryFrameWeaponEffectPlugin`, `OnHitEffectPlugin`, `BeamEffectPlugin` |
| Missile AI            | `MissileAIPlugin` + `GuidedMissileAI` (both)    |
| Ship system stats     | `BaseShipSystemScript`                          |
| Ship system AI        | `ShipSystemAIScript`                            |
| Combat plugins        | `BaseEveryFrameCombatPlugin`                    |
| Campaign industries   | `BaseIndustry`                                  |
| World generation      | `SectorGeneratorPlugin`                         |
| MagicLib subsystems   | `MagicSubsystem`                                |
| Campaign scripts      | `EveryFrameScript`                              |

### Things to Avoid
- Do not add a logging framework; the project does not use one.
- Do not reorganize imports into a strict order -- match the file's existing style.
- Do not move classes from `src/data/` to `src/org/scy/` -- they may be referenced
  by Starsector CSV data files via fully-qualified class names.
- Do not introduce build tools (Gradle, Maven); the project uses IntelliJ artifacts.
- Do not add `@Nullable`/`@NotNull` annotations; the codebase does not use them.

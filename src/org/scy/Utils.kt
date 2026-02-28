package org.scy

import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.VectorUtils
import org.lwjgl.util.vector.Vector2f
import java.awt.Color
import java.awt.Point
import kotlin.math.*
import kotlin.random.Random

fun accelerateInDirection(
    ship: ShipAPI,
    targetAngle: Float,
    targetSpeed: Float = ship.mutableStats.maxSpeed.modifiedValue
) {
    // 1. Calculate the DESIRED velocity vector in global space
    // We multiply by PI / 180 to convert degrees to radians quickly without casting to Double
    val targetAngleRad = targetAngle * (PI.toFloat() / 180f)
    val desiredVx = cos(targetAngleRad) * targetSpeed
    val desiredVy = sin(targetAngleRad) * targetSpeed

    // 2. Calculate Delta V (Error Vector)
    // This tells us exactly how much velocity we need to add to correct our trajectory
    val deltaVx = desiredVx - ship.velocity.x
    val deltaVy = desiredVy - ship.velocity.y

    // 3. Rotate the global Delta V into the ship's LOCAL coordinate space
    val facingRad = ship.facing * (PI.toFloat() / 180f)
    val cosF = cos(facingRad)
    val sinF = sin(facingRad)

    // Standard 2D rotation matrix math
    val localDeltaVx = deltaVx * cosF + deltaVy * sinF
    val localDeltaVy = -deltaVx * sinF + deltaVy * cosF

    // 4. Apply thrusters (The PWM Logic)
    // A deadzone of ~3.0 speed units prevents thruster micro-stuttering (thrashing
    // back and forth on the exact same frame) while keeping the PWM tight and responsive.
    val deadzone = 3f

    // Forward / Backward
    if (localDeltaVx > deadzone) {
        ship.giveCommand(ShipCommand.ACCELERATE, null, 0)
    } else if (localDeltaVx < -deadzone) {
        ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0)
    }

    // Left / Right Strafing (+Y is Left in local Starsector coordinates)
    if (localDeltaVy > deadzone) {
        ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0)
    } else if (localDeltaVy < -deadzone) {
        ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0)
    }
}

fun fluxToShield(damageType: DamageType, damage: Float, ship: ShipAPI): Float {
    if (ship.shield == null) return 0f

    val stats = ship.mutableStats
    // trying to ignore systems that change shieldDamageTakenMult, as we don't know when they will change
    var shieldMultiplier = stats.shieldDamageTakenMult.base
    shieldMultiplier *= when (damageType) {
        DamageType.FRAGMENTATION -> 0.25f * stats.fragmentationDamageTakenMult.modifiedValue
        DamageType.KINETIC -> 2f * stats.kineticDamageTakenMult.modifiedValue
        DamageType.HIGH_EXPLOSIVE -> 0.5f * stats.highExplosiveDamageTakenMult.modifiedValue
        DamageType.ENERGY -> stats.energyDamageTakenMult.modifiedValue
        DamageType.OTHER -> 1f
    }

    return damage * ship.shield.fluxPerPointOfDamage * shieldMultiplier
}

fun damageAfterArmor(
    damageType: DamageType,
    damage: Float,
    hitStrength: Float,
    armorValue: Float,
    ship: ShipAPI
): Pair<Float, Float> {
    val stats: MutableShipStatsAPI = ship.mutableStats

    // Get relevant stats modifiers
    var armorMultiplier = stats.armorDamageTakenMult.modifiedValue // Use var as it changes
    val effectiveArmorMult = stats.effectiveArmorBonus.bonusMult
    var hullMultiplier = stats.hullDamageTakenMult.modifiedValue // Use var as it changes
    val minArmor = stats.minArmorFraction.modifiedValue
    val maxDR = stats.maxArmorDamageReduction.modifiedValue

    // Adjust multipliers based on damage type
    when (damageType) {
        DamageType.FRAGMENTATION -> {
            armorMultiplier *= (0.25f * stats.fragmentationDamageTakenMult.modifiedValue)
            hullMultiplier *= stats.fragmentationDamageTakenMult.modifiedValue
        }
        DamageType.KINETIC -> {
            armorMultiplier *= (0.5f * stats.kineticDamageTakenMult.modifiedValue)
            hullMultiplier *= stats.kineticDamageTakenMult.modifiedValue
        }
        DamageType.HIGH_EXPLOSIVE -> {
            armorMultiplier *= (2f * stats.highExplosiveDamageTakenMult.modifiedValue)
            hullMultiplier *= stats.highExplosiveDamageTakenMult.modifiedValue
        }
        DamageType.ENERGY -> {
            armorMultiplier *= stats.energyDamageTakenMult.modifiedValue
            hullMultiplier *= stats.energyDamageTakenMult.modifiedValue
        }
        DamageType.OTHER -> {}
    }

    // Calculate damage reduction factor
    val effectiveArmor = max(minArmor * ship.armorGrid.armorRating, armorValue) * effectiveArmorMult
    val damageReductionFactor = (hitStrength * armorMultiplier) / (effectiveArmor + hitStrength * armorMultiplier)
    val armorDR = max(1f - maxDR, damageReductionFactor)

    // Calculate damage actually applied after reduction
    val effectiveDamage = damage * armorDR

    // Calculate damage dealt to the armor layer
    val armorDamage = effectiveDamage * armorMultiplier

    // Calculate damage penetrating to the hull
    var hullDamage = 0f
    if (armorDamage > armorValue) {
        // Hull damage is the portion of effective damage corresponding to the excess armor damage
        hullDamage = ((armorDamage - armorValue) / armorDamage) * effectiveDamage * hullMultiplier
    }

    // Return the pair of damages
    return Pair(armorDamage, hullDamage)
}

/**
 * Precomputed 5x5 weight mask based on Starsector armor rules.
 * Indices [0..4][0..4] correspond to relative offsets [-2..2][-2..2].
 * - Inner 3x3 cells (including center): weight 1.0
 * - Outer 12 adjacent cells: weight 0.5
 * - Corner 4 cells: weight 0.0
 */
private val ARMOR_MASK: Array<FloatArray> = run {
    val mask = Array(5) { FloatArray(5) }
    for (relX in -2..2) {
        for (relY in -2..2) {
            val dx = abs(relX)
            val dy = abs(relY)
            mask[relX + 2][relY + 2] = when {
                dx <= 1 && dy <= 1 -> 1.0f // Inner 3x3
                (dx == 2 && dy <= 1) || (dy == 2 && dx <= 1) -> 0.5f // Outer 12
                else -> 0.0f // Corners
            }
        }
    }
    mask // Return the computed mask
}

/**
 * Builds an integral image (summed-area table) for efficient region sum queries.
 */
private fun buildIntegralImage(grid: Array<FloatArray>, width: Int, height: Int, scale: Float = 1.0f): Array<FloatArray> {
    val integral = Array(width + 1) { FloatArray(height + 1) } // Padded
    for (x in 0..<width) {
        var rowSum = 0f
        for (y in 0..<height) {
            rowSum += grid[x][y] * scale
            integral[x + 1][y + 1] = integral[x][y + 1] + rowSum
        }
    }
    return integral
}

/**
 * Calculates the sum of values within a rectangular region using a precomputed integral image.
 * Handles boundary clamping automatically.
 */
private fun getRegionSum(integral: Array<FloatArray>, x1: Int, y1: Int, x2: Int, y2: Int, width: Int, height: Int): Float {
    val minX = max(0, x1)
    val minY = max(0, y1)
    val maxX = min(width - 1, x2)
    val maxY = min(height - 1, y2)
    if (minX > maxX || minY > maxY) return 0f
    val iMaxX = maxX + 1
    val iMaxY = maxY + 1
    return integral[iMaxX][iMaxY] - integral[minX][iMaxY] - integral[iMaxX][minY] + integral[minX][minY]
}


/**
 * Finds the coordinates of a candidate cell likely to have the lowest effective armor.
 * Uses a fast scan based on the minimum unweighted sum in a 5x5 neighborhood.
 * Only considers center cells where the 5x5 kernel fits entirely within the grid.
 *
 * @receiver armorGrid The ArmorGridAPI instance for the ship.
 * @return A Point(x, y) representing the coordinates of the candidate cell,
 *         or null if the grid is smaller than 5x5 or otherwise invalid.
 */
fun ArmorGridAPI.weakestArmorRegion(): Point? {
    val grid: Array<FloatArray> = this.grid ?: return null
    val width = this.leftOf + this.rightOf
    val height = this.below + this.above

    // Grid must be at least 5x5 to contain the kernel
    if (width < 5 || height < 5) {
        return null
    }

    // Fast Scan using Integral Image
    val integralSum = buildIntegralImage(grid, width, height, 1.0f)

    var minSum5x5 = Float.MAX_VALUE
    var candidateX = -1
    var candidateY = -1

    // Iterate only through centers where the 5x5 kernel fits entirely
    for (cx in 2..<width - 2) {
        for (cy in 2..<height - 2) {
            val x5_min = cx - 2
            val y5_min = cy - 2
            val x5_max = cx + 2
            val y5_max = cy + 2

            // Get the unweighted 5x5 sum efficiently
            val currentSum5x5 = getRegionSum(integralSum, x5_min, y5_min, x5_max, y5_max, width, height)

            if (currentSum5x5 < minSum5x5) {
                minSum5x5 = currentSum5x5
                candidateX = cx
                candidateY = cy
            }
        }
    }
    return if (minSum5x5 == Float.MAX_VALUE) null else Point(candidateX, candidateY)
}

/**
 * Calculates the accurate effective armor rating for a specific cell,
 * based on the Starsector weighted sum rule, using the precomputed WEIGHT_MASK.
 * Handles boundary conditions correctly (cells outside the grid contribute 0).
 *
 * @receiver The ArmorGridAPI instance for the ship.
 * @param point A Point(x, y) representing the coordinates of the center cell for the calculation.
 * @return The calculated effective armor rating (Float). Returns null if the grid is invalid.
 */
fun ArmorGridAPI.armorAtCell(point: Point): Float? {
    val grid: Array<FloatArray> = this.grid ?: return 0.0f
    val width = this.leftOf + this.rightOf
    val height = this.below + this.above

    if (width <= 0 || height <= 0) return 0.0f

    var effectiveArmor: Float? = null

    // Perform the accurate weighted sum, checking boundaries for each neighbor
    for (relX in -2..2) {
        for (relY in -2..2) {
            val nx = point.x + relX
            val ny = point.y + relY
            // Check if the neighbor cell (nx, ny) is within the grid bounds
            if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                // Use the precomputed top-level weight mask
                val weight = ARMOR_MASK[relX + 2][relY + 2]
                if (effectiveArmor == null) effectiveArmor = 0f
                if (weight > 0f) effectiveArmor += grid[nx][ny] * weight
            }
        }
    }

    return effectiveArmor
}


fun Float.isCloseTo(other: Float, epsilon: Float): Boolean {
    if (this.isNaN() || other.isNaN())  return false
    if (this.isInfinite() || other.isInfinite()) return this == other
    return abs(this - other) <= epsilon
}

// Generic Linear Map of a number from an input range to an output range
inline fun <reified T : Number> Number.linMap(minIn: Number, maxIn: Number, minOut: Number, maxOut: Number): T {
    val value = this.toDouble()
    val dMinIn = minIn.toDouble()
    val dMaxIn = maxIn.toDouble()
    val dMinOut = minOut.toDouble()
    val dMaxOut = maxOut.toDouble()

    val result = when {
        value > dMaxIn -> dMaxOut
        value < dMinIn -> dMinOut
        else -> dMinOut + (value - dMinIn) * (dMaxOut - dMinOut) / (dMaxIn - dMinIn)
    }
    return when (T::class) {
        Double::class -> result as T
        Float::class -> result.toFloat() as T
        Long::class -> result.toLong() as T
        Int::class -> result.toInt() as T
        Short::class -> result.toInt().toShort() as T
        Byte::class -> result.toInt().toByte() as T
        else -> throw IllegalArgumentException("Unsupported type")
    }
}

// Why does this not exist????
fun Random.nextFloat(from: Number, until: Number): Float {
    return Random.nextDouble(from.toDouble(), until.toDouble()).toFloat()
}

fun getNearestSegmentOnBounds(source: Vector2f, entity: CombatEntityAPI): Pair<SegmentAPI?, Vector2f> {

    // Fall back to closest point on collision radius if entity lacks a BoundsAPI
    val bounds = entity.exactBounds ?: return Pair(null,
        MathUtils.getPointOnCircumference(
            entity.location,
            entity.collisionRadius,
            VectorUtils.getAngle(entity.location, source)
        )
    )

    val closestPoint = Vector2f(entity.location)
    var closestSegment = bounds.segments.firstOrNull()
    var closestDistanceSquared = Float.MAX_VALUE
    bounds.update(entity.location, entity.facing)
    for (segment in bounds.segments) {
        val tmp = MathUtils.getNearestPointOnLine(source, segment.p1, segment.p2)
        val distanceSquared = MathUtils.getDistanceSquared(source, tmp)
        if (distanceSquared < closestDistanceSquared) {
            closestPoint.set(tmp)
            closestSegment = segment
            closestDistanceSquared = distanceSquared
        }
    }

    return Pair(closestSegment, closestPoint)
}
/**
 * Creates a new `Color` that is a brighter version of this
 * `Color`.
 *
 *
 * This method applies an arbitrary scale factor to each of the three RGB
 * components of this `Color` to create a brighter version
 * of this `Color`.
 * The `alpha` value is preserved.
 * Although `brighter` and
 * `darker` are inverse operations, the results of a
 * series of invocations of these two methods might be inconsistent
 * because of rounding errors.
 * @return     a new `Color` object that is
 * a brighter version of this `Color`
 * with the same `alpha` value.
 * @see java.awt.Color.darker
 *
 * @since      JDK1.0
 */
fun brighter(color: Color, factor: Float): Color {
    var r = color.red
    var g = color.green
    var b = color.blue
    val alpha = color.alpha

    /* From 2D group:
         * 1. black.brighter() should return grey
         * 2. applying brighter to blue will always return blue, brighter
         * 3. non pure color (non zero rgb) will eventually return white
         */
    val i = (1.0 / (1.0 - factor)).toInt()
    if (r == 0 && g == 0 && b == 0) {
        return Color(i, i, i, alpha)
    }
    if (r > 0 && r < i) r = i
    if (g > 0 && g < i) g = i
    if (b > 0 && b < i) b = i

    return Color(
        min((r / factor).toInt(), 255),
        min((g / factor).toInt(), 255),
        min((b / factor).toInt(), 255),
        alpha
    )
}

fun darker(color: Color, factor: Float): Color {
    return Color(
        max((color.red * factor).toInt(), 0),
        max((color.green * factor).toInt(), 0),
        max((color.blue * factor).toInt(), 0),
        color.alpha
    )
}

private data class LMS(val L: Float, val M: Float, val S: Float)

// sRGB <-> Linear sRGB Conversion
private fun srgbToLinearSrgb(x: Float): Float {
    return if (x >= 0.04045f) ((x + 0.055f) / 1.055f).pow(2.4f) else (x / 12.92f)
}

private fun linearSrgbToSrgb(x: Float): Float {
    return if (x >= 0.0031308f) (1.055f * x.pow(1.0f / 2.4f) - 0.055f) else (12.92f * x)
}

// Linear sRGB Conversion <-> LMS of OkLab
private fun Color.toLMS(): LMS {
    val linearRed = srgbToLinearSrgb(red/255f)
    val linearGreen = srgbToLinearSrgb(green/255f)
    val linearBlue = srgbToLinearSrgb(blue/255f)

    return LMS(
        L = cbrt(0.4122214708f*linearRed + 0.5363325363f*linearGreen + 0.0514459929f*linearBlue),
        M = cbrt(0.2119034982f*linearRed + 0.6806995451f*linearGreen + 0.1073969566f*linearBlue),
        S = cbrt(0.0883024619f*linearRed + 0.2817188376f*linearGreen + 0.6299787005f*linearBlue)
    )
}

private fun LMS.toColor(alpha: Int? = null): Color {
    val cubedL = L.pow(3)
    val cubedM = M.pow(3)
    val cubedS = S.pow(3)
    return Color(
        linearSrgbToSrgb(+4.0767416621f*cubedL - 3.3077115913f*cubedM + 0.2309699292f*cubedS).coerceIn(0f, 1f),
        linearSrgbToSrgb(-1.2684380046f*cubedL + 2.6097574011f*cubedM - 0.3413193965f*cubedS).coerceIn(0f, 1f),
        linearSrgbToSrgb(-0.0041960863f*cubedL - 0.7034186147f*cubedM + 1.7076147010f*cubedS).coerceIn(0f, 1f),
        ((alpha ?: 255) / 255f).coerceIn(0f, 1f)
    )
}

 /** Interpolates colors via the LMS space of OkLab.
  *
  *  Produces consistent perceived lightness and consistent hue path during interpolation compared to normal srbg interpolation.
 **/
fun interpolateColorNicely(from: Color, to: Color, progress: Float): Color {
    val fromLMS = from.toLMS()
    val toLMS = to.toLMS()
    val targetLMS = LMS(
        L = Misc.interpolate(fromLMS.L, toLMS.L, progress),
        M = Misc.interpolate(fromLMS.M, toLMS.M, progress),
        S = Misc.interpolate(fromLMS.S, toLMS.S, progress)
    )
    val targetAlpha = Misc.interpolate(from.alpha.toFloat(), to.alpha.toFloat(), progress).roundToInt()
    return targetLMS.toColor(targetAlpha)
}

operator fun Vector2f.plus(other: Vector2f): Vector2f = Vector2f(this.x + other.x, this.y + other.y)
operator fun Vector2f.plusAssign(other: Vector2f) {
    this.x += other.x
    this.y += other.y
}
operator fun Vector2f.minus(other: Vector2f): Vector2f = Vector2f(this.x - other.x, this.y - other.y)
operator fun Vector2f.minusAssign(other: Vector2f) {
    this.x -= other.x
    this.y -= other.y
}
operator fun Vector2f.times(scalar: Float): Vector2f = Vector2f(this.x * scalar, this.y * scalar)

fun Vector2f.dot(other: Vector2f): Float {
    return this.x * other.x + this.y * other.y
}
fun Vector2f.normalized(): Vector2f {
    val l = this.length()
    return if (l.isApproximatelyZero()) Vector2f(0f,0f) // Return a new zero vector
    else Vector2f(this.x / l, this.y / l) // Return a new normalized vector
}

fun Vector2f.copy(): Vector2f = Vector2f(this.x, this.y) // Essential if ops modify in place

fun Float.isApproximatelyZero(epsilon: Float = FLOAT_EPSILON): Boolean = abs(this) < epsilon
fun Double.isApproximatelyZero(epsilon: Double = DOUBLE_EPSILON): Boolean = abs(this) < epsilon

const val FLOAT_EPSILON = 1e-6f
const val DOUBLE_EPSILON = 1e-9 // For internal solver calculations
const val FLOAT_EPSILON_COMPLEX_IMAG = 1e-7 // How close imag part must be to zero
const val FLOAT_EPSILON_SQ = FLOAT_EPSILON * FLOAT_EPSILON

data class Complex(val real: Double, val imag: Double = 0.0) {
    operator fun plus(other: Complex) = Complex(real + other.real, imag + other.imag)
    operator fun plus(scalar: Double) = Complex(real + scalar, imag)

    operator fun minus(other: Complex) = Complex(real - other.real, imag - other.imag)
    operator fun unaryMinus() = Complex(-real, -imag)

    operator fun times(other: Complex) = Complex(
        real * other.real - imag * other.imag,
        real * other.imag + imag * other.real
    )
    operator fun times(scalar: Double) = Complex(real * scalar, imag * scalar)

    fun absSq(): Double = real * real + imag * imag
    fun abs(): Double = sqrt(absSq())

    operator fun div(other: Complex): Complex {
        val denominator = other.absSq()
        if (denominator.isApproximatelyZero(DOUBLE_EPSILON * DOUBLE_EPSILON)) throw ArithmeticException("Complex division by zero or very small number")
        return Complex(
            (real * other.real + imag * other.imag) / denominator,
            (imag * other.real - real * other.imag) / denominator
        )
    }
    operator fun div(scalar: Double): Complex {
        if (scalar.isApproximatelyZero(DOUBLE_EPSILON)) throw ArithmeticException("Scalar division by zero or very small number")
        return Complex(real / scalar, imag / scalar)
    }

    override fun toString(): String = when {
        imag.isApproximatelyZero(FLOAT_EPSILON_COMPLEX_IMAG) -> "%.6f".format(real)
        real.isApproximatelyZero(FLOAT_EPSILON_COMPLEX_IMAG) -> "%.6fi".format(imag)
        imag < 0 -> "%.6f - %.6fi".format(real, -imag)
        else -> "%.6f + %.6fi".format(real, imag)
    }

    companion object {
        val ZERO = Complex(0.0, 0.0)
        val ONE = Complex(1.0, 0.0)
        val I = Complex(0.0, 1.0)
    }
}

fun Double.toComplex() = Complex(this)

/** Complex square root (principal value) */
fun csqrt(c: Complex): Complex {
    if (c.imag.isApproximatelyZero(DOUBLE_EPSILON) && c.real >= -DOUBLE_EPSILON) { // Non-negative real part
        return Complex(sqrt(max(0.0, c.real)))
    }
    val r = c.abs()
    // atan2 returns angle in [-PI, PI]. We need this for the principal root.
    val phi = atan2(c.imag, c.real)
    val sqrtR = sqrt(r)
    return Complex(sqrtR * cos(phi / 2.0), sqrtR * sin(phi / 2.0))
}

/** Complex principal cube root: z^(1/3) */
fun cbrtPrincipal(c: Complex): Complex {
    if (c.imag.isApproximatelyZero(DOUBLE_EPSILON)) { // Real number
        return Complex(cbrt(c.real)) // kotlin.math.cbrt handles signs correctly for reals
    }
    val r = c.abs()
    val phi = atan2(c.imag, c.real) // Angle in [-PI, PI]
    val rToPower = cbrt(r)
    val angle = phi / 3.0 // Principal angle for cube root
    return Complex(rToPower * cos(angle), rToPower * sin(angle))
}

/** Solves ax^2 + bx + c = 0 for x, using Double coefficients. Returns Complex roots. */
internal fun solveQuadraticInternal(a: Double, b: Double, c: Double): List<Complex> {
    if (a.isApproximatelyZero(DOUBLE_EPSILON)) { // Linear: bx + c = 0
        return if (b.isApproximatelyZero(DOUBLE_EPSILON)) {
            // 0x + 0 = 0 (inf solutions) or c = 0 (no solution if c!=0)
            // For root finding, typically return empty if no specific x.
            emptyList()
        } else {
            listOf(Complex(-c / b))
        }
    }
    val pNorm = b / a
    val qNorm = c / a
    val term1Real = -pNorm / 2.0
    val deltaTermVal = term1Real * term1Real - qNorm // Discriminant / (4a^2)
    val sqrtDeltaTerm = csqrt(deltaTermVal.toComplex()) // Handles real/complex delta

    return listOf(
        term1Real.toComplex() + sqrtDeltaTerm,
        term1Real.toComplex() - sqrtDeltaTerm
    )
}

/** Solves ax^2 + bx + c = 0 for x, using Complex coefficients. Returns Complex roots. */
internal fun solveQuadraticComplexCoeff(aC: Complex, bC: Complex, cC: Complex): List<Complex> {
    if (aC.abs() < DOUBLE_EPSILON) { // Linear
        return if (bC.abs() < DOUBLE_EPSILON) {
            emptyList() // Or handle as error/infinite solutions depending on context
        } else {
            listOf(-cC / bC)
        }
    }
    val delta = bC * bC - Complex(4.0) * aC * cC
    val sqrtDelta = csqrt(delta)
    val twoA = Complex(2.0) * aC
    if (twoA.abs() < DOUBLE_EPSILON) return emptyList() // Avoid division by near-zero complex

    return listOf(
        (-bC + sqrtDelta) / twoA,
        (-bC - sqrtDelta) / twoA
    )
}


/** Solves ax^3 + bx^2 + cx + d = 0 for x. Returns one real root (as Complex). Used by quartic solver. */
internal fun solveCubicOneRealInternal(a0: Double, b0: Double, c0: Double, d0: Double): Complex {
    if (a0.isApproximatelyZero(DOUBLE_EPSILON)) { // Fallback to quadratic
        val roots = solveQuadraticInternal(b0, c0, d0)
        return roots.firstOrNull { it.imag.isApproximatelyZero(FLOAT_EPSILON_COMPLEX_IMAG) }
            ?: Complex(Double.NaN) // Sentinel for failure
    }
    // Normalize: x^3 + ax^2 + bx + c = 0
    val a = b0 / a0
    val b = c0 / a0
    val cCub = d0 / a0 // Renamed to avoid clash with 'c' in quadratic

    val third = 1.0 / 3.0
    val a13 = a * third
    val a2Val = a13 * a13 // (a/3)^2

    val fVal = third * b - a2Val
    val gVal = a13 * (2.0 * a2Val - b) + cCub
    val hVal = 0.25 * gVal * gVal + fVal * fVal * fVal

    if (fVal.isApproximatelyZero(DOUBLE_EPSILON) &&
        gVal.isApproximatelyZero(DOUBLE_EPSILON) &&
        hVal.isApproximatelyZero(DOUBLE_EPSILON)) {
        return -cbrtPrincipal(cCub.toComplex()) // -cbrt(c)
    } else if (hVal <= DOUBLE_EPSILON) { // All roots are real (h can be slightly positive due to precision)
        val jVal = sqrt(max(0.0, -fVal)) // f must be <=0 for real j
        var kArgRaw = 0.0
        if (! (jVal * jVal * jVal).isApproximatelyZero(DOUBLE_EPSILON) ) { // Check jVal^3 != 0
            kArgRaw = -0.5 * gVal / (jVal * jVal * jVal)
        }
        val kArg = kArgRaw.coerceIn(-1.0, 1.0) // Clamp for acos domain
        val k = acos(kArg)
        val m = cos(third * k)
        return Complex(2.0 * jVal * m - a13)
    } else { // One real root and two complex conjugate (hVal > 0)
        val sqrtH = csqrt(hVal.toComplex())
        val S = cbrtPrincipal(Complex(-0.5 * gVal) + sqrtH)
        val U = cbrtPrincipal(Complex(-0.5 * gVal) - sqrtH)
        return (S + U - a13.toComplex())
    }
}

/** Solves ax^3 + bx^2 + cx + d = 0 for x. Returns all Complex roots. */
internal fun solveCubicAllRootsInternal(a0: Double, b0: Double, c0: Double, d0: Double): List<Complex> {
    if (a0.isApproximatelyZero(DOUBLE_EPSILON)) {
        return solveQuadraticInternal(b0, c0, d0)
    }
    val a = b0 / a0
    val b = c0 / a0
    val cCub = d0 / a0

    val third = 1.0 / 3.0
    val a13 = a * third
    val a2Val = a13 * a13
    val sqrt3 = sqrt(3.0)

    val fVal = third * b - a2Val
    val gVal = a13 * (2.0 * a2Val - b) + cCub
    val hVal = 0.25 * gVal * gVal + fVal * fVal * fVal

    if (fVal.isApproximatelyZero(DOUBLE_EPSILON) &&
        gVal.isApproximatelyZero(DOUBLE_EPSILON) &&
        hVal.isApproximatelyZero(DOUBLE_EPSILON)) {
        val r = -cbrtPrincipal(cCub.toComplex())
        return listOf(r, r, r)
    } else if (hVal <= DOUBLE_EPSILON) { // All roots are real
        val jVal = sqrt(max(0.0, -fVal))
        var kArgRaw = 0.0
        if (! (jVal * jVal * jVal).isApproximatelyZero(DOUBLE_EPSILON) ) {
            kArgRaw = -0.5 * gVal / (jVal * jVal * jVal)
        }
        val kArg = kArgRaw.coerceIn(-1.0, 1.0)
        val k = acos(kArg)
        val mVal = cos(third * k)
        val nVal = sqrt3 * sin(third * k)
        return listOf(
            Complex(2.0 * jVal * mVal - a13),
            Complex(-jVal * (mVal + nVal) - a13),
            Complex(-jVal * (mVal - nVal) - a13)
        )
    } else { // One real root and two complex conjugate
        val sqrtH = csqrt(hVal.toComplex())
        val S = cbrtPrincipal(Complex(-0.5 * gVal) + sqrtH)
        val U = cbrtPrincipal(Complex(-0.5 * gVal) - sqrtH)
        val S_plus_U = S + U
        val S_minus_U = S - U
        return listOf(
            (S_plus_U - a13.toComplex()),
            (Complex(-0.5) * S_plus_U - a13.toComplex() + S_minus_U * Complex(sqrt3 * 0.5) * Complex.I),
            (Complex(-0.5) * S_plus_U - a13.toComplex() - S_minus_U * Complex(sqrt3 * 0.5) * Complex.I)
        )
    }
}

/** Solves ax^4 + bx^3 + cx^2 + dx + e = 0 for x. Returns all Complex roots. */
internal fun solveQuarticInternal(a0: Double, b0: Double, c0: Double, d0: Double, e0: Double): List<Complex> {
    if (a0.isApproximatelyZero(DOUBLE_EPSILON)) {
        return solveCubicAllRootsInternal(b0, c0, d0, e0)
    }
    // Normalize to x^4 + ax^3 + bx^2 + cx + d = 0
    val a = b0 / a0
    val b = c0 / a0
    val cQuart = d0 / a0 // Renamed
    val dQuart = e0 / a0 // Renamed

    // Ferrari's method, variable names from Python reference for its specific resolvent form
    val pCoeffA = 0.25 * a // Python's 'a0' in single_quartic (not to be confused with input a0)
    val pCoeffASq = pCoeffA * pCoeffA

    // Coefficients for the specific resolvent cubic used in the Python example:
    // z^3 + P_res*z^2 + Q_res*z + R_res = 0 (Note: Python used different Q_res and R_res names for the cubic itself)
    // Python's `single_cubic_one(1, p, r, p*r - 0.5*q*q)`
    // where p = 3*a02 - 0.5*b  (P_res in Kotlin)
    //       q = a*a02 - b*a0 + 0.5*c (Q_res_ferrari in Kotlin)
    //       r = 3*a02*a02 - b*a02 + c*a0 - d (R_res_ferrari in Kotlin)
    val P_res_cubic = 3.0 * pCoeffASq - 0.5 * b
    val Q_res_ferrari = a * pCoeffASq - b * pCoeffA + 0.5 * cQuart
    val R_res_ferrari = 3.0 * pCoeffASq * pCoeffASq - b * pCoeffASq + cQuart * pCoeffA - dQuart

    // Coefficients for the resolvent cubic z^3 + c2*z^2 + c1*z + c0 = 0
    // Matching Python: single_cubic_one(1, p, r, p*r - 0.5*q*q)
    // c2_cubic = P_res_cubic
    // c1_cubic = R_res_ferrari
    // c0_cubic = P_res_cubic * R_res_ferrari - 0.5 * Q_res_ferrari * Q_res_ferrari
    val z0Complex = solveCubicOneRealInternal(
        1.0,
        P_res_cubic,
        R_res_ferrari,
        P_res_cubic * R_res_ferrari - 0.5 * Q_res_ferrari * Q_res_ferrari
    )

    if (z0Complex.real.isNaN()) return emptyList() // Cubic solver failed for resolvent.
    val z0 = z0Complex.real // Ferrari's method needs a real root from the resolvent.

    // s = sqrt(2*P_res_cubic + 2*z0)
    val sArgComplex = Complex(2.0 * P_res_cubic + 2.0 * z0)
    val sValComplex = csqrt(sArgComplex)

    val tValComplex: Complex
    if (sValComplex.abs() < DOUBLE_EPSILON) {
        // t = z0^2 + R_res_ferrari (if s is zero)
        tValComplex = Complex(z0 * z0 + R_res_ferrari)
    } else {
        // t = -Q_res_ferrari / s
        tValComplex = Complex(-Q_res_ferrari) / sValComplex
    }

    // Roots from two quadratics: x^2 +/- s*x + (z0 +/- t) = 0, then shift by -pCoeffA
    val rootsQ1 = solveQuadraticComplexCoeff(Complex.ONE, sValComplex, z0.toComplex() + tValComplex)
    val rootsQ2 = solveQuadraticComplexCoeff(Complex.ONE, -sValComplex, z0.toComplex() - tValComplex)

    val shift = Complex(-pCoeffA)
    return (rootsQ1 + rootsQ2).map { it + shift }
}



fun calculateCollisionTime(
    targetPosition: Vector2f, targetVelocity: Vector2f, targetRadius: Float,
    projectileLocation: Vector2f, projectileVelocity: Vector2f, projectileAccel: Vector2f, projectileMaxSpeed: Float, projectileRadius: Float
): Float? {
    val R_sum = targetRadius + projectileRadius
    val R_sum_sq = R_sum * R_sum
    val s_max = if (projectileMaxSpeed < 0f) 0f else projectileMaxSpeed
    val s_max_sq = s_max * s_max

    // Initial overlap check at t=0
    val deltaP0AtT0 = projectileLocation - targetPosition // Using your Vector2f extension
    if (deltaP0AtT0.lengthSquared() <= R_sum_sq + FLOAT_EPSILON_SQ) {
        return 0.0f // Already overlapping or touching
    }

    // --- Step 1: Calculate time ts for Object 2 to reach S_max (Corrected Logic) ---
    val ts: Float
    val v2_0_speed_sq = projectileVelocity.lengthSquared()

    if (projectileAccel.lengthSquared() < FLOAT_EPSILON_SQ) { // No effective acceleration
        ts = if (v2_0_speed_sq > s_max_sq + FLOAT_EPSILON_SQ) { // Starts faster than S_max
            0.0f // Will cap immediately
        } else { // Starts slower or at S_max, no accel to change that towards s_max
            Float.POSITIVE_INFINITY
        }
    } else { // Has acceleration
        val aTsD = projectileAccel.lengthSquared().toDouble() // A for quadratic t^2
        val bTsD = (2 * projectileVelocity.dot(projectileAccel)).toDouble() // B for quadratic t
        val cTsD = (v2_0_speed_sq - s_max_sq).toDouble() // C for quadratic

        val rootsTsComplex = solveQuadraticInternal(aTsD, bTsD, cTsD)

        val validTsCandidates = rootsTsComplex
            .filter { it.imag.isApproximatelyZero(FLOAT_EPSILON_COMPLEX_IMAG) && it.real >= -DOUBLE_EPSILON } // Non-negative real root
            .map { it.real.toFloat() }
            .sorted()

        if (validTsCandidates.isEmpty()) {
            // No non-negative real root to reach s_max.
            // If C_ts_d > 0, it means v2_0_speed_sq > s_max_sq (started faster).
            // If it's accelerating further or not decelerating enough to reach s_max, ts effectively 0 for capping.
            ts = if (cTsD > 0) 0.0f else Float.POSITIVE_INFINITY
        } else {
            ts = validTsCandidates.first() // Smallest non-negative time to reach/cross s_max
        }
    }

    // --- Step 2: Check for collision in Phase 1 (0 <= t <= ts) ---
    var t_coll1: Float? = null
    val delta_P0 = projectileLocation - targetPosition
    val delta_V0 = projectileVelocity - targetVelocity
    val A_rel_eff = projectileAccel * 0.5f

    // Quartic coeffs for || delta_P0 + delta_V0*t + A_rel_eff*t^2 ||^2 - R_sum_sq = 0
    val c4 = A_rel_eff.lengthSquared().toDouble()
    val c3 = (2 * A_rel_eff.dot(delta_V0)).toDouble()
    val c2 = (delta_V0.lengthSquared() + 2 * A_rel_eff.dot(delta_P0)).toDouble()
    val c1 = (2 * delta_V0.dot(delta_P0)).toDouble()
    val c0 = (delta_P0.lengthSquared() - R_sum_sq).toDouble()

    val rootsPhase1Complex = solveQuarticInternal(c4, c3, c2, c1, c0)
    t_coll1 = rootsPhase1Complex
        .filter { it.imag.isApproximatelyZero(FLOAT_EPSILON_COMPLEX_IMAG) }
        .map { it.real.toFloat() }
        .filter { it >= -FLOAT_EPSILON && it <= ts + FLOAT_EPSILON } // Valid time range for phase 1
        .minOrNull()


    // --- Step 3: Check for collision in Phase 2 (t' > 0, where t = ts + t') ---
    var t_coll2_abs: Float? = null
    if (ts < Float.POSITIVE_INFINITY) {
        val p1_at_ts = targetPosition + (targetVelocity * ts)
        val v2_at_ts_uncapped = projectileVelocity + (projectileAccel * ts)

        var v2_s = v2_at_ts_uncapped.copy() // Start with the velocity at ts
        if (s_max < FLOAT_EPSILON) { // Speed limit is effectively zero
            v2_s = Vector2f(0f, 0f)
        } else {
            val currentSpeedSq = v2_s.lengthSquared()
            // Tolerance for comparing s_max_sq, can be relative if s_max_sq is large
            val sMaxSqTolerance = FLOAT_EPSILON_SQ * max(1.0f, if (s_max_sq > FLOAT_EPSILON_SQ) s_max_sq else 1.0f)

            var shouldCap = false
            // Condition 1: ts is a calculated time (non-negative and finite) where speed should be s_max.
            // If current speed at ts is not s_max (within tolerance), it needs capping.
            if (ts >= -FLOAT_EPSILON && ts < Float.POSITIVE_INFINITY) {
                if (abs(currentSpeedSq - s_max_sq) > sMaxSqTolerance) {
                    shouldCap = true
                }
            }
            // Condition 2: ts is 0.0 because it started over the speed limit.
            // The speed at ts (which is v2_0) should be capped.
            // (ts == 0.0f implies v2_0_speed_sq >= s_max_sq - FLOAT_EPSILON_SQ based on ts calculation)
            // If v2_0_speed_sq was strictly greater, it needs capping.
            // If v2_0_speed_sq was approx s_max_sq, currentSpeedSq should be s_max_sq, no cap needed by this branch.
            else if (ts.isApproximatelyZero(FLOAT_EPSILON) && v2_0_speed_sq > s_max_sq + FLOAT_EPSILON_SQ) {
                shouldCap = true // Explicitly started over speed limit
            }


            if (shouldCap) {
                if (currentSpeedSq > FLOAT_EPSILON_SQ) { // Avoid normalizing zero vector
                    v2_s = v2_s.normalized() * s_max
                } else { // Current speed is zero, but s_max isn't. It remains zero.
                    v2_s = Vector2f(0f, 0f)
                }
            }
        }

        val p2_at_ts = projectileLocation + (projectileVelocity * ts) + (A_rel_eff * (ts * ts))

        val deltaPPrime0 = p2_at_ts - p1_at_ts
        val deltaVPrime = v2_s - targetVelocity

        val aP2D = deltaVPrime.lengthSquared().toDouble()
        val bP2D = (2 * deltaPPrime0.dot(deltaVPrime)).toDouble()
        val cP2D = (deltaPPrime0.lengthSquared() - R_sum_sq).toDouble()

        val rootsPhase2PrimeComplex = solveQuadraticInternal(aP2D, bP2D, cP2D)
        val firstValidTPrime = rootsPhase2PrimeComplex
            .filter { it.imag.isApproximatelyZero(FLOAT_EPSILON_COMPLEX_IMAG) }
            .map { it.real.toFloat() }
            .filter { it >= -FLOAT_EPSILON } // t_prime must be non-negative
            .minOrNull()

        if (firstValidTPrime != null) {
            t_coll2_abs = ts + firstValidTPrime
        }
    }

    // --- Step 4: Determine Final Collision Time ---
    val candidates = mutableListOf<Float>()
    if (t_coll1 != null && t_coll1 >= -FLOAT_EPSILON) candidates.add(t_coll1)
    if (t_coll2_abs != null && t_coll2_abs >= -FLOAT_EPSILON) candidates.add(t_coll2_abs)

    return if (candidates.isEmpty()) {
        null
    } else {
        val minT = candidates.minOrNull()!! // Not null due to isEmpty check
        if (minT < -FLOAT_EPSILON) null else minT // Ensure truly non-negative or null
    }
}

fun calculate1DInterceptTime(
    distance: Float,
    closingVelocity: Float,
    missileStartingVelocity: Float,
    missileMaxVelocity: Float,
    missileAccel: Float
): Float {
    val targetVelocity = missileStartingVelocity - closingVelocity

    if (missileStartingVelocity >= missileMaxVelocity) {
        val currentClosingVelocity = missileMaxVelocity - targetVelocity

        return if (currentClosingVelocity <= 0) Float.MAX_VALUE else distance / currentClosingVelocity
    }

    // Sub-scenario 2b: Missile starts below max speed and accelerates.
    val timeToReachMaxSpeed = (missileMaxVelocity - missileStartingVelocity) / missileAccel

    val quad_A = 0.5f * missileAccel
    val quad_B = closingVelocity
    val quad_C = -distance

    val discriminant = quad_B * quad_B - 4 * quad_A * quad_C // Should be: closingVelocity^2 + 2 * missileAccel * distance
    val timeIfAcceleratingContinuously = (-quad_B + sqrt(discriminant)) / (2 * quad_A)
    if (timeIfAcceleratingContinuously <= timeToReachMaxSpeed) {
        return timeIfAcceleratingContinuously
    } else {
        val distanceCoveredByMissileDuringAccel = missileStartingVelocity * timeToReachMaxSpeed +
                0.5f * missileAccel * timeToReachMaxSpeed * timeToReachMaxSpeed

        val distanceCoveredByTargetDuringMissileAccel = targetVelocity * timeToReachMaxSpeed
        val remainingDistance = (distance + distanceCoveredByTargetDuringMissileAccel) - distanceCoveredByMissileDuringAccel

        if (remainingDistance <= 0) return timeToReachMaxSpeed

        val closingVelocityDuringCruise = missileMaxVelocity - targetVelocity

        if (closingVelocityDuringCruise <= 0) return Float.MAX_VALUE

        val timeToCoverRemainingDistanceAtCruise = remainingDistance / closingVelocityDuringCruise

        return timeToReachMaxSpeed + timeToCoverRemainingDistanceAtCruise
    }
}

/**
 * Calculates the speed at which two objects are moving towards or away from each other.
 *
 * @param p1 Position of object 1.
 * @param v1 Velocity of object 1.
 * @param p2 Position of object 2.
 * @param v2 Velocity of object 2.
 * @return The closing or separation speed.
 * - Negative value: Objects are moving towards each other (closing).
 * - Positive value: Objects are moving away from each other (separating).
 * - Zero: Objects' relative motion is perpendicular to the line connecting them,
 * or they are at the same position and the speed is considered 0 by this formula.
 */
fun calculateClosingOrSeparationSpeed(
    p1: Vector2f, v1: Vector2f,
    p2: Vector2f, v2: Vector2f
): Float {
    val displacement = p2 - p1
    val relativeVelocity = v2 - v1
    val distance = displacement.length()

    if (distance.isApproximatelyZero()) {
        return 0.0f
    }

    val dotProduct = relativeVelocity.dot(displacement)

    return dotProduct / distance
}
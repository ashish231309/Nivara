package com.nivara.app.domain.credential

/**
 * Converts a drawn pattern into the single canonical form everything else works with.
 *
 * ### Why canonicalisation is needed
 *
 * The same drawing gesture can produce different raw touch sequences: a finger may report the
 * same dot twice, and a straight line from one corner to the opposite corner passes over the
 * middle dot without ever touching it. If the raw sequence were derived from, two visually
 * identical drawings could produce different protection values and the user would be locked out
 * of a pattern they drew correctly.
 *
 * ### The rule
 *
 * The grid is 3x3, indexed row by row:
 *
 * ```
 *  0 1 2
 *  3 4 5
 *  6 7 8
 * ```
 *
 * A raw sequence is converted as follows, matching how Android's own pattern lock records a
 * drawing:
 *
 * 1. A point outside the grid makes the sequence invalid.
 * 2. A point equal to the previous point is ignored (a repeated touch, not a second visit).
 * 3. A point already connected earlier in the drawing makes the sequence invalid: the drawing
 *    connects each point at most once.
 * 4. When the move from the previous point to the next one crosses the centre of an *unvisited*
 *    grid point — for example `0 -> 2` crosses `1`, and `0 -> 8` crosses `4` — that crossed
 *    point is inserted before the next point, because the line visibly passes through it.
 *
 * ### Encoding
 *
 * The canonical form is one character per connected point, in visit order, with the characters
 * `'0'`–`'8'` naming the points: a drawing through the top row left to right is `"012"`, and the
 * diagonal from the top-left corner to the bottom-right corner is `"048"`.
 *
 * This encoding is the credential that is passed to the key-derivation function, which is why it
 * is documented precisely: reproducing it in a later stage must be possible from this text alone.
 * It is never written to storage and never logged. Joining the drawing into a `String` would make
 * it impossible to clear, so the characters are produced directly into a `CharArray`.
 */
object PatternCanonicalizer {

    /** Side of the grid. [POINT_COUNT] is `GRID_SIZE * GRID_SIZE`. */
    const val GRID_SIZE: Int = 3

    /** Number of points on the grid. */
    const val POINT_COUNT: Int = GRID_SIZE * GRID_SIZE

    /** Longest possible drawing: every point, plus the crossed points inserted on the way. */
    private const val MAXIMUM_OUTPUT_LENGTH: Int = POINT_COUNT * 2

    private const val NO_MIDPOINT: Int = -1
    private const val NO_POINT: Int = -1

    /**
     * Canonicalises [touched], or returns `null` when the sequence cannot be a drawing on the
     * grid (a point outside it, or a point connected twice).
     *
     * The caller owns the returned array and is responsible for clearing it.
     */
    fun canonicalize(touched: IntArray): CharArray? {
        if (touched.isEmpty()) return null

        val canonical = CharArray(MAXIMUM_OUTPUT_LENGTH)
        var length = 0
        var previous = NO_POINT

        for (point in touched) {
            if (point < 0 || point >= POINT_COUNT) return null

            // 2. Touching the same dot again is the same dot, not a new connection.
            if (point == previous) continue

            // 3. The drawing connects each point at most once.
            if (contains(canonical, length, point)) return null

            // 4. A line crossing an unvisited point visits it.
            val crossed = crossedPoint(previous, point)
            if (crossed != NO_MIDPOINT && !contains(canonical, length, crossed)) {
                canonical[length] = encode(crossed)
                length++
            }

            canonical[length] = encode(point)
            length++
            previous = point
        }

        if (length == 0) return null
        return canonical.copyOf(length)
    }

    /**
     * The grid point whose centre lies on the line between [from] and [to], or [NO_MIDPOINT].
     *
     * A centre exists exactly when the two points are an even number of rows and an even number
     * of columns apart: `0 -> 2` has one midpoint (`1`), `0 -> 8` has one (`4`), while `0 -> 1`
     * and `0 -> 4` pass through no grid point at all.
     */
    private fun crossedPoint(from: Int, to: Int): Int {
        if (from == NO_POINT) return NO_MIDPOINT
        val rowSum = from / GRID_SIZE + to / GRID_SIZE
        val columnSum = from % GRID_SIZE + to % GRID_SIZE
        if (rowSum % 2 != 0 || columnSum % 2 != 0) return NO_MIDPOINT
        return (rowSum / 2) * GRID_SIZE + columnSum / 2
    }

    private fun contains(points: CharArray, length: Int, point: Int): Boolean {
        val encoded = encode(point)
        for (index in 0 until length) {
            if (points[index] == encoded) return true
        }
        return false
    }

    private fun encode(point: Int): Char = '0' + point
}

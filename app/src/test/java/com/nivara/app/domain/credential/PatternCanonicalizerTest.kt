package com.nivara.app.domain.credential

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for pattern canonicalisation.
 *
 * The rule has to be exactly reproducible: if two identical drawings could produce two different
 * canonical forms, a user could enrol a pattern and then be unable to unlock with it.
 *
 * The grid is indexed row by row:
 *
 * ```
 *  0 1 2
 *  3 4 5
 *  6 7 8
 * ```
 */
class PatternCanonicalizerTest {

    @Test
    fun `encodes the touched points in visit order`() {
        val canonical = PatternCanonicalizer.canonicalize(intArrayOf(0, 4, 8))

        assertArrayEquals("048".toCharArray(), canonical)
    }

    @Test
    fun `inserts the point a straight line crosses`() {
        // Drawing straight across the top row passes over the middle dot.
        assertArrayEquals("012".toCharArray(), PatternCanonicalizer.canonicalize(intArrayOf(0, 2)))
        // The diagonal from corner to corner passes over the centre.
        assertArrayEquals("048".toCharArray(), PatternCanonicalizer.canonicalize(intArrayOf(0, 8)))
        // A vertical jump down the middle column passes over the centre.
        assertArrayEquals("147".toCharArray(), PatternCanonicalizer.canonicalize(intArrayOf(1, 7)))
    }

    @Test
    fun `does not insert a point the line does not cross`() {
        // Neighbouring dots have no grid point between them.
        assertArrayEquals("01".toCharArray(), PatternCanonicalizer.canonicalize(intArrayOf(0, 1)))
        assertArrayEquals("04".toCharArray(), PatternCanonicalizer.canonicalize(intArrayOf(0, 4)))
        // A knight move crosses no centre: the row and column distances are odd.
        assertArrayEquals("07".toCharArray(), PatternCanonicalizer.canonicalize(intArrayOf(0, 7)))
    }

    @Test
    fun `ignores a repeated touch on the same point`() {
        assertArrayEquals("012".toCharArray(), PatternCanonicalizer.canonicalize(intArrayOf(0, 0, 0, 1, 2)))
    }

    @Test
    fun `produces the same canonical form for different traces of one drawing`() {
        val traced = PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 2, 5, 8))
        val tracedWithExtraTouches = PatternCanonicalizer.canonicalize(intArrayOf(0, 0, 1, 1, 2, 5, 5, 8))
        val tracedWithCrossedCentre = PatternCanonicalizer.canonicalize(intArrayOf(0, 2, 5, 8))

        assertArrayEquals(traced, tracedWithExtraTouches)
        // The second trace adds the crossed centre to the first one, so it is a *different*
        // drawing, but each of them is stable for its own gesture.
        assertArrayEquals("01258".toCharArray(), traced)
        assertArrayEquals("01258".toCharArray(), tracedWithCrossedCentre)
    }

    @Test
    fun `rejects a drawing that connects the same point twice`() {
        assertNull(PatternCanonicalizer.canonicalize(intArrayOf(0, 4, 0)))
        assertNull(PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 2, 1)))
    }

    @Test
    fun `rejects points outside the grid`() {
        assertNull(PatternCanonicalizer.canonicalize(intArrayOf(0, 9)))
        assertNull(PatternCanonicalizer.canonicalize(intArrayOf(-1)))
        assertNull(PatternCanonicalizer.canonicalize(intArrayOf(0, 4, 12)))
    }

    @Test
    fun `rejects an empty drawing`() {
        assertNull(PatternCanonicalizer.canonicalize(intArrayOf()))
    }

    @Test
    fun `covers every point of the grid`() {
        // The longest simple drawing visits all nine points; each step here crosses nothing.
        val canonical = PatternCanonicalizer.canonicalize(intArrayOf(0, 1, 2, 4, 3, 5, 6, 7, 8))

        assertArrayEquals("012435678".toCharArray(), canonical)
    }
}

package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderTest {
    private fun applyMoves(current: List<String>, moves: List<Pair<Int, Int>>): List<String> {
        val cur = current.toMutableList()
        for ((from, before) in moves) cur.add(before, cur.removeAt(from))
        return cur
    }

    private fun applyBefore(current: List<String>, steps: List<Pair<String, String>>): List<String> {
        val cur = current.toMutableList()
        for ((x, y) in steps) { cur.remove(x); cur.add(cur.indexOf(y), x) }
        return cur
    }

    @Test fun movesBeforeReachTheDesiredOrderWithTheSameFewestMoves() {
        val current = listOf("a", "b", "c", "d", "e")
        for (desired in listOf(listOf("e", "c", "a", "d", "b"), listOf("b", "c", "d", "e", "a"), listOf("e", "d", "c", "b", "a"), current)) {
            val steps = Reorder.movesBefore(current, desired)
            assertEquals(Reorder.moves(current, desired).size, steps.size)
            val cur = current.toMutableList()
            for ((key, before) in steps) { cur.remove(key); if (before == null) cur.add(key) else cur.add(cur.indexOf(before), key) }
            assertEquals(desired, cur)
        }
        // The first item to the end: one move, with no item to go before.
        assertEquals(listOf("a" to null), Reorder.movesBefore(current, listOf("b", "c", "d", "e", "a")))
    }

    @Test fun movesReachTheDesiredOrder() {
        val current = listOf("a", "b", "c", "d", "e")
        val desired = listOf("e", "c", "a", "d", "b")
        val moves = Reorder.moves(current, desired)
        assertEquals(desired, applyMoves(current, moves))
    }

    @Test fun noMovesWhenAlreadyInOrder() {
        val l = listOf("a", "b", "c")
        assertTrue(Reorder.moves(l, l).isEmpty())
        assertTrue(Reorder.before(l, l).isEmpty())
    }

    @Test fun movesSkipWhatEitherSideLacks() {
        // "zzz" is not in the target and "x" is not in the wanted order: the known keys still end
        // up in the wanted relative order, and nothing is lost.
        val current = listOf("x", "a", "b", "c")
        val desired = listOf("c", "b", "a", "zzz")
        val result = applyMoves(current, Reorder.moves(current, desired))
        assertEquals(listOf("c", "b", "a"), result.filter { it != "x" })
        assertEquals(current.toSet(), result.toSet())
    }

    @Test fun oneMoveForOneDisplacedItem() {
        val current = listOf("a", "b", "c", "d")
        val desired = listOf("a", "c", "d", "b")
        val moves = Reorder.moves(current, desired)
        assertEquals(1, moves.size)
        assertEquals(desired, applyMoves(current, moves))
        // Spotify wants the insertion point before the removal: the end of a four-item list is 4.
        assertEquals(4, Reorder.insertBefore(moves[0].first, moves[0].second))
    }

    @Test fun insertBeforeMatchesSpotifysSemantics() {
        // Moving backwards: the index is the same before and after the removal.
        assertEquals(1, Reorder.insertBefore(3, 1))
        // Moving forwards: one more, since the removal shifts what follows.
        assertEquals(3, Reorder.insertBefore(0, 2))
    }

    @Test fun beforeStepsReachTheDesiredOrder() {
        val current = listOf("a", "b", "c", "d", "e")
        val desired = listOf("e", "c", "a", "d", "b")
        val steps = Reorder.before(current, desired)
        assertEquals(desired, applyBefore(current, steps))
    }

    @Test fun beforeStepsIgnoreUnknownKeys() {
        val current = listOf("a", "b", "c")
        val desired = listOf("c", "q", "a", "b")
        assertEquals(listOf("c", "a", "b"), applyBefore(current, Reorder.before(current, desired)))
    }
}

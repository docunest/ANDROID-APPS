package com.docunest.dock

import kotlin.math.abs

/**
 * The Dock keyboard's keys and how the iDrive controller moves between them
 * (pure, unit-tested). Rows are 10 units wide; a key has a position and
 * width in units. Pushing the controller moves to the neighbouring key
 * (up/down: the nearest key in the next row); turning it steps to the
 * next / previous key, row by row.
 */
object KeyGrid {
    enum class Kind { CHAR, BACKSPACE, SHIFT, ENTER, SPACE, PAGE, SIDE, HIDE }

    data class Key(val label: String, val kind: Kind, val x: Float, val w: Float, val text: String = label) {
        val center get() = x + w / 2
    }

    private fun chars(s: String, start: Float = 0f): List<Key> = s.mapIndexed { i, c -> Key(c.toString(), Kind.CHAR, start + i, 1f) }

    /** Letters page: digits always there (coordinates, house numbers), QWERTY, comma / dot. */
    val LETTERS: List<List<Key>> = listOf(
        chars("1234567890"),
        chars("qwertyuiop"),
        chars("asdfghjkl", 0f) + Key("⌫", Kind.BACKSPACE, 9f, 1f),
        listOf(Key("⇧", Kind.SHIFT, 0f, 1f)) + chars("zxcvbnm", 1f) + listOf(Key(",", Kind.CHAR, 8f, 1f), Key(".", Kind.CHAR, 9f, 1f)),
        listOf(
            Key("?#á", Kind.PAGE, 0f, 1.5f), Key("⇄", Kind.SIDE, 1.5f, 1f), Key("space", Kind.SPACE, 2.5f, 3.5f, " "),
            Key("-", Kind.CHAR, 6f, 1f), Key("⏎", Kind.ENTER, 7f, 1.5f), Key("▾", Kind.HIDE, 8.5f, 1.5f),
        ),
    )

    /** Symbols page: punctuation for addresses / coordinates and Iberian accents. */
    val SYMBOLS: List<List<Key>> = listOf(
        chars("1234567890"),
        chars("-/:;()°&@\""),
        chars("áéíóúãõçñ") + Key("⌫", Kind.BACKSPACE, 9f, 1f),
        listOf(Key("⇧", Kind.SHIFT, 0f, 1f)) + chars("àâêôü'!?", 1f) + listOf(Key("#", Kind.CHAR, 9f, 1f)),
        listOf(
            Key("abc", Kind.PAGE, 0f, 1.5f), Key("⇄", Kind.SIDE, 1.5f, 1f), Key("space", Kind.SPACE, 2.5f, 3.5f, " "),
            Key(",", Kind.CHAR, 6f, 1f), Key("⏎", Kind.ENTER, 7f, 1.5f), Key("▾", Kind.HIDE, 8.5f, 1.5f),
        ),
    )

    /** A key's place: row, index in the row. */
    data class Pos(val row: Int, val col: Int)

    enum class Dir { UP, DOWN, LEFT, RIGHT }

    /** Push the controller: neighbour in that direction (up/down = nearest by centre in the next row; no wrap). */
    fun move(page: List<List<Key>>, p: Pos, d: Dir): Pos {
        val row = page[p.row]
        return when (d) {
            Dir.LEFT -> if (p.col > 0) Pos(p.row, p.col - 1) else if (p.row > 0) Pos(p.row - 1, page[p.row - 1].lastIndex) else p
            Dir.RIGHT -> if (p.col < row.lastIndex) Pos(p.row, p.col + 1) else if (p.row < page.lastIndex) Pos(p.row + 1, 0) else p
            Dir.UP, Dir.DOWN -> {
                val r = p.row + if (d == Dir.UP) -1 else 1
                if (r !in page.indices) return p
                val cx = row[p.col].center
                Pos(r, page[r].indices.minByOrNull { abs(page[r][it].center - cx) }!!)
            }
        }
    }

    /** Turn the controller: next / previous key in reading order, wrapping round. */
    fun step(page: List<List<Key>>, p: Pos, forward: Boolean): Pos {
        val flat = page.flatMapIndexed { r, row -> row.indices.map { Pos(r, it) } }
        val i = flat.indexOf(p).coerceAtLeast(0)
        return flat[(i + if (forward) 1 else flat.size - 1) % flat.size]
    }

    /** Where the highlight starts: "g", the middle of the keyboard. */
    fun home(page: List<List<Key>>): Pos {
        val r = 2.coerceAtMost(page.lastIndex)
        return Pos(r, ((page[r].size - 1) / 2).coerceAtMost(page[r].lastIndex))
    }
}

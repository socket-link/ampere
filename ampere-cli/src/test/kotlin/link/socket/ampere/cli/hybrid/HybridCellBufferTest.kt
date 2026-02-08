package link.socket.ampere.cli.hybrid

import link.socket.ampere.cli.layout.ParsedCell
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HybridCellTest {

    @Test
    fun `default cell is space with no color at layer 0`() {
        val cell = HybridCell()
        assertEquals(' ', cell.char)
        assertNull(cell.ansiColor)
        assertEquals(0, cell.layer)
    }
}

class HybridCellBufferTest {

    @Test
    fun `clear produces all-space buffer`() {
        val buffer = HybridCellBuffer(5, 3)
        buffer.clear()
        for (y in 0 until 3) {
            for (x in 0 until 5) {
                val cell = buffer.getCell(x, y)
                assertEquals(' ', cell.char)
                assertNull(cell.ansiColor)
            }
        }
    }

    @Test
    fun `writeChar places character at position`() {
        val buffer = HybridCellBuffer(10, 5)
        buffer.clear()
        buffer.writeChar(3, 2, 'X', "\u001B[31m", layer = 1)

        val cell = buffer.getCell(3, 2)
        assertEquals('X', cell.char)
        assertEquals("\u001B[31m", cell.ansiColor)
        assertEquals(1, cell.layer)
    }

    @Test
    fun `writeChar ignores out-of-bounds`() {
        val buffer = HybridCellBuffer(5, 3)
        buffer.clear()
        // Should not throw
        buffer.writeChar(-1, 0, 'X')
        buffer.writeChar(5, 0, 'X')
        buffer.writeChar(0, -1, 'X')
        buffer.writeChar(0, 3, 'X')
    }

    @Test
    fun `higher layer overwrites lower layer`() {
        val buffer = HybridCellBuffer(10, 5)
        buffer.clear()
        buffer.writeChar(0, 0, 'A', null, layer = 0)
        buffer.writeChar(0, 0, 'B', null, layer = 1)

        assertEquals('B', buffer.getCell(0, 0).char)
    }

    @Test
    fun `lower layer does not overwrite higher layer`() {
        val buffer = HybridCellBuffer(10, 5)
        buffer.clear()
        buffer.writeChar(0, 0, 'A', null, layer = 2)
        buffer.writeChar(0, 0, 'B', null, layer = 1)

        assertEquals('A', buffer.getCell(0, 0).char)
    }

    @Test
    fun `same layer overwrites`() {
        val buffer = HybridCellBuffer(10, 5)
        buffer.clear()
        buffer.writeChar(0, 0, 'A', null, layer = 1)
        buffer.writeChar(0, 0, 'B', null, layer = 1)

        assertEquals('B', buffer.getCell(0, 0).char)
    }

    @Test
    fun `writeString places multiple characters`() {
        val buffer = HybridCellBuffer(10, 3)
        buffer.clear()
        buffer.writeString(2, 1, "abc", "\u001B[32m", layer = 1)

        assertEquals('a', buffer.getCell(2, 1).char)
        assertEquals('b', buffer.getCell(3, 1).char)
        assertEquals('c', buffer.getCell(4, 1).char)
        assertEquals("\u001B[32m", buffer.getCell(2, 1).ansiColor)
    }

    @Test
    fun `writePaneRegion writes parsed cells at offset`() {
        val buffer = HybridCellBuffer(10, 5)
        buffer.clear()

        val rows = listOf(
            listOf(ParsedCell('H', "\u001B[31m"), ParsedCell('i', "\u001B[31m")),
            listOf(ParsedCell('!', null), ParsedCell(' ', null))
        )

        buffer.writePaneRegion(3, 1, rows, layer = 1)

        assertEquals('H', buffer.getCell(3, 1).char)
        assertEquals("\u001B[31m", buffer.getCell(3, 1).ansiColor)
        assertEquals('i', buffer.getCell(4, 1).char)
        assertEquals('!', buffer.getCell(3, 2).char)
        assertNull(buffer.getCell(3, 2).ansiColor)
    }

    @Test
    fun `writePaneRegion clips to buffer bounds`() {
        val buffer = HybridCellBuffer(5, 3)
        buffer.clear()

        val rows = listOf(
            listOf(ParsedCell('A', null), ParsedCell('B', null), ParsedCell('C', null))
        )

        // Writing at x=4 with 3 chars: only first fits
        buffer.writePaneRegion(4, 0, rows, layer = 1)
        assertEquals('A', buffer.getCell(4, 0).char)
        // Remaining are out of bounds - buffer unchanged
        assertEquals(' ', buffer.getCell(4, 1).char)
    }

    @Test
    fun `renderFull contains cursor positioning`() {
        val buffer = HybridCellBuffer(3, 2)
        buffer.clear()
        buffer.writeChar(0, 0, 'A')
        buffer.writeChar(1, 0, 'B')

        val output = buffer.renderFull()
        assertTrue(output.contains("\u001B[1;1H")) // Row 1 positioning
        assertTrue(output.contains("\u001B[2;1H")) // Row 2 positioning
        assertTrue(output.contains("AB"))
    }

    @Test
    fun `renderFull includes ANSI colors`() {
        val buffer = HybridCellBuffer(3, 1)
        buffer.clear()
        buffer.writeChar(0, 0, 'X', "\u001B[31m")

        val output = buffer.renderFull()
        assertTrue(output.contains("\u001B[31m"))
        assertTrue(output.contains("X"))
        assertTrue(output.contains("\u001B[0m"))
    }

    @Test
    fun `renderDiff falls back to renderFull on first frame`() {
        val buffer = HybridCellBuffer(3, 1)
        buffer.clear()
        buffer.writeChar(0, 0, 'A')

        val diff = buffer.renderDiff()
        val full = buffer.renderFull()
        // First diff should be equivalent to full render
        assertEquals(full, diff)
    }

    @Test
    fun `renderDiff produces empty string when nothing changed`() {
        val buffer = HybridCellBuffer(3, 2)
        buffer.clear()
        buffer.writeChar(0, 0, 'A')
        buffer.swapBuffers()

        // Same content
        buffer.clear()
        buffer.writeChar(0, 0, 'A')

        val diff = buffer.renderDiff()
        assertTrue(diff.isEmpty(), "Diff should be empty when nothing changed, got: $diff")
    }

    @Test
    fun `renderDiff produces output only for changed cells`() {
        val buffer = HybridCellBuffer(5, 2)
        buffer.clear()
        buffer.writeString(0, 0, "ABCDE")
        buffer.swapBuffers()

        // Change only one cell
        buffer.clear()
        buffer.writeString(0, 0, "ABXDE")

        val diff = buffer.renderDiff()
        assertTrue(diff.contains("X"))
        // Should contain cursor positioning for the changed cell
        assertTrue(diff.contains("\u001B[1;3H")) // Row 1, Col 3 (0-indexed x=2)
        // Should NOT contain unchanged chars A, B, D, E as individual writes
        // (they may appear in cursor positioning codes, so just check diff is short)
        assertTrue(diff.length < buffer.renderFull().length)
    }

    @Test
    fun `swapBuffers preserves state for next diff`() {
        val buffer = HybridCellBuffer(3, 1)
        buffer.clear()
        buffer.writeChar(0, 0, 'A')
        buffer.swapBuffers()

        buffer.clear()
        buffer.writeChar(0, 0, 'B')

        val diff = buffer.renderDiff()
        // Should detect the change from A to B
        assertTrue(diff.contains("B"))
    }

    @Test
    fun `renderFull runs color codes efficiently`() {
        val buffer = HybridCellBuffer(3, 1)
        buffer.clear()
        // Three cells with same color - should only emit color once
        buffer.writeChar(0, 0, 'A', "\u001B[31m", layer = 0)
        buffer.writeChar(1, 0, 'B', "\u001B[31m", layer = 0)
        buffer.writeChar(2, 0, 'C', "\u001B[31m", layer = 0)

        val output = buffer.renderFull()
        // The color code should appear once, not three times
        val colorCount = output.split("\u001B[31m").size - 1
        assertEquals(1, colorCount, "Same consecutive color should be emitted once")
    }
}

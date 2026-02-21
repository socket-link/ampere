package link.socket.ampere.cli.render

import link.socket.ampere.animation.render.AsciiCell
import link.socket.ampere.cli.hybrid.HybridCellBuffer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaveformCellAdapterTest {

    private val adapter = WaveformCellAdapter()

    @Test
    fun `toAnsiColor returns null for default cell`() {
        val cell = AsciiCell()
        assertNull(WaveformCellAdapter.toAnsiColor(cell))
    }

    @Test
    fun `toAnsiColor produces 256-color foreground code`() {
        val cell = AsciiCell(char = '#', fgColor = 196)
        val color = WaveformCellAdapter.toAnsiColor(cell)
        assertNotNull(color)
        assertEquals("\u001B[38;5;196m", color)
    }

    @Test
    fun `toAnsiColor includes bold when set`() {
        val cell = AsciiCell(char = '@', fgColor = 220, bold = true)
        val color = WaveformCellAdapter.toAnsiColor(cell)
        assertNotNull(color)
        assertTrue(color.contains("1;"), "Expected bold prefix in: $color")
        assertTrue(color.contains("38;5;220"), "Expected fg color in: $color")
    }

    @Test
    fun `toAnsiColor includes background when set`() {
        val cell = AsciiCell(char = '*', fgColor = 117, bgColor = 232)
        val color = WaveformCellAdapter.toAnsiColor(cell)
        assertNotNull(color)
        assertTrue(color.contains("38;5;117"), "Expected fg color in: $color")
        assertTrue(color.contains("48;5;232"), "Expected bg color in: $color")
    }

    @Test
    fun `writeToBuffer places cells at correct offset`() {
        val buffer = HybridCellBuffer(20, 10)
        buffer.clear()

        val cells = Array(2) { row ->
            Array(3) { col ->
                AsciiCell(char = ('A' + row * 3 + col), fgColor = 196)
            }
        }

        adapter.writeToBuffer(cells, buffer, offsetX = 5, offsetY = 3)

        // Verify cells are placed at the offset
        assertEquals('A', buffer.getCell(5, 3).char)
        assertEquals('B', buffer.getCell(6, 3).char)
        assertEquals('C', buffer.getCell(7, 3).char)
        assertEquals('D', buffer.getCell(5, 4).char)
        assertEquals('E', buffer.getCell(6, 4).char)
        assertEquals('F', buffer.getCell(7, 4).char)
    }

    @Test
    fun `writeToBuffer applies ANSI color from fgColor`() {
        val buffer = HybridCellBuffer(10, 5)
        buffer.clear()

        val cells = Array(1) { Array(1) { AsciiCell(char = '#', fgColor = 196) } }
        adapter.writeToBuffer(cells, buffer, offsetX = 0, offsetY = 0)

        val cell = buffer.getCell(0, 0)
        assertEquals('#', cell.char)
        assertNotNull(cell.ansiColor)
        assertTrue(cell.ansiColor!!.contains("38;5;196"))
    }

    @Test
    fun `writeToBuffer skips empty cells`() {
        val buffer = HybridCellBuffer(10, 5)
        buffer.clear()
        buffer.writeChar(2, 0, 'Z', "\u001B[31m", layer = 0)

        // AsciiCell with default char=' ' and fgColor=7 should be skipped
        val cells = Array(1) { Array(5) { AsciiCell.EMPTY } }
        adapter.writeToBuffer(cells, buffer, offsetX = 0, offsetY = 0, layer = 1)

        // 'Z' at layer 0 should still be there since empty cells were skipped
        assertEquals('Z', buffer.getCell(2, 0).char)
    }

    @Test
    fun `writeToBuffer clips to buffer bounds`() {
        val buffer = HybridCellBuffer(5, 3)
        buffer.clear()

        // 4x4 grid at offset 3,1 — only first 2 cols and 2 rows fit
        val cells = Array(4) { row ->
            Array(4) { col ->
                AsciiCell(char = 'X', fgColor = 196)
            }
        }

        adapter.writeToBuffer(cells, buffer, offsetX = 3, offsetY = 1)

        assertEquals('X', buffer.getCell(3, 1).char)
        assertEquals('X', buffer.getCell(4, 1).char)
        assertEquals('X', buffer.getCell(3, 2).char)
        assertEquals('X', buffer.getCell(4, 2).char)
        // Out of bounds cells are unaffected
        assertEquals(' ', buffer.getCell(0, 0).char)
    }

    @Test
    fun `writeToBuffer respects layer parameter`() {
        val buffer = HybridCellBuffer(10, 5)
        buffer.clear()
        // Pre-fill at layer 2
        buffer.writeChar(0, 0, 'Z', null, layer = 2)

        val cells = Array(1) { Array(1) { AsciiCell(char = 'A', fgColor = 196) } }

        // Write at layer 1 — should NOT overwrite layer 2
        adapter.writeToBuffer(cells, buffer, offsetX = 0, offsetY = 0, layer = 1)
        assertEquals('Z', buffer.getCell(0, 0).char)

        // Write at layer 2 — should overwrite
        adapter.writeToBuffer(cells, buffer, offsetX = 0, offsetY = 0, layer = 2)
        assertEquals('A', buffer.getCell(0, 0).char)
    }
}

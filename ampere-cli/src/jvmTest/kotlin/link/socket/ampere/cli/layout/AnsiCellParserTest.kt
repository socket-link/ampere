package link.socket.ampere.cli.layout

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnsiCellParserTest {

    @Test
    fun `plain text produces cells with null color`() {
        val cells = AnsiCellParser.parseLine("hello")
        assertEquals(5, cells.size)
        assertEquals('h', cells[0].char)
        assertEquals('e', cells[1].char)
        assertEquals('l', cells[2].char)
        assertEquals('l', cells[3].char)
        assertEquals('o', cells[4].char)
        cells.forEach { assertNull(it.ansiPrefix) }
    }

    @Test
    fun `empty string returns empty list`() {
        val cells = AnsiCellParser.parseLine("")
        assertTrue(cells.isEmpty())
    }

    @Test
    fun `single ANSI color wrapping text`() {
        // "\u001B[31mHi\u001B[0m"
        val input = "\u001B[31mHi\u001B[0m"
        val cells = AnsiCellParser.parseLine(input)
        assertEquals(2, cells.size)
        assertEquals('H', cells[0].char)
        assertEquals("\u001B[31m", cells[0].ansiPrefix)
        assertEquals('i', cells[1].char)
        assertEquals("\u001B[31m", cells[1].ansiPrefix)
    }

    @Test
    fun `reset clears color state`() {
        val input = "\u001B[31mA\u001B[0mB"
        val cells = AnsiCellParser.parseLine(input)
        assertEquals(2, cells.size)
        assertEquals('A', cells[0].char)
        assertEquals("\u001B[31m", cells[0].ansiPrefix)
        assertEquals('B', cells[1].char)
        assertNull(cells[1].ansiPrefix)
    }

    @Test
    fun `256 color codes are captured`() {
        val input = "\u001B[38;5;45mX\u001B[0m"
        val cells = AnsiCellParser.parseLine(input)
        assertEquals(1, cells.size)
        assertEquals('X', cells[0].char)
        assertEquals("\u001B[38;5;45m", cells[0].ansiPrefix)
    }

    @Test
    fun `color changes mid-string`() {
        val input = "\u001B[31mR\u001B[32mG\u001B[34mB\u001B[0m"
        val cells = AnsiCellParser.parseLine(input)
        assertEquals(3, cells.size)
        assertEquals("\u001B[31m", cells[0].ansiPrefix)
        assertEquals("\u001B[32m", cells[1].ansiPrefix)
        assertEquals("\u001B[34m", cells[2].ansiPrefix)
    }

    @Test
    fun `parseLineToWidth truncates long lines`() {
        val cells = AnsiCellParser.parseLineToWidth("abcdef", 3)
        assertEquals(3, cells.size)
        assertEquals('a', cells[0].char)
        assertEquals('b', cells[1].char)
        assertEquals('c', cells[2].char)
    }

    @Test
    fun `parseLineToWidth pads short lines`() {
        val cells = AnsiCellParser.parseLineToWidth("ab", 5)
        assertEquals(5, cells.size)
        assertEquals('a', cells[0].char)
        assertEquals('b', cells[1].char)
        assertEquals(' ', cells[2].char)
        assertEquals(' ', cells[3].char)
        assertEquals(' ', cells[4].char)
        // Padding cells have no color
        assertNull(cells[2].ansiPrefix)
    }

    @Test
    fun `parseLineToWidth returns exact width`() {
        val cells = AnsiCellParser.parseLineToWidth("abc", 3)
        assertEquals(3, cells.size)
    }

    @Test
    fun `parseLineToWidth truncates with ANSI codes correctly`() {
        val input = "\u001B[31mHello World\u001B[0m"
        val cells = AnsiCellParser.parseLineToWidth(input, 5)
        assertEquals(5, cells.size)
        assertEquals('H', cells[0].char)
        assertEquals('o', cells[4].char)
        cells.forEach { assertEquals("\u001B[31m", it.ansiPrefix) }
    }

    @Test
    fun `stripAnsi removes all escape codes`() {
        val input = "\u001B[31mHello\u001B[0m \u001B[38;5;45mWorld\u001B[0m"
        assertEquals("Hello World", AnsiCellParser.stripAnsi(input))
    }

    @Test
    fun `stripAnsi returns plain text unchanged`() {
        assertEquals("hello", AnsiCellParser.stripAnsi("hello"))
    }

    @Test
    fun `short reset sequence is handled`() {
        val input = "\u001B[31mA\u001B[mB"
        val cells = AnsiCellParser.parseLine(input)
        assertEquals(2, cells.size)
        assertEquals("\u001B[31m", cells[0].ansiPrefix)
        assertNull(cells[1].ansiPrefix)
    }

    @Test
    fun `bold and dim codes are captured`() {
        val input = "\u001B[1mBold\u001B[2mDim\u001B[0m"
        val cells = AnsiCellParser.parseLine(input)
        assertEquals(7, cells.size)
        assertEquals("\u001B[1m", cells[0].ansiPrefix)
        assertEquals("\u001B[2m", cells[4].ansiPrefix)
    }

    @Test
    fun `consecutive escape sequences with no chars between`() {
        // Two color codes then text
        val input = "\u001B[1m\u001B[31mX\u001B[0m"
        val cells = AnsiCellParser.parseLine(input)
        assertEquals(1, cells.size)
        assertEquals('X', cells[0].char)
        // The last color code before the character wins
        assertEquals("\u001B[31m", cells[0].ansiPrefix)
    }
}

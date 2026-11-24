package me.minecraftauth.minecraft

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

class TextTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        textBlock = """
        "&cRed &Lbold & stuff &z" | "§cRed §lbold & stuff &z"
        "no codes"                | "no codes"
        ""                        | ""
        "&"                       | "&"
        "trailing &"              | "trailing &"
        "&c"                      | "§c"
        "&&c"                     | "&§c"
        "&&&c"                    | "&&§c"
        "&c&l"                    | "§c§l"
        "&g&h&x&#&&"              | "&g&h&x&#&&"
        "&R&K&O"                  | "§r§k§o"
        "§c already"              | "§c already"
        "a & b"                   | "a & b"
        "&0&9&a&f&k&o&r"          | "§0§9§a§f§k§o§r"""",
    )
    fun `colorize translates only valid codes`(input: String, expected: String) {
        assertEquals(expected, Text.colorize(input))
    }

    @Test
    fun `denied results are colorized`() {
        assertEquals("§cno &zway", GatekeeperResult.denied("&cno &zway").message)
    }
}

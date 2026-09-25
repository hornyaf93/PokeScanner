package com.yourname.pokescanner.core.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextParserTest {

    private val parser = OcrTextParser(maximumPlausibleYear = 2030)

    @Test
    fun `extracts name and standard collector fraction`() {
        val result = parse(
            """
            BASIC Pikachu HP 60
            Thunder Shock
            023/189
            ©2020 Pokémon
            """.trimIndent(),
            lines = listOf(
                line("BASIC Pikachu HP 60", top = 40, bottom = 80),
                line("Thunder Shock", top = 280, bottom = 315),
                line("023/189", top = 850, bottom = 880),
            ),
        )

        assertEquals("Pikachu", result.cardName)
        assertEquals("023", result.collectorNumber?.cardNumber)
        assertEquals("189", result.collectorNumber?.totalSetCards)
        assertEquals(CollectorNumberKind.SET_FRACTION, result.collectorNumber?.kind)
        assertEquals(setOf(2020), result.modifiers.yearStamps)
    }

    @Test
    fun `supports subset letters and spaces around slash`() {
        val result = parse(
            raw = "Galarian Gallery GG01 / GG70",
            lines = listOf(line("GG01 / GG70", top = 850, bottom = 880)),
        )

        assertEquals("GG01", result.collectorNumber?.cardNumber)
        assertEquals("GG70", result.collectorNumber?.totalSetCards)
    }

    @Test
    fun `keeps printed set code separate from collector fraction`() {
        val result = parse(
            raw = "Miriam SV1EN 251/198 ©2023 Pokémon",
            lines = listOf(
                line("Miriam", top = 40, bottom = 80),
                line("SV1EN 251/198", top = 850, bottom = 880),
            ),
        )

        assertEquals("Miriam", result.cardName)
        assertEquals("251", result.collectorNumber?.cardNumber)
        assertEquals("198", result.collectorNumber?.totalSetCards)
        assertEquals("SV1EN", result.setCode)
        assertFalse(result.modifiers.isPromo)
    }

    @Test
    fun `extracts standalone promo number and explicit modifiers`() {
        val result = parse(
            raw = """
                Darkrai Lv. X
                BLACK STAR PROMO
                SWSH001
                ©2019 Pokémon
                1st Edition
            """.trimIndent(),
            lines = listOf(
                line("Darkrai Lv. X", top = 40, bottom = 80),
                line("SWSH001", top = 850, bottom = 880),
            ),
        )

        assertEquals("SWSH001", result.collectorNumber?.cardNumber)
        assertEquals("Darkrai", result.cardName)
        assertNull(result.collectorNumber?.totalSetCards)
        assertEquals(CollectorNumberKind.PROMO_CODE, result.collectorNumber?.kind)
        assertTrue(result.modifiers.isPromo)
        assertTrue(result.modifiers.hasLevelMarker)
        assertTrue(result.modifiers.hasFirstEditionText)
        assertEquals(setOf(2019), result.modifiers.yearStamps)
    }

    @Test
    fun `recognises abbreviated first edition text`() {
        val result = parse(
            raw = "Machamp 1st Ed. 8/102 ©1999 Wizards",
            lines = listOf(
                line("Machamp", top = 40, bottom = 80),
                line("1st Ed. 8/102", top = 850, bottom = 880),
            ),
        )

        assertTrue(result.modifiers.hasFirstEditionText)
    }

    @Test
    fun `does not invent first edition from a normal numbered card`() {
        val result = parse(
            raw = "Charizard 4/102 ©1999 Wizards",
            lines = listOf(
                line("Charizard", top = 40, bottom = 80),
                line("4/102", top = 850, bottom = 880),
            ),
        )

        assertFalse(result.modifiers.hasFirstEditionText)
    }

    @Test
    fun `does not treat hit points as a collector number`() {
        val result = parse(
            raw = "BASIC Eevee 120 HP\nTackle 30",
            lines = listOf(line("BASIC Eevee 120 HP", top = 40, bottom = 80)),
        )

        assertEquals("Eevee", result.cardName)
        assertNull(result.collectorNumber)
    }

    private fun parse(
        raw: String,
        lines: List<RecognisedTextLine>,
    ): CardRecognitionResult = parser.parse(
        rawText = raw,
        lines = lines,
        imageWidth = 700,
        imageHeight = 1_000,
    )

    private fun line(
        text: String,
        top: Int,
        bottom: Int,
    ) = RecognisedTextLine(
        text = text,
        left = 20,
        top = top,
        right = 620,
        bottom = bottom,
    )
}

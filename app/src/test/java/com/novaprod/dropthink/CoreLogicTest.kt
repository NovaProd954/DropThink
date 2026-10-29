package com.novaprod.dropthink

import com.novaprod.dropthink.util.Calc
import com.novaprod.dropthink.util.extractUrls
import com.novaprod.dropthink.util.looksRepetitive
import com.novaprod.dropthink.util.parseQuant
import com.novaprod.dropthink.util.splitThinking
import com.novaprod.dropthink.util.trimOverlap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    @Test fun quantFromFileName() {
        assertEquals("UD-Q5_K_XL", parseQuant("Qwen3.5-0.8B-UD-Q5_K_XL.gguf"))
        assertEquals("Q4_K_M", parseQuant("model-Q4_K_M.gguf"))
        assertEquals("", parseQuant("model.gguf"))
    }

    @Test fun thinkingSplit() {
        val a = splitThinking("reasoning here</think>\n\nAnswer", true)
        assertEquals("reasoning here", a.reasoning)
        assertEquals("Answer", a.answer)
        assertEquals("plain", splitThinking("plain", false).answer)
        assertTrue(splitThinking("still thinking", true).inThink)
    }

    @Test fun calculator() {
        assertEquals("14", Calc.format(Calc.eval("2+3*4")))
        assertEquals("512", Calc.format(Calc.eval("2^3^2")))
        assertEquals("2.5", Calc.format(Calc.eval("(1+4)/2")))
        assertEquals("3", Calc.format(Calc.eval("sqrt(9)")))
    }

    @Test fun overlapTrim() {
        val prev = "The quick brown fox jumps over the lazy dog near the river bank."
        val next = "jumps over the lazy dog near the river bank. Then it slept."
        assertEquals(" Then it slept.", trimOverlap(prev, next))
        assertEquals("fresh text", trimOverlap(prev, "fresh text"))
    }

    @Test fun repetition() {
        assertTrue(looksRepetitive("abc def ghi. ".repeat(60)))
        assertFalse(looksRepetitive("A normal sentence that does not repeat itself in any meaningful way at all."))
    }

    @Test fun urls() {
        assertEquals(listOf("https://a.com/x", "https://b.org"), extractUrls("see https://a.com/x, and (https://b.org)."))
    }
}

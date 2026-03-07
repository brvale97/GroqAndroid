package com.groqandroid

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for word replacement regex logic.
 * Tests special characters, case sensitivity, Unicode, and edge cases.
 */
class WordReplacementTest {

    /**
     * Applies replacements using the same logic as GroqIME.applyReplacements.
     */
    private fun applyReplacements(text: String, replacements: List<Pair<String, String>>): String {
        var result = text
        for ((from, to) in replacements) {
            val escaped = Regex.escape(from)
            val prefix = if (from.first().isLetterOrDigit() || from.first() == '_') "\\b" else ""
            val suffix = if (from.last().isLetterOrDigit() || from.last() == '_') "\\b" else ""
            val pattern = Regex("$prefix$escaped$suffix", RegexOption.IGNORE_CASE)
            result = pattern.replace(result, Regex.escapeReplacement(to))
        }
        return result
    }

    @Test
    fun `simple word replacement`() {
        val result = applyReplacements("hello world", listOf("hello" to "hi"))
        assertEquals("hi world", result)
    }

    @Test
    fun `case insensitive replacement`() {
        val result = applyReplacements("Hello HELLO hello", listOf("hello" to "hi"))
        assertEquals("hi hi hi", result)
    }

    @Test
    fun `word boundary prevents partial matches`() {
        val result = applyReplacements("helloworld hello world", listOf("hello" to "hi"))
        // "helloworld" should NOT be replaced, only standalone "hello"
        assertEquals("helloworld hi world", result)
    }

    @Test
    fun `replacement with period in from pattern`() {
        val result = applyReplacements("Use Dr. Smith for reference", listOf("Dr." to "Doctor"))
        // Regex.escape handles the dot
        assertEquals("Use Doctor Smith for reference", result)
    }

    @Test
    fun `replacement with parentheses in pattern`() {
        val result = applyReplacements("call func() now", listOf("func()" to "function()"))
        assertEquals("call function() now", result)
    }

    @Test
    fun `replacement with backslash in pattern`() {
        val result = applyReplacements("path\\to\\file", listOf("path\\to\\file" to "new/path"))
        assertEquals("new/path", result)
    }

    @Test
    fun `replacement with asterisk in pattern`() {
        val result = applyReplacements("pointer* is ready", listOf("pointer*" to "ptr"))
        assertEquals("ptr is ready", result)
    }

    @Test
    fun `multiple replacements applied sequentially`() {
        val replacements = listOf(
            "hello" to "hi",
            "world" to "earth"
        )
        val result = applyReplacements("hello world", replacements)
        assertEquals("hi earth", result)
    }

    @Test
    fun `empty replacement list returns original text`() {
        val result = applyReplacements("hello world", emptyList())
        assertEquals("hello world", result)
    }

    @Test
    fun `replacement to empty string removes word`() {
        val result = applyReplacements("remove this word", listOf("this" to ""))
        assertEquals("remove  word", result)
    }

    @Test
    fun `Dutch word replacement`() {
        val result = applyReplacements("Ik zeg hallo tegen je", listOf("hallo" to "hoi"))
        assertEquals("Ik zeg hoi tegen je", result)
    }

    @Test
    fun `replacement does not affect numbers`() {
        val result = applyReplacements("test 123 test", listOf("test" to "exam"))
        assertEquals("exam 123 exam", result)
    }

    @Test
    fun `replacement with dollar sign in target`() {
        val result = applyReplacements("price is USD", listOf("USD" to "$"))
        assertEquals("price is $", result)
    }

    @Test
    fun `no match returns original text unchanged`() {
        val result = applyReplacements("hello world", listOf("foo" to "bar"))
        assertEquals("hello world", result)
    }
}

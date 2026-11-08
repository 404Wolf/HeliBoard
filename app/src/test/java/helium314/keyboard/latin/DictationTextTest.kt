// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.dictation.DictationCommand
import helium314.keyboard.latin.dictation.DictationCommandParser
import helium314.keyboard.latin.dictation.DictationFormatter
import helium314.keyboard.latin.dictation.FormattedText
import helium314.keyboard.latin.dictation.ParsedUtterance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// plain JVM test, DictationText.kt has no Android dependencies
class DictationTextTest {
    private fun parse(s: String, partial: Boolean = false) = DictationCommandParser.parse(s, partial = partial)

    @Test fun `plain text passes through`() {
        assertEquals(ParsedUtterance("hello world"), parse("hello world"))
        assertEquals(ParsedUtterance("Hello, world."), parse("  Hello,   world. "))
        assertEquals(ParsedUtterance(""), parse("   "))
    }

    @Test fun `whole utterance commands`() {
        assertEquals(ParsedUtterance("", DictationCommand.DELETE_LAST), parse("delete that"))
        assertEquals(ParsedUtterance("", DictationCommand.DELETE_LAST), parse("Scratch that."))
        assertEquals(ParsedUtterance("", DictationCommand.UNDO), parse("Undo"))
        assertEquals(ParsedUtterance("", DictationCommand.SEND), parse("send"))
        assertEquals(ParsedUtterance("", DictationCommand.SEND), parse("Send it!"))
        assertEquals(ParsedUtterance("", DictationCommand.STOP_LISTENING), parse("stop listening"))
    }

    @Test fun `commands inside text are text`() {
        assertEquals(ParsedUtterance("I will send it tomorrow"), parse("I will send it tomorrow"))
        assertEquals(ParsedUtterance("please delete that file"), parse("please delete that file"))
        assertEquals(ParsedUtterance("undo the change"), parse("undo the change"))
    }

    @Test fun `partial results never run whole utterance commands`() {
        assertEquals(ParsedUtterance("send"), parse("send", partial = true))
        assertEquals(ParsedUtterance("stop listening"), parse("stop listening", partial = true))
    }

    @Test fun `trailing stop listening`() {
        assertEquals(ParsedUtterance("that's all", DictationCommand.STOP_LISTENING), parse("that's all stop listening"))
    }

    @Test fun `spoken punctuation`() {
        assertEquals("hello, world.", parse("hello comma world period").text)
        assertEquals("are you there?", parse("are you there question mark").text)
        assertEquals("wow!", parse("wow exclamation point").text)
        assertEquals("wow!", parse("wow exclamation mark").text)
        assertEquals("note: apples; pears.", parse("note colon apples semicolon pears full stop").text)
        assertEquals(".", parse("period").text)
        assertEquals(",", parse("Comma").text)
    }

    @Test fun `recognizer punctuation on command words is not doubled`() {
        assertEquals("hello.", parse("hello. Period.").text)
        assertEquals("hello?", parse("hello, question mark").text)
    }

    @Test fun `punctuation words after determiners are literal`() {
        assertEquals("a period of time", parse("a period of time").text)
        assertEquals("the colon is an organ", parse("the colon is an organ").text)
        assertEquals("trial period.", parse("trial period period").text)
    }

    @Test fun `line breaks`() {
        assertEquals("first\nsecond", parse("first new line second").text)
        assertEquals("done.\n\nsecond", parse("done period new paragraph second").text)
        assertEquals("\n", parse("new line").text)
        assertEquals("\n\n", parse("New paragraph.").text)
        assertEquals("read the next line", parse("read the next line").text)
    }

    @Test fun `commands disabled`() {
        assertEquals(ParsedUtterance("delete that"), DictationCommandParser.parse("delete that", commandsEnabled = false))
        assertEquals(ParsedUtterance("hello period"), DictationCommandParser.parse("hello period", commandsEnabled = false))
    }

    private fun format(before: String, body: String, autoCap: Boolean = true, autoSpace: Boolean = true) =
        DictationFormatter.format(before, body, autoCap, autoSpace)

    @Test fun `capitalize at sentence start`() {
        assertEquals(FormattedText("Hello"), format("", "hello"))
        assertEquals(FormattedText(" How are you"), format("Hi.", "how are you"))
        assertEquals(FormattedText("Next"), format("line\n", "next"))
        assertEquals(FormattedText("Hi"), format("Done! ", "hi"))
        assertEquals(FormattedText("hello"), format("", "hello", autoCap = false))
    }

    @Test fun `lowercase continuing function word`() {
        assertEquals(FormattedText(" and then"), format("I went home", "And then"))
        assertEquals(FormattedText(" John came"), format("I went home with", "John came"))
        assertEquals(FormattedText(" I think"), format("well", "I think"))
        assertEquals(FormattedText(" NASA"), format("I like", "NASA"))
    }

    @Test fun `spacing`() {
        assertEquals(FormattedText(" world"), format("hello", "world"))
        assertEquals(FormattedText("world"), format("hello ", "world"))
        assertEquals(FormattedText("world"), format("(", "world"))
        assertEquals(FormattedText("world"), format("hello", "world", autoSpace = false))
        assertEquals(FormattedText(".", 1), format("hello ", "."))
        assertEquals(FormattedText(", then", 0), format("hello", ", then"))
        assertEquals(FormattedText("\n", 2), format("hello  ", "\n"))
    }

    @Test fun `capitalize inside body after inserted punctuation`() {
        assertEquals(FormattedText("Hello. How are you?"), format("", "hello. how are you?"))
        assertEquals(FormattedText("A\nB"), format("", "a\nb"))
        assertEquals(FormattedText("hello. how"), format("", "hello. how", autoCap = false))
    }

    @Test fun `sentence start detection`() {
        assertTrue(DictationFormatter.isSentenceStart(""))
        assertTrue(DictationFormatter.isSentenceStart("Done.  "))
        assertTrue(DictationFormatter.isSentenceStart("He said \"hi.\" "))
        assertFalse(DictationFormatter.isSentenceStart("hello, "))
        assertFalse(DictationFormatter.isSentenceStart("hello"))
    }
}

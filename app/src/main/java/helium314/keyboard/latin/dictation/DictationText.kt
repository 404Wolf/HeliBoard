// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

/**
 * Pure text logic for in-keyboard dictation: voice command parsing and
 * spacing / capitalization of recognized utterances. No Android dependencies,
 * so it is unit tested directly (DictationTextTest).
 */

/** A command that acts on the editor instead of inserting text. */
enum class DictationCommand {
    /** "delete that", "scratch that": remove the last dictated utterance. */
    DELETE_LAST,
    /** "undo": revert the last dictation edit (or the editor's last edit). */
    UNDO,
    /** "send": perform the editor action (send, go, search, done...). */
    SEND,
    /** "stop listening": end the dictation session. */
    STOP_LISTENING,
}

/**
 * The result of parsing one utterance.
 * [text] is the body to insert (inline commands like "comma" or "new line"
 * already applied, without context-dependent leading space or capitalization),
 * [command] is run after inserting [text].
 */
data class ParsedUtterance(val text: String, val command: DictationCommand? = null)

object DictationCommandParser {
    // whole-utterance commands: the utterance must be exactly one of these
    private val wholeCommands = mapOf(
        "delete that" to DictationCommand.DELETE_LAST,
        "scratch that" to DictationCommand.DELETE_LAST,
        "delete last" to DictationCommand.DELETE_LAST,
        "erase that" to DictationCommand.DELETE_LAST,
        "undo" to DictationCommand.UNDO,
        "undo that" to DictationCommand.UNDO,
        "send" to DictationCommand.SEND,
        "send it" to DictationCommand.SEND,
        "send message" to DictationCommand.SEND,
        "stop listening" to DictationCommand.STOP_LISTENING,
        "stop dictation" to DictationCommand.STOP_LISTENING,
    )

    // commands that may also end a longer utterance ("that's all stop listening")
    private val trailingCommands = listOf(
        listOf("stop", "listening") to DictationCommand.STOP_LISTENING,
        listOf("stop", "dictation") to DictationCommand.STOP_LISTENING,
    )

    private sealed interface Inline {
        val text: String
    }
    private data class Punct(override val text: String) : Inline
    private data class Break(override val text: String) : Inline

    // inline commands, longest phrases first
    private val inlineCommands: List<Pair<List<String>, Inline>> = listOf(
        "new paragraph" to Break("\n\n"),
        "new line" to Break("\n"),
        "newline" to Break("\n"),
        "question mark" to Punct("?"),
        "exclamation point" to Punct("!"),
        "exclamation mark" to Punct("!"),
        "full stop" to Punct("."),
        "semi colon" to Punct(";"),
        "period" to Punct("."),
        "comma" to Punct(","),
        "semicolon" to Punct(";"),
        "colon" to Punct(":"),
    ).map { (phrase, inline) -> phrase.split(' ') to inline }.sortedByDescending { it.first.size }

    // after these words a punctuation word is meant literally ("a period of time", "the colon")
    private val literalAfter = setOf(
        "a", "an", "the", "this", "that", "these", "those", "each", "every", "one", "any", "some", "no",
        "my", "your", "his", "her", "our", "their", "its", "per", "same", "another", "first", "last",
        "next", "trial", "grace", "waiting", "time", "short", "long", "whole", "entire", "word", "say",
        "says", "said", "called", "of",
    )

    /**
     * Parse a recognized utterance.
     * @param commandsEnabled whether spoken commands are applied; if false, the text is passed through
     * @param partial whether this is a partial (streaming) result; whole-utterance commands are then
     *  shown as text, because the utterance may still continue
     */
    fun parse(utterance: String, commandsEnabled: Boolean = true, partial: Boolean = false): ParsedUtterance {
        val words = utterance.trim().split(whitespace).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ParsedUtterance("")
        if (!commandsEnabled) return ParsedUtterance(render(words.map { Word(it) }))

        val normalized = words.map { normalize(it) }
        if (!partial) {
            wholeCommands[normalized.joinToString(" ")]?.let { return ParsedUtterance("", it) }
        }

        var end = words.size
        var command: DictationCommand? = null
        if (!partial) {
            for ((phrase, cmd) in trailingCommands) {
                if (end > phrase.size && normalized.subList(end - phrase.size, end) == phrase) {
                    end -= phrase.size
                    command = cmd
                    break
                }
            }
        }

        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < end) {
            val match = inlineCommands.firstOrNull { (phrase, _) ->
                i + phrase.size <= end && normalized.subList(i, i + phrase.size) == phrase
            }
            if (match != null) {
                val (phrase, inline) = match
                val previous = tokens.lastOrNull()
                val literal = inline is Punct && previous is Word && normalize(previous.text) in literalAfter
                if (!literal) {
                    // the recognizer may already have put punctuation on the word we read as command,
                    // e.g. "period." -> drop it, and keep the punctuation the command stands for
                    tokens.add(if (inline is Punct) PunctToken(inline.text) else BreakToken(inline.text))
                    i += phrase.size
                    continue
                }
            }
            tokens.add(Word(words[i]))
            i++
        }
        return ParsedUtterance(render(tokens), command)
    }

    private sealed interface Token
    private data class Word(val text: String) : Token
    private data class PunctToken(val text: String) : Token
    private data class BreakToken(val text: String) : Token

    private fun render(tokens: List<Token>): String {
        val sb = StringBuilder()
        for (token in tokens) {
            when (token) {
                is Word -> {
                    if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
                    sb.append(token.text)
                }
                is PunctToken -> {
                    trimTrailingSpaces(sb)
                    // replace punctuation the recognizer already put there ("hello. period")
                    if (sb.isNotEmpty() && sb.last() in replaceablePunctuation) sb.setLength(sb.length - 1)
                    sb.append(token.text)
                }
                is BreakToken -> {
                    trimTrailingSpaces(sb)
                    sb.append(token.text)
                }
            }
        }
        return sb.toString()
    }

    private fun trimTrailingSpaces(sb: StringBuilder) {
        while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\t')) sb.setLength(sb.length - 1)
    }

    /** lowercase, without surrounding punctuation */
    private fun normalize(word: String) = word.lowercase().trim { !it.isLetterOrDigit() && it != '\'' }

    private val replaceablePunctuation = setOf('.', ',', ';', ':', '!', '?')

    private val whitespace = Regex("\\s+")
}

/** Text to put into the editor: first delete [deleteBefore] chars before the cursor, then insert [text]. */
data class FormattedText(val text: String, val deleteBefore: Int = 0)

object DictationFormatter {
    /**
     * Fit [body] (from [DictationCommandParser]) to the text before the cursor.
     * @param before text before the cursor (a few dozen chars are enough)
     * @param autoCap capitalize at sentence starts (keyboard's auto-cap setting and the field allow it)
     * @param autoSpace insert / remove spaces at the start (keyboard setting and field type allow it)
     */
    fun format(before: CharSequence, body: String, autoCap: Boolean, autoSpace: Boolean): FormattedText {
        if (body.isEmpty()) return FormattedText("")
        var deleteBefore = 0
        val first = body.first()
        val startsAttached = first in attachedPunctuation || first == '\n'
        if (autoSpace && startsAttached) {
            // punctuation / line break: no space before it
            var i = before.length
            while (i > 0 && (before[i - 1] == ' ' || before[i - 1] == '\t')) i--
            deleteBefore = before.length - i
        }
        val context = before.subSequence(0, before.length - deleteBefore)
        val needsSpace = autoSpace && !startsAttached && !first.isWhitespace() && context.isNotEmpty()
                && !context.last().isWhitespace() && context.last() !in openingChars
        val sentenceStart = isSentenceStart(context)

        val text = StringBuilder(body)
        val firstLetter = text.indexOfFirst { it.isLetter() }
        if (firstLetter >= 0 && text.substring(0, firstLetter).none { it in sentencePunctuation || it == '\n' }) {
            if (sentenceStart && autoCap) {
                text.setCharAt(firstLetter, text[firstLetter].uppercaseChar())
            } else if (!sentenceStart && context.isNotEmpty()) {
                // recognizers capitalize the start of every utterance, also when it continues a sentence
                val wordEnd = (firstLetter until text.length).firstOrNull { !text[it].isLetter() } ?: text.length
                val word = text.substring(firstLetter, wordEnd)
                if (word.lowercase() in lowercaseWhenContinuing && word == word.lowercase().replaceFirstChar { it.uppercaseChar() })
                    text.setCharAt(firstLetter, text[firstLetter].lowercaseChar())
            }
        }
        if (autoCap) {
            // capitalize after sentence ends inside the body ("hello period how are you" -> "hello. How are you")
            var capNext = false
            for (i in text.indices) {
                val c = text[i]
                if (c in sentencePunctuation || c == '\n') capNext = true
                else if (c.isLetter()) {
                    if (capNext && i > 0 && (text[i - 1].isWhitespace())) text.setCharAt(i, c.uppercaseChar())
                    capNext = false
                } else if (!c.isWhitespace()) capNext = false
            }
        }
        if (needsSpace) text.insert(0, ' ')
        return FormattedText(text.toString(), deleteBefore)
    }

    fun isSentenceStart(before: CharSequence): Boolean {
        var i = before.length
        while (i > 0 && (before[i - 1] == ' ' || before[i - 1] == '\t')) i--
        if (i == 0) return true
        val c = before[i - 1]
        if (c == '\n' || c in sentencePunctuation) return true
        // closing quote or bracket after sentence end: `He said "hi."`
        if ((c == '"' || c == ')' || c == '”') && i > 1 && before[i - 2] in sentencePunctuation) return true
        return false
    }

    private val openingChars = setOf('(', '[', '{', '“', '¿', '¡')
    private val attachedPunctuation = setOf('.', ',', '?', '!', ':', ';', ')', ']', '}', '…')

    private val lowercaseWhenContinuing = setOf(
        "and", "but", "or", "so", "then", "the", "a", "an", "to", "of", "in", "on", "at", "for", "with",
        "it", "it's", "that", "this", "is", "was", "are", "were", "be", "because", "if", "when", "which",
        "who", "what", "where", "how", "we", "you", "he", "she", "they", "my", "your", "our", "their",
        "not", "also", "just", "as", "from", "by", "about", "like", "maybe", "too", "there", "here",
    )
}

private val sentencePunctuation = setOf('.', '!', '?', '…')

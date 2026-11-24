/*
 * Copyright (C) 2026 8002Keys Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.ai

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.TextInfo
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.smartcompose.RewriteProvider
import dev.patrickgold.florisboard.ime.smartcompose.RewriteRequest
import dev.patrickgold.florisboard.ime.smartcompose.RewriteResult
import dev.patrickgold.florisboard.ime.smartcompose.RewriteTone
import dev.patrickgold.florisboard.ime.smartcompose.SensitiveFieldGuard
import dev.patrickgold.florisboard.ime.translate.InlineTranslator
import dev.patrickgold.florisboard.ime.translate.LanguagePairDescriptor
import dev.patrickgold.florisboard.ime.translate.TranslationResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

enum class AiWritingTask(
    val chipLabel: String,
    val systemDirective: String,
) {
    FIX_GRAMMAR_SPELLING(
        chipLabel = "Fix Grammar & Spell ✨",
        systemDirective = "Fix all grammar, spelling, punctuation, and capitalization errors in the user's text. " +
            "If the text is in English, return corrected natural English. If the text is in Roman Urdu, " +
            "clean up typos into natural readable Roman Urdu. Return ONLY the corrected text with no explanations.",
    ),
    IMPROVE_WRITING(
        chipLabel = "Polish & Improve",
        systemDirective = "Polish and improve the clarity, grammar, flow, and word choice of the user's text while " +
            "preserving its original meaning and language (English or Roman Urdu). Return ONLY the improved text.",
    ),
    ROMAN_URDU_TO_ENGLISH(
        chipLabel = "Roman Urdu → English",
        systemDirective = "Translate the user's Roman Urdu (or mixed Roman Urdu + English) text into fluent, " +
            "grammatically correct, natural English. Return ONLY the English translation.",
    ),
    ENGLISH_TO_ROMAN_URDU(
        chipLabel = "English → Roman Urdu",
        systemDirective = "Translate the user's English text into natural, conversational Pakistani Roman Urdu " +
            "(written in the Latin alphabet). Return ONLY the Roman Urdu text.",
    ),
    TO_URDU_SCRIPT(
        chipLabel = "To Urdu Script اردو",
        systemDirective = "Convert or translate the user's Roman Urdu or English text into proper Urdu Nastaliq " +
            "script (اردو) with accurate spelling and grammar. Return ONLY the Urdu script text.",
    ),
    TONE_PROFESSIONAL(
        chipLabel = "Professional",
        systemDirective = "Rewrite the user's text in a polished, polite, professional tone with spotless grammar. " +
            "If the input is Roman Urdu, you may polish it into professional English or formal Roman Urdu matching context. " +
            "Return ONLY the rewritten text.",
    ),
    TONE_CASUAL(
        chipLabel = "Casual / Friendly",
        systemDirective = "Rewrite the user's text in a warm, natural, friendly, conversational tone with clean spelling. " +
            "Return ONLY the rewritten text.",
    ),
    SHORTEN(
        chipLabel = "Shorter",
        systemDirective = "Make the user's text shorter, concise, and direct while keeping the full core message. " +
            "Return ONLY the shortened text.",
    ),
    EXPAND(
        chipLabel = "Expand",
        systemDirective = "Expand the user's text into a clearer, more complete version with smooth transitions and " +
            "proper grammar. Return ONLY the expanded text.",
    ),
    SMART_REPLY(
        chipLabel = "Smart Reply",
        systemDirective = "Write a helpful, natural, well-written reply to the given message in the same language " +
            "(English or Roman Urdu). Return ONLY the reply text ready to send.",
    ),
    CONTINUE_WRITING(
        chipLabel = "Continue Writing",
        systemDirective = "Continue writing from the user's text by adding 1-2 natural follow-up sentences in the " +
            "same style and language, returning the full combined text (original corrected + continuation). " +
            "Return ONLY the final text.",
    ),
    CUSTOM_PROMPT(
        chipLabel = "Ask AI ✨",
        systemDirective = "Follow the user's instruction accurately and produce text ready to paste or insert into " +
            "their active text editor. Return ONLY the final output text without markdown fences or meta-commentary.",
    ),
}

data class AiExecutionResult(
    val outputText: String,
    val providerUsed: String,
    val usedCloud: Boolean,
    val warningMessage: String? = null,
)

/**
 * Built-in offline English + Roman Urdu grammar, spelling, rewrite, and translation engine.
 *
 * Runs locally with zero latency for:
 *  1. Android's `FlorisSpellCheckerService.onGetSentenceSuggestionsMultiple` (real-time grammar/spell check).
 *  2. Instant fallback when offline or before the user logs in to Google Gemini / OpenAI ChatGPT.
 */
object LocalGrammarAndRewriteEngine {
    private const val MAX_MATCHES_PER_SENTENCE = 24
    private val cloudSentenceCache = ConcurrentHashMap<String, String>()

    data class GrammarMatch(
        val offset: Int,
        val length: Int,
        val replacement: String,
    )

    private val wordCorrections: Map<String, String> = mapOf(
        // Common English spelling & contraction fixes
        "teh" to "the",
        "recieve" to "receive",
        "seperate" to "separate",
        "definately" to "definitely",
        "occured" to "occurred",
        "untill" to "until",
        "wich" to "which",
        "becuase" to "because",
        "accomodate" to "accommodate",
        "enviroment" to "environment",
        "goverment" to "government",
        "tommorow" to "tomorrow",
        "wierd" to "weird",
        "beleive" to "believe",
        "acheive" to "achieve",
        "neccessary" to "necessary",
        "grammer" to "grammar",
        "grmammer" to "grammar",
        "featire" to "feature",
        "im" to "I'm",
        "ive" to "I've",
        "ill" to "I'll",
        "dont" to "don't",
        "cant" to "can't",
        "wont" to "won't",
        "didnt" to "didn't",
        "doesnt" to "doesn't",
        "isnt" to "isn't",
        "arent" to "aren't",
        "wasnt" to "wasn't",
        "werent" to "weren't",
        "shouldnt" to "shouldn't",
        "couldnt" to "couldn't",
        "wouldnt" to "wouldn't",
        "hasnt" to "hasn't",
        "havent" to "haven't",
        "hadnt" to "hadn't",
        "youre" to "you're",
        "theyre" to "they're",
        "thats" to "that's",
        "whats" to "what's",
        "heres" to "here's",
        "theres" to "there's",
        "alot" to "a lot",
        // Common Roman Urdu typo normalizations
        "mjy" to "mujhe",
        "mjhy" to "mujhe",
        "maany" to "maine",
        "isku" to "isko",
        "usku" to "usko",
        "krna" to "karna",
        "krdu" to "kar do",
        "krdia" to "kar diya",
        "rha" to "raha",
        "rhi" to "rahi",
        "rhy" to "rahe",
        "bht" to "bohat",
        "nhi" to "nahi",
        "kch" to "kuch",
        "smjh" to "samajh",
        "smh" to "samajh",
        "amsla" to "masla",
        "chahye" to "chahiye",
        "tkay" to "taake",
        "taky" to "taake",
        "whaan" to "wahan",
        "yhaan" to "yahan",
        "shukria" to "shukriya",
        "insallah" to "InshaAllah",
        "inshallah" to "InshaAllah",
        "mashallah" to "MashaAllah",
        "alhamdulillah" to "Alhamdulillah",
        "assalamualaikum" to "Assalamu Alaikum",
    )

    private data class PhraseRule(
        val regex: Regex,
        val replacement: (MatchResult) -> String,
    )

    private val phraseGrammarRules: List<PhraseRule> = listOf(
        PhraseRule(Regex("""\b(this|that)\s+are\b""", RegexOption.IGNORE_CASE)) { m ->
            preserveCase(m.value, "${m.groupValues[1]} is")
        },
        PhraseRule(Regex("""\b(these|those)\s+is\b""", RegexOption.IGNORE_CASE)) { m ->
            preserveCase(m.value, "${m.groupValues[1]} are")
        },
        PhraseRule(Regex("""\b(he|she|it)\s+don't\b""", RegexOption.IGNORE_CASE)) { m ->
            preserveCase(m.value, "${m.groupValues[1]} doesn't")
        },
        PhraseRule(Regex("""\b(he|she|it)\s+dont\b""", RegexOption.IGNORE_CASE)) { m ->
            preserveCase(m.value, "${m.groupValues[1]} doesn't")
        },
        PhraseRule(Regex("""\b(he|she|it)\s+have\b""", RegexOption.IGNORE_CASE)) { m ->
            preserveCase(m.value, "${m.groupValues[1]} has")
        },
        PhraseRule(Regex("""\b(you|we|they)\s+was\b""", RegexOption.IGNORE_CASE)) { m ->
            preserveCase(m.value, "${m.groupValues[1]} were")
        },
        PhraseRule(Regex("""\bi\s+is\b""", RegexOption.IGNORE_CASE)) {
            "I am"
        },
        PhraseRule(Regex("""\bi\s+has\b""", RegexOption.IGNORE_CASE)) {
            "I have"
        },
        PhraseRule(Regex("""\b(could|should|would|must|might)\s+of\b""", RegexOption.IGNORE_CASE)) { m ->
            preserveCase(m.value, "${m.groupValues[1]} have")
        },
        PhraseRule(Regex("""\ba\s+([aeiouAEIOU][a-zA-Z]{2,})\b""")) { m ->
            val word = m.groupValues[1]
            if (word.lowercase().startsWith("uni") || word.lowercase().startsWith("use") || word.lowercase().startsWith("one")) {
                m.value
            } else {
                "an $word"
            }
        },
        PhraseRule(Regex("""\ban\s+([bcdfghjklmnpqrstvwxyzBCDFGHJKLMNPQRSTVWXYZ][a-zA-Z]+)\b""")) { m ->
            val word = m.groupValues[1]
            if (word.lowercase().startsWith("hour") || word.lowercase().startsWith("honest") || word.lowercase().startsWith("heir")) {
                m.value
            } else {
                "a $word"
            }
        },
    )

    private val romanUrduToEnglishPhrases: List<Pair<Regex, String>> = listOf(
        Regex("""\bkia haal hai\b|\bkya haal hai\b|\bkesay ho\b|\bkaise ho\b""", RegexOption.IGNORE_CASE) to "How are you?",
        Regex("""\bmein theek hoon\b|\bmain theek hun\b|\balhamdulillah theek\b""", RegexOption.IGNORE_CASE) to "I am doing well, thank you.",
        Regex("""\bbohat shukriya\b|\bshukriya\b|\bshukria\b""", RegexOption.IGNORE_CASE) to "Thank you very much",
        Regex("""\bmujhe (ye|ya) chahiye\b|\bmjy (ye|ya) chahye\b""", RegexOption.IGNORE_CASE) to "I need this",
        Regex("""\bmujhe madad chahiye\b|\bmjy madad chahye\b""", RegexOption.IGNORE_CASE) to "I need help",
        Regex("""\bsamajh rahe ho na\b|\bsmh rhy ho na\b""", RegexOption.IGNORE_CASE) to "Do you understand what I mean?",
        Regex("""\bmeri baat\b|\bmeri bat\b""", RegexOption.IGNORE_CASE) to "my point",
        Regex("""\bmasla hal kar do\b|\bmasla theek karo\b""", RegexOption.IGNORE_CASE) to "please resolve the issue",
        Regex("""\bjaldi karo\b""", RegexOption.IGNORE_CASE) to "please hurry",
        Regex("""\btheek hai\b|\bsahi hai\b|\bsai hai\b""", RegexOption.IGNORE_CASE) to "okay, sounds good",
        Regex("""\bkab aoge\b|\bkab aaoge\b""", RegexOption.IGNORE_CASE) to "when will you arrive?",
        Regex("""\bkahan ho\b""", RegexOption.IGNORE_CASE) to "where are you?",
    )

    private val englishToRomanUrduPhrases: List<Pair<Regex, String>> = listOf(
        Regex("""\bhow are you\??\b""", RegexOption.IGNORE_CASE) to "Aap kaise hain?",
        Regex("""\bi am fine\b|\bi'm doing well\b""", RegexOption.IGNORE_CASE) to "Main bilkul theek hoon",
        Regex("""\bthank you very much\b|\bthanks a lot\b""", RegexOption.IGNORE_CASE) to "Bohat shukriya",
        Regex("""\bthank you\b|\bthanks\b""", RegexOption.IGNORE_CASE) to "Shukriya",
        Regex("""\bi need this\b""", RegexOption.IGNORE_CASE) to "Mujhe ye chahiye",
        Regex("""\bi need help\b|\bplease help me\b""", RegexOption.IGNORE_CASE) to "Mujhe madad chahiye",
        Regex("""\bwhere are you\??\b""", RegexOption.IGNORE_CASE) to "Aap kahan hain?",
        Regex("""\bwhat is the issue\??\b|\bwhat's the problem\??\b""", RegexOption.IGNORE_CASE) to "Kya masla hai?",
        Regex("""\bplease fix this\b""", RegexOption.IGNORE_CASE) to "Meharbani kar ke isko theek kar dein",
        Regex("""\bokay\b|\bsounds good\b""", RegexOption.IGNORE_CASE) to "Theek hai",
        Regex("""\bsee you tomorrow\b""", RegexOption.IGNORE_CASE) to "Kal milte hain",
    )

    private val romanToUrduScriptWords: Map<String, String> = mapOf(
        "assalamu" to "السلام",
        "alaikum" to "علیکم",
        "aap" to "آپ",
        "ap" to "آپ",
        "kaise" to "کیسے",
        "kesay" to "کیسے",
        "hain" to "ہیں",
        "ho" to "ہو",
        "main" to "میں",
        "mein" to "میں",
        "theek" to "ٹھیک",
        "hoon" to "ہوں",
        "hun" to "ہوں",
        "mujhe" to "مجھے",
        "mjy" to "مجھے",
        "ye" to "یہ",
        "ya" to "یہ",
        "chahiye" to "چاہیے",
        "chahye" to "چاہیے",
        "bohat" to "بہت",
        "bht" to "بہت",
        "shukriya" to "شکریہ",
        "shukria" to "شکریہ",
        "nahi" to "نہیں",
        "nhi" to "نہیں",
        "haan" to "ہاں",
        "han" to "ہاں",
        "kya" to "کیا",
        "kia" to "کیا",
        "haal" to "حال",
        "hai" to "ہے",
        "masla" to "مسئلہ",
        "kaam" to "کام",
        "kam" to "کام",
        "kar" to "کر",
        "karo" to "کرو",
        "karna" to "کرنا",
        "do" to "دو",
        "dein" to "دیں",
        "aur" to "اور",
        "mera" to "میرا",
        "meri" to "میری",
        "baat" to "بات",
        "bat" to "بات",
        "samajh" to "سمجھ",
        "raha" to "رہا",
        "rahi" to "رہی",
        "rahe" to "رہے",
        "inshaallah" to "ان شاء اللہ",
        "mashaallah" to "ما شاء اللہ",
        "alhamdulillah" to "الحمد للہ",
    )

    fun cacheCloudCorrection(original: String, corrected: String) {
        val cleanOrig = original.trim()
        val cleanCorr = corrected.trim()
        if (cleanOrig.isNotEmpty() && cleanCorr.isNotEmpty() && cleanOrig != cleanCorr && cleanOrig.length <= 400) {
            if (cloudSentenceCache.size > 200) {
                cloudSentenceCache.clear()
            }
            cloudSentenceCache[cleanOrig] = cleanCorr
        }
    }

    /**
     * Finds word- and phrase-level grammar/spelling matches within [text] for Android's
     * `SpellCheckerService.onGetSentenceSuggestionsMultiple`.
     */
    fun findSentenceGrammarMatches(text: String): List<GrammarMatch> {
        if (text.isBlank()) return emptyList()
        val matches = mutableListOf<GrammarMatch>()

        // 0. If the entire sentence was recently corrected by Gemini/ChatGPT, offer it if single-span or word diff
        val cached = cloudSentenceCache[text.trim()]
        if (cached != null && cached != text.trim() && text.length <= 120) {
            val trimOffset = text.indexOf(text.trim()).coerceAtLeast(0)
            matches += GrammarMatch(
                offset = trimOffset,
                length = text.trim().length,
                replacement = cached,
            )
            return matches
        }

        // 1. Check multi-word grammar rules (e.g. "this are" -> "this is", "he don't" -> "he doesn't")
        for (rule in phraseGrammarRules) {
            for (m in rule.regex.findAll(text)) {
                val repl = rule.replacement(m)
                if (repl != m.value) {
                    matches += GrammarMatch(
                        offset = m.range.first,
                        length = m.value.length,
                        replacement = repl,
                    )
                }
            }
        }

        // 2. Check individual words against English + Roman Urdu typo & contraction table + standalone "i"
        val wordRegex = Regex("""\b[A-Za-z']+\b""")
        for (m in wordRegex.findAll(text)) {
            val start = m.range.first
            val endExclusive = m.range.last + 1
            val overlapsExisting = matches.any { existing ->
                start < (existing.offset + existing.length) && endExclusive > existing.offset
            }
            if (overlapsExisting) continue

            val raw = m.value
            val lower = raw.lowercase()
            val corrected = when {
                raw == "i" -> "I"
                wordCorrections.containsKey(lower) -> preserveCase(raw, wordCorrections.getValue(lower))
                else -> null
            }
            if (corrected != null && corrected != raw) {
                matches += GrammarMatch(
                    offset = start,
                    length = raw.length,
                    replacement = corrected,
                )
            }
        }

        return matches
            .sortedBy { it.offset }
            .take(MAX_MATCHES_PER_SENTENCE)
    }

    fun evaluateSentenceSuggestions(
        textInfo: TextInfo,
        suggestionsLimit: Int,
    ): SentenceSuggestionsInfo? {
        val text = textInfo.text ?: return null
        val matches = findSentenceGrammarMatches(text)
        if (matches.isEmpty()) return null

        val suggestions = matches.map { match ->
            val replacements = if (suggestionsLimit > 0) {
                arrayOf(match.replacement)
            } else {
                emptyArray()
            }
            SpellingResult.grammarError(
                suggestions = replacements,
                isHighConfidenceResult = true,
            ).suggestionsInfo.apply {
                setCookieAndSequence(textInfo.cookie, textInfo.sequence)
            }
        }.toTypedArray()

        return SentenceSuggestionsInfo(
            suggestions,
            matches.map(GrammarMatch::offset).toIntArray(),
            matches.map(GrammarMatch::length).toIntArray(),
        )
    }

    fun fixGrammarAndSpelling(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return input
        cachedOrNull(trimmed)?.let { return it }

        var result = trimmed
        // Apply multi-word grammar rules
        for (rule in phraseGrammarRules) {
            result = rule.regex.replace(result) { m -> rule.replacement(m) }
        }
        // Apply word-level fixes
        val wordRegex = Regex("""\b[A-Za-z']+\b""")
        result = wordRegex.replace(result) { m ->
            val raw = m.value
            val lower = raw.lowercase()
            when {
                raw == "i" -> "I"
                wordCorrections.containsKey(lower) -> preserveCase(raw, wordCorrections.getValue(lower))
                else -> raw
            }
        }
        // Clean up extra spaces before punctuation and collapse multiple spaces
        result = result.replace(Regex("""\s+([,.;!?])"""), "$1")
        result = result.replace(Regex("""[ \t]{2,}"""), " ")

        // Capitalize first letter of sentences
        result = capitalizeSentences(result)
        return result
    }

    fun executeLocalFallback(
        task: AiWritingTask,
        inputText: String,
        customInstruction: String = "",
    ): String {
        val cleaned = fixGrammarAndSpelling(inputText)
        return when (task) {
            AiWritingTask.FIX_GRAMMAR_SPELLING -> cleaned
            AiWritingTask.IMPROVE_WRITING -> {
                val polished = cleaned
                    .replace(Regex("""\bkinda\b""", RegexOption.IGNORE_CASE), "somewhat")
                    .replace(Regex("""\bgonna\b""", RegexOption.IGNORE_CASE), "going to")
                    .replace(Regex("""\bwanna\b""", RegexOption.IGNORE_CASE), "want to")
                ensureTerminalPunctuation(polished)
            }
            AiWritingTask.ROMAN_URDU_TO_ENGLISH -> {
                var out = cleaned
                for ((pattern, repl) in romanUrduToEnglishPhrases) {
                    out = pattern.replace(out, repl)
                }
                capitalizeSentences(out)
            }
            AiWritingTask.ENGLISH_TO_ROMAN_URDU -> {
                var out = cleaned
                for ((pattern, repl) in englishToRomanUrduPhrases) {
                    out = pattern.replace(out, repl)
                }
                capitalizeSentences(out)
            }
            AiWritingTask.TO_URDU_SCRIPT -> {
                val tokens = inputText.trim().split(Regex("""\s+"""))
                tokens.joinToString(" ") { token ->
                    val bare = token.lowercase().trim(',', '.', '!', '?')
                    romanToUrduScriptWords[bare] ?: token
                }
            }
            AiWritingTask.TONE_PROFESSIONAL -> {
                val formal = cleaned
                    .replace(Regex("""\bthanks\b""", RegexOption.IGNORE_CASE), "thank you")
                    .replace(Regex("""\bhey\b""", RegexOption.IGNORE_CASE), "Hello")
                    .replace(Regex("""\basap\b""", RegexOption.IGNORE_CASE), "at your earliest convenience")
                    .replace(Regex("""\bok\b|\bokay\b""", RegexOption.IGNORE_CASE), "Understood")
                ensureTerminalPunctuation(capitalizeSentences(formal))
            }
            AiWritingTask.TONE_CASUAL -> {
                cleaned
                    .replace(Regex("""\bThank you very much\b""", RegexOption.IGNORE_CASE), "Thanks a lot")
                    .replace(Regex("""\bHello\b""", RegexOption.IGNORE_CASE), "Hey")
            }
            AiWritingTask.SHORTEN -> {
                val words = cleaned.split(Regex("""\s+"""))
                if (words.size <= 12) cleaned else words.take((words.size * 2 / 3).coerceAtLeast(8)).joinToString(" ") + "."
            }
            AiWritingTask.EXPAND -> {
                ensureTerminalPunctuation(cleaned) + " Please let me know if you need any further details."
            }
            AiWritingTask.SMART_REPLY -> {
                val lower = inputText.lowercase()
                when {
                    lower.contains("kesay") || lower.contains("kaise") || lower.contains("haal") ->
                        "Alhamdulillah main bilkul theek hoon, aap sunayein aap kaise hain?"
                    lower.contains("how are you") ->
                        "I'm doing great, thank you for asking! How are you doing?"
                    lower.contains("shukriya") || lower.contains("thank") ->
                        "You're most welcome! Happy to help anytime."
                    else ->
                        "Got it, thank you for the update! I will check and get back to you shortly."
                }
            }
            AiWritingTask.CONTINUE_WRITING -> {
                ensureTerminalPunctuation(cleaned) + " Let's make sure everything works smoothly from start to finish."
            }
            AiWritingTask.CUSTOM_PROMPT -> {
                if (customInstruction.isNotBlank() && inputText.isNotBlank()) {
                    fixGrammarAndSpelling(inputText)
                } else if (customInstruction.isNotBlank()) {
                    fixGrammarAndSpelling(customInstruction)
                } else {
                    cleaned
                }
            }
        }
    }

    private fun cachedOrNull(trimmed: String): String? = cloudSentenceCache[trimmed]

    private fun preserveCase(original: String, replacement: String): String {
        if (original.isEmpty() || replacement.isEmpty()) return replacement
        return when {
            original.all { !it.isLetter() || it.isUpperCase() } && original.length > 1 -> replacement.uppercase()
            original[0].isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
            else -> replacement
        }
    }

    private fun capitalizeSentences(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var capitalizeNext = true
        for (ch in text) {
            if (capitalizeNext && ch.isLetter()) {
                sb.append(ch.uppercaseChar())
                capitalizeNext = false
            } else {
                sb.append(ch)
                if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                    capitalizeNext = true
                }
            }
        }
        return sb.toString()
    }

    private fun ensureTerminalPunctuation(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        val last = t.last()
        return if (last == '.' || last == '!' || last == '?' || last == '۔') t else "$t."
    }
}

/**
 * Dual-Provider Cloud + Local AI Client supporting Google Gemini and OpenAI ChatGPT
 * with persistent user writing memory from [AiMemoryVault].
 */
object CloudAiClient {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 25_000
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val backgroundPool = Executors.newCachedThreadPool()

    /**
     * Executes an [AiWritingTask] using the user's connected Google Gemini and/or OpenAI ChatGPT
     * account, automatically injecting persistent writing memory from [AiMemoryVault] and falling
     * back to the secondary provider or [LocalGrammarAndRewriteEngine] if needed.
     */
    suspend fun executeTask(
        context: Context,
        task: AiWritingTask,
        inputText: String,
        customInstruction: String = "",
        inputType: Int = 0x01,
        imeOptions: Int = 0,
        isIncognito: Boolean = false,
        overrideProvider: AiProviderType? = null,
    ): AiExecutionResult = withContext(Dispatchers.IO) {
        executeTaskBlockingIo(
            context = context,
            task = task,
            inputText = inputText,
            customInstruction = customInstruction,
            inputType = inputType,
            imeOptions = imeOptions,
            isIncognito = isIncognito,
            overrideProvider = overrideProvider,
        )
    }

    /**
     * Tests an API key & model directly against Google Gemini or OpenAI ChatGPT and returns
     * a verified status message on success.
     */
    suspend fun testConnection(
        provider: AiProviderType,
        apiKey: String,
        model: String,
        baseUrl: String = "https://api.openai.com/v1",
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API key is empty"))
        }
        val systemPrompt = "You are 8002Keys AI assistant. Reply with a short 1-line confirmation."
        val userPrompt = "Fix grammar: 'i is testing 8002Keys ai login' and confirm connection."
        runCatching {
            when (provider) {
                AiProviderType.GEMINI, AiProviderType.AUTO_HYBRID -> {
                    val out = callGemini(
                        apiKey = cleanKey,
                        model = model.trim().ifBlank { "gemini-2.5-flash" },
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                    )
                    "Gemini connected (${model.ifBlank { "gemini-2.5-flash" }}): ${out.take(90)}"
                }
                AiProviderType.CHATGPT -> {
                    val out = callChatGpt(
                        apiKey = cleanKey,
                        model = model.trim().ifBlank { "gpt-4o-mini" },
                        baseUrl = baseUrl.trim().ifBlank { "https://api.openai.com/v1" },
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                    )
                    "ChatGPT connected (${model.ifBlank { "gpt-4o-mini" }}): ${out.take(90)}"
                }
            }
        }
    }

    internal fun executeTaskBlockingIo(
        context: Context?,
        task: AiWritingTask,
        inputText: String,
        customInstruction: String = "",
        inputType: Int = 0x01,
        imeOptions: Int = 0,
        isIncognito: Boolean = false,
        overrideProvider: AiProviderType? = null,
    ): AiExecutionResult {
        val trimmedInput = inputText.trim()
        val trimmedCustom = customInstruction.trim()
        if (trimmedInput.isEmpty() && trimmedCustom.isEmpty()) {
            return AiExecutionResult(
                outputText = "",
                providerUsed = "None",
                usedCloud = false,
                warningMessage = "Type or select some text in the editor first.",
            )
        }

        // Never send password/PIN fields to cloud providers
        if (SensitiveFieldGuard.isSensitive(inputType, imeOptions)) {
            val localOut = LocalGrammarAndRewriteEngine.executeLocalFallback(task, trimmedInput, trimmedCustom)
            return AiExecutionResult(
                outputText = localOut,
                providerUsed = "Local Engine (Protected Sensitive Field)",
                usedCloud = false,
                warningMessage = "Password/sensitive field detected — kept strictly on-device.",
            )
        }

        val vault = AiMemoryVault.state.value
        val preferred = overrideProvider ?: vault.preferredProvider
        val memoryContext = AiMemoryVault.buildMemoryContextPrompt()

        val systemPrompt = buildString {
            append("You are the built-in AI Writing, Grammar, Spell-Check, and Roman Urdu/English assistant inside the 8002Keys Android keyboard.\n")
            append("TASK DIRECTIVE: ").append(task.systemDirective).append('\n')
            if (memoryContext.isNotBlank()) {
                append('\n').append(memoryContext).append('\n')
            }
            append("\nIMPORTANT RULES:\n")
            append("1. Output ONLY the final corrected/rewritten/translated text ready to insert into the user's app.\n")
            append("2. Do NOT wrap the response in quotes or markdown code blocks unless asked.\n")
            append("3. Do NOT add conversational filler like 'Here is the corrected text:'.\n")
        }

        val userPrompt = buildString {
            if (trimmedCustom.isNotEmpty() && trimmedInput.isNotEmpty()) {
                append("Instruction: ").append(trimmedCustom).append("\n\n")
                append("Target Text:\n").append(trimmedInput)
            } else if (trimmedCustom.isNotEmpty()) {
                append(trimmedCustom)
            } else {
                append(trimmedInput)
            }
        }

        val providerOrder = buildProviderAttemptOrder(preferred, vault)
        var lastCloudError: String? = null

        for (candidate in providerOrder) {
            try {
                when (candidate) {
                    AiProviderType.GEMINI -> {
                        val response = callGemini(
                            apiKey = vault.geminiApiKey.trim(),
                            model = vault.geminiModel.trim().ifBlank { "gemini-2.5-flash" },
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                        )
                        val cleanOut = cleanAiOutput(response)
                        if (cleanOut.isNotEmpty()) {
                            onSuccessfulCloudExecution(context, task, trimmedInput, cleanOut, "Google Gemini (${vault.geminiModel})", isIncognito)
                            return AiExecutionResult(
                                outputText = cleanOut,
                                providerUsed = "Google Gemini (${vault.geminiModel})",
                                usedCloud = true,
                            )
                        }
                    }
                    AiProviderType.CHATGPT -> {
                        val response = callChatGpt(
                            apiKey = vault.chatGptApiKey.trim(),
                            model = vault.chatGptModel.trim().ifBlank { "gpt-4o-mini" },
                            baseUrl = vault.chatGptBaseUrl.trim().ifBlank { "https://api.openai.com/v1" },
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                        )
                        val cleanOut = cleanAiOutput(response)
                        if (cleanOut.isNotEmpty()) {
                            onSuccessfulCloudExecution(context, task, trimmedInput, cleanOut, "OpenAI ChatGPT (${vault.chatGptModel})", isIncognito)
                            return AiExecutionResult(
                                outputText = cleanOut,
                                providerUsed = "OpenAI ChatGPT (${vault.chatGptModel})",
                                usedCloud = true,
                            )
                        }
                    }
                    AiProviderType.AUTO_HYBRID -> Unit
                }
            } catch (e: Throwable) {
                lastCloudError = "${candidate.displayName}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        val localOut = LocalGrammarAndRewriteEngine.executeLocalFallback(task, trimmedInput, trimmedCustom)
        val warning = when {
            !vault.hasAnyCloudProvider ->
                "Using Local AI Grammar Engine. Tap 'Login / Memory ⚙️' to connect Google Gemini or ChatGPT."
            lastCloudError != null ->
                "Cloud fallback ($lastCloudError) — used Local AI Engine."
            else -> null
        }
        return AiExecutionResult(
            outputText = localOut,
            providerUsed = "8002Keys Local AI Engine",
            usedCloud = false,
            warningMessage = warning,
        )
    }

    private fun onSuccessfulCloudExecution(
        context: Context?,
        task: AiWritingTask,
        inputText: String,
        outputText: String,
        providerLabel: String,
        isIncognito: Boolean,
    ) {
        if (task == AiWritingTask.FIX_GRAMMAR_SPELLING || task == AiWritingTask.IMPROVE_WRITING) {
            LocalGrammarAndRewriteEngine.cacheCloudCorrection(inputText, outputText)
        }
        if (context != null && !isIncognito && inputText.isNotBlank()) {
            AiMemoryVault.recordTaskExecution(
                context = context,
                taskLabel = task.chipLabel,
                providerName = providerLabel,
                inputText = inputText,
                outputText = outputText,
                isSensitiveOrIncognito = false,
            )
        }
    }

    private fun buildProviderAttemptOrder(
        preferred: AiProviderType,
        vault: AiVaultState,
    ): List<AiProviderType> {
        val list = mutableListOf<AiProviderType>()
        when (preferred) {
            AiProviderType.GEMINI -> {
                if (vault.isGeminiReady) list += AiProviderType.GEMINI
                if (vault.isChatGptReady) list += AiProviderType.CHATGPT
            }
            AiProviderType.CHATGPT -> {
                if (vault.isChatGptReady) list += AiProviderType.CHATGPT
                if (vault.isGeminiReady) list += AiProviderType.GEMINI
            }
            AiProviderType.AUTO_HYBRID -> {
                if (vault.isGeminiReady) list += AiProviderType.GEMINI
                if (vault.isChatGptReady) list += AiProviderType.CHATGPT
            }
        }
        return list
    }

    private fun callGemini(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val safeModel = model.removePrefix("models/").trim().ifBlank { "gemini-2.5-flash" }
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent?key=$apiKey"

        val payloadObj = buildJsonObject {
            putJsonObject("systemInstruction") {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            }
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", userPrompt) })
                    })
                })
            })
            putJsonObject("generationConfig") {
                put("temperature", 0.25)
                put("maxOutputTokens", 1024)
            }
        }

        val responseBody = postJson(
            url = endpoint,
            headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
            body = json.encodeToString(JsonObject.serializer(), payloadObj),
        )

        val root = json.parseToJsonElement(responseBody).jsonObject
        val candidates = root["candidates"] as? JsonArray
            ?: error("Gemini returned no candidates")
        val firstCandidate = candidates.firstOrNull()?.jsonObject
            ?: error("Gemini candidate list empty")
        val content = firstCandidate["content"]?.jsonObject
            ?: error("Gemini candidate content missing")
        val parts = content["parts"] as? JsonArray
            ?: error("Gemini content parts missing")
        val text = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
            .trim()
        if (text.isEmpty()) error("Gemini returned empty text")
        return text
    }

    private fun callChatGpt(
        apiKey: String,
        model: String,
        baseUrl: String,
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { "https://api.openai.com/v1" }
        val endpoint = if (cleanBase.endsWith("/chat/completions")) {
            cleanBase
        } else {
            "$cleanBase/chat/completions"
        }

        val payloadObj = buildJsonObject {
            put("model", model.trim().ifBlank { "gpt-4o-mini" })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
            put("temperature", 0.25)
            put("max_tokens", 1024)
        }

        val responseBody = postJson(
            url = endpoint,
            headers = mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Authorization" to "Bearer $apiKey",
            ),
            body = json.encodeToString(JsonObject.serializer(), payloadObj),
        )

        val root = json.parseToJsonElement(responseBody).jsonObject
        val choices = root["choices"] as? JsonArray
            ?: error("ChatGPT returned no choices")
        val firstChoice = choices.firstOrNull()?.jsonObject
            ?: error("ChatGPT choices empty")
        val message = firstChoice["message"]?.jsonObject
            ?: error("ChatGPT message missing")
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (content.isEmpty()) error("ChatGPT returned empty content")
        return content
    }

    private fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            doOutput = true
            useCaches = false
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }
        return try {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val responseText = if (stream != null) {
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            } else {
                ""
            }
            if (status !in 200..299) {
                val shortErr = extractApiErrorMessage(responseText).ifBlank { "HTTP $status" }
                throw IllegalStateException(shortErr)
            }
            responseText
        } finally {
            conn.disconnect()
        }
    }

    private fun extractApiErrorMessage(rawJson: String): String {
        return runCatching {
            val obj = json.parseToJsonElement(rawJson).jsonObject
            val errObj = obj["error"]?.jsonObject
            errObj?.get("message")?.jsonPrimitive?.contentOrNull
                ?: obj["message"]?.jsonPrimitive?.contentOrNull
                ?: rawJson.take(140)
        }.getOrDefault(rawJson.take(140))
    }

    private fun cleanAiOutput(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```") && text.endsWith("```")) {
            val lines = text.lines()
            if (lines.size >= 3) {
                text = lines.subList(1, lines.size - 1).joinToString("\n").trim()
            }
        }
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"") && !text.substring(1, text.length - 1).contains("\"")) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    /**
     * Bridges [CloudAiClient] into [RewriteProviderRegistry] so existing Smartbar rewrite
     * quick actions (Formal, Casual, Shorter, Longer, Summarize) use Gemini / ChatGPT / Local AI.
     */
    fun asRewriteProvider(appContext: Context): RewriteProvider = object : RewriteProvider {
        override fun isReady(tone: RewriteTone, sourceLanguageTag: String): Boolean = true

        override fun rewrite(request: RewriteRequest): RewriteResult {
            val task = when (request.tone) {
                RewriteTone.FORMAL -> AiWritingTask.TONE_PROFESSIONAL
                RewriteTone.CASUAL -> AiWritingTask.TONE_CASUAL
                RewriteTone.SHORTER -> AiWritingTask.SHORTEN
                RewriteTone.LONGER -> AiWritingTask.EXPAND
                RewriteTone.SUMMARIZE -> AiWritingTask.SHORTEN
            }
            return runCatching {
                val future = backgroundPool.submit<AiExecutionResult> {
                    executeTaskBlockingIo(
                        context = appContext,
                        task = task,
                        inputText = request.sourceText,
                        inputType = request.inputType,
                        imeOptions = request.imeOptions,
                    )
                }
                val res = future.get(15, TimeUnit.SECONDS)
                if (res.outputText.isNotBlank()) {
                    RewriteResult.Rewritten(res.outputText.take(request.maxRewrittenChars), request.tone)
                } else {
                    RewriteResult.Failed("Empty rewrite response")
                }
            }.getOrElse { err ->
                val fallback = LocalGrammarAndRewriteEngine.executeLocalFallback(task, request.sourceText)
                if (fallback.isNotBlank()) {
                    RewriteResult.Rewritten(fallback.take(request.maxRewrittenChars), request.tone)
                } else {
                    RewriteResult.Failed(err.message ?: "Rewrite failed")
                }
            }
        }
    }

    /**
     * Bridges [CloudAiClient] into [InlineTranslatorRegistry] so inline translation works with
     * English, Roman Urdu, and Urdu Script out of the box.
     */
    fun asInlineTranslator(appContext: Context): InlineTranslator = object : InlineTranslator {
        override val installedPairs: Set<LanguagePairDescriptor> = setOf(
            LanguagePairDescriptor("ur", "en", "cloud_or_local/ur-en", 1024L, "high"),
            LanguagePairDescriptor("en", "ur", "cloud_or_local/en-ur", 1024L, "high"),
        )

        override fun isLanguagePairReady(sourceLocale: String, targetLocale: String): Boolean = true

        override fun translate(
            sourceText: String,
            sourceLocale: String,
            targetLocale: String,
        ): TranslationResult {
            if (sourceText.isBlank()) return TranslationResult.Unavailable
            val src = sourceLocale.lowercase()
            val dst = targetLocale.lowercase()
            val task = when {
                dst.startsWith("en") -> AiWritingTask.ROMAN_URDU_TO_ENGLISH
                dst.contains("roman") -> AiWritingTask.ENGLISH_TO_ROMAN_URDU
                dst.startsWith("ur") && src.startsWith("en") -> AiWritingTask.ENGLISH_TO_ROMAN_URDU
                dst.startsWith("ur") -> AiWritingTask.TO_URDU_SCRIPT
                else -> AiWritingTask.CUSTOM_PROMPT
            }
            val customPrompt = if (task == AiWritingTask.CUSTOM_PROMPT) {
                "Translate from $sourceLocale to $targetLocale:"
            } else {
                ""
            }
            return runCatching {
                val future = backgroundPool.submit<AiExecutionResult> {
                    executeTaskBlockingIo(
                        context = appContext,
                        task = task,
                        inputText = sourceText,
                        customInstruction = customPrompt,
                    )
                }
                val res = future.get(15, TimeUnit.SECONDS)
                if (res.outputText.isNotBlank()) {
                    TranslationResult.Translated(
                        translatedText = res.outputText,
                        confidence = if (res.usedCloud) 0.96f else 0.80f,
                    )
                } else {
                    TranslationResult.Unavailable
                }
            }.getOrElse {
                val fallback = LocalGrammarAndRewriteEngine.executeLocalFallback(task, sourceText, customPrompt)
                if (fallback.isNotBlank()) {
                    TranslationResult.Translated(fallback, 0.75f)
                } else {
                    TranslationResult.Unavailable
                }
            }
        }
    }
}

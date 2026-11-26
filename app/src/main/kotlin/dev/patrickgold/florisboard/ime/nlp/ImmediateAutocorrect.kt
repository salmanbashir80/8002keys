/*
 * Copyright (C) 2026 SwiftFloris Contributors
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

package dev.patrickgold.florisboard.ime.nlp

internal data class ImmediateAutocorrectCorrection(
    val dictionaryWord: String,
    val text: String,
    val tier: Tier = Tier.SAFE,
) {
    enum class Tier {
        /** No collision with a real English word — safe to immediate-auto-commit. */
        SAFE,

        /**
         * Collides with a real English word ("ill", "well", "hell", "wed", "id", ...).
         * Only auto-commit when the dictionary check confirms the typed word is not
         * itself a valid word — handled in the LatinLanguageProvider path. The immediate
         * (no-dictionary) path must NEVER auto-commit these.
         */
        DICTIONARY_GATED,
    }
}

/**
 * Curated English contraction substitutions, tuned for SwiftKey-parity behavior.
 *
 * Two tiers exist (see [ImmediateAutocorrectCorrection.Tier]):
 * - SAFE entries (e.g. "dont" → "don't", "youre" → "you're") have no real-word collision
 *   and substitute on space/punctuation immediately.
 * - DICTIONARY_GATED entries (e.g. "well" → "we'll", "id" → "I'd") collide with real
 *   English words and only auto-commit when the dictionary check confirms the typed
 *   word is not itself in the user's dictionary.
 *
 * Excluded entirely (too ambiguous even with dict gating):
 *   "were" — past tense of "to be" is far more common than "we're".
 */
internal object ImmediateAutocorrect {
    /**
     * Returns the contraction expansion for [rawWord] (with the user's typed case applied),
     * or null if no expansion applies. Callers that need to gate by dictionary membership
     * should check [ImmediateAutocorrectCorrection.tier] and verify the dictionary contains
     * (or does not contain) [ImmediateAutocorrectCorrection.dictionaryWord] as appropriate.
     */
    fun englishContraction(
        rawWord: String,
        languageCode: String,
    ): ImmediateAutocorrectCorrection? {
        if (normalizeLanguageCode(languageCode) != "en") {
            return null
        }
        val typedWord = rawWord.trim().trim { char -> !char.isLetter() && char != '\'' }
        if (typedWord.isEmpty()) return null
        val normalizedWord = typedWord.lowercase()
        val canonical = EnglishContractions[normalizedWord] ?: return null

        val letters = typedWord.filter { it.isLetter() }
        val isAllCapsInput = letters.length > 1 && letters.all { it.isUpperCase() }

        // Don't re-substitute an already-correct form.
        if (typedWord.equals(canonical.text, ignoreCase = false)) return null

        // ALL-CAPS suppression matches SwiftKey: ALL CAPS tokens are left untouched
        // because they almost always indicate an acronym, initialism, or intentional
        // shouting that the user does not want re-cased ("ID", "DONT", "WONT", "IM").
        if (isAllCapsInput) return null

        val finalText = applyTypedCase(canonical.text, typedWord)
        if (typedWord == finalText) return null
        return ImmediateAutocorrectCorrection(
            dictionaryWord = canonical.dictionaryWord,
            text = finalText,
            tier = canonical.tier,
        )
    }

    /**
     * Returns an immediate auto-commit candidate for [rawWord] only when the substitution
     * is in the SAFE tier. DICTIONARY_GATED corrections must go through the dictionary-aware
     * path in [dev.patrickgold.florisboard.ime.nlp.latin.LatinLanguageProvider].
     */
    fun englishContractionCandidate(
        rawWord: String,
        languageCode: String,
    ): WordSuggestionCandidate? {
        val correction = englishContraction(rawWord, languageCode) ?: return null
        if (correction.tier != ImmediateAutocorrectCorrection.Tier.SAFE) return null
        return WordSuggestionCandidate(
            text = correction.text,
            confidence = 1.0,
            isEligibleForAutoCommit = true,
            isEligibleForUserRemoval = false,
        )
    }

    fun englishPhraseRepair(
        rawWord: String,
        languageCode: String,
    ): ImmediateAutocorrectCorrection? {
        if (normalizeLanguageCode(languageCode) != "en") {
            return null
        }
        val typedWord = rawWord.trim().trim { char -> !char.isLetter() }
        if (typedWord.isEmpty()) return null
        val normalizedWord = typedWord.lowercase()
        val canonical = EnglishPhraseRepairs[normalizedWord] ?: return null

        val letters = typedWord.filter { it.isLetter() }
        val isAllCapsInput = letters.length > 1 && letters.all { it.isUpperCase() }
        if (isAllCapsInput) return null

        val finalText = applyTypedCase(canonical.text, typedWord)
        if (typedWord == finalText) return null
        return ImmediateAutocorrectCorrection(
            dictionaryWord = canonical.dictionaryWord,
            text = finalText,
            tier = ImmediateAutocorrectCorrection.Tier.SAFE,
        )
    }

    fun englishPhraseRepairCandidate(
        rawWord: String,
        languageCode: String,
    ): WordSuggestionCandidate? {
        val correction = englishPhraseRepair(rawWord, languageCode) ?: return null
        return WordSuggestionCandidate(
            text = correction.text,
            confidence = 1.0,
            isEligibleForAutoCommit = true,
            isEligibleForUserRemoval = false,
        )
    }

    /**
     * Apply the user's typed-case pattern to a canonical contraction.
     * Examples:
     *   ("don't",   "dont")  → "don't"
     *   ("don't",   "Dont")  → "Don't"
     *   ("don't",   "DONT")  → "DON'T"
     *   ("you're",  "Youre") → "You're"
     *   ("I'm",     "im")    → "I'm"   (canonical retains the capital I)
     *   ("I'm",     "IM")    → "I'M"
     */
    internal fun applyTypedCase(canonical: String, typedWord: String): String {
        val typedLetters = typedWord.filter { it.isLetter() }
        if (typedLetters.isEmpty()) return canonical
        val isAllCaps = typedLetters.length > 1 && typedLetters.all { it.isUpperCase() }
        val isFirstUpper = typedLetters.first().isUpperCase()
        return when {
            isAllCaps -> canonical.uppercase()
            isFirstUpper -> canonical.replaceFirstChar { ch -> ch.uppercase() }
            else -> canonical
        }
    }

    private fun normalizeLanguageCode(languageCode: String): String {
        return languageCode
            .substringBefore('-')
            .substringBefore('_')
            .lowercase()
            .ifBlank { "en" }
    }

    // Convenience constructors so the table below stays readable.
    private fun safe(dictionaryWord: String, text: String) =
        ImmediateAutocorrectCorrection(dictionaryWord, text, ImmediateAutocorrectCorrection.Tier.SAFE)

    private fun gated(dictionaryWord: String, text: String) =
        ImmediateAutocorrectCorrection(dictionaryWord, text, ImmediateAutocorrectCorrection.Tier.DICTIONARY_GATED)

    /**
     * Canonical English contractions table.
     *
     * Keys are lowercase typed-without-apostrophe forms (and the apostrophe-form for
     * already-correct detection). Values carry the dictionary lookup form, the canonical
     * cased text (for first-person "I" entries this preserves the capital I), and the tier.
     */
    private val EnglishContractions: Map<String, ImmediateAutocorrectCorrection> = buildMap {
        // ---- First-person "I" forms ---------------------------------------------------
        put("i", safe("i", "I"))
        put("im", gated("i'm", "I'm"))
        put("i'm", gated("i'm", "I'm"))
        put("ill", gated("i'll", "I'll"))
        put("i'll", gated("i'll", "I'll"))
        put("id", gated("i'd", "I'd"))
        put("i'd", gated("i'd", "I'd"))
        put("ive", gated("i've", "I've"))
        put("i've", gated("i've", "I've"))
        put("ima", safe("i'ma", "I'ma"))
        put("i'ma", safe("i'ma", "I'ma"))

        // ---- Negative -n't forms (mostly safe, a few collide) -------------------------
        // SAFE — no English-word collision
        put("dont", safe("don't", "don't"))
        put("don't", safe("don't", "don't"))
        put("isnt", safe("isn't", "isn't"))
        put("isn't", safe("isn't", "isn't"))
        put("wasnt", safe("wasn't", "wasn't"))
        put("wasn't", safe("wasn't", "wasn't"))
        put("werent", safe("weren't", "weren't"))
        put("weren't", safe("weren't", "weren't"))
        put("arent", safe("aren't", "aren't"))
        put("aren't", safe("aren't", "aren't"))
        put("didnt", safe("didn't", "didn't"))
        put("didn't", safe("didn't", "didn't"))
        put("doesnt", safe("doesn't", "doesn't"))
        put("doesn't", safe("doesn't", "doesn't"))
        put("havent", safe("haven't", "haven't"))
        put("haven't", safe("haven't", "haven't"))
        put("hasnt", safe("hasn't", "hasn't"))
        put("hasn't", safe("hasn't", "hasn't"))
        put("hadnt", safe("hadn't", "hadn't"))
        put("hadn't", safe("hadn't", "hadn't"))
        put("wouldnt", safe("wouldn't", "wouldn't"))
        put("wouldn't", safe("wouldn't", "wouldn't"))
        put("shouldnt", safe("shouldn't", "shouldn't"))
        put("shouldn't", safe("shouldn't", "shouldn't"))
        put("couldnt", safe("couldn't", "couldn't"))
        put("couldn't", safe("couldn't", "couldn't"))
        put("mustnt", safe("mustn't", "mustn't"))
        put("mustn't", safe("mustn't", "mustn't"))
        put("neednt", safe("needn't", "needn't"))
        put("needn't", safe("needn't", "needn't"))
        put("mightnt", safe("mightn't", "mightn't"))
        put("mightn't", safe("mightn't", "mightn't"))
        put("oughtnt", safe("oughtn't", "oughtn't"))
        put("oughtn't", safe("oughtn't", "oughtn't"))
        put("shant", safe("shan't", "shan't"))
        put("shan't", safe("shan't", "shan't"))
        put("aint", safe("ain't", "ain't"))
        put("ain't", safe("ain't", "ain't"))
        // DICTIONARY_GATED — collide with real English words
        put("wont", gated("won't", "won't"))           // "wont" is an archaic noun ("his wont")
        put("won't", gated("won't", "won't"))
        put("cant", gated("can't", "can't"))           // "cant" is a real noun (architectural / jargon)
        put("can't", gated("can't", "can't"))

        // ---- "would have / could have / should have / etc." -'ve forms (all SAFE) -----
        put("wouldve", safe("would've", "would've"))
        put("would've", safe("would've", "would've"))
        put("shouldve", safe("should've", "should've"))
        put("should've", safe("should've", "should've"))
        put("couldve", safe("could've", "could've"))
        put("could've", safe("could've", "could've"))
        put("mightve", safe("might've", "might've"))
        put("might've", safe("might've", "might've"))
        put("mustve", safe("must've", "must've"))
        put("must've", safe("must've", "must've"))

        // ---- Pronoun + auxiliary (you / they / we) ------------------------------------
        // SAFE — none are English words
        put("youre", safe("you're", "you're"))
        put("you're", safe("you're", "you're"))
        put("youve", safe("you've", "you've"))
        put("you've", safe("you've", "you've"))
        put("youll", safe("you'll", "you'll"))
        put("you'll", safe("you'll", "you'll"))
        put("youd", safe("you'd", "you'd"))
        put("you'd", safe("you'd", "you'd"))
        put("theyre", safe("they're", "they're"))
        put("they're", safe("they're", "they're"))
        put("theyve", safe("they've", "they've"))
        put("they've", safe("they've", "they've"))
        put("theyll", safe("they'll", "they'll"))
        put("they'll", safe("they'll", "they'll"))
        put("theyd", safe("they'd", "they'd"))
        put("they'd", safe("they'd", "they'd"))
        put("weve", safe("we've", "we've"))
        put("we've", safe("we've", "we've"))
        // "it" forms — "itll", "itd", "itve" are not English words → SAFE.
        // "its" → "it's" is the classic ambiguity (possessive "its" vs contraction
        // "it's") — must stay DICTIONARY_GATED.
        put("itll", safe("it'll", "it'll"))
        put("it'll", safe("it'll", "it'll"))
        put("itd", safe("it'd", "it'd"))
        put("it'd", safe("it'd", "it'd"))
        put("its", gated("it's", "it's"))
        put("it's", gated("it's", "it's"))
        // DICTIONARY_GATED — "well", "hell", "shell", "shed", "wed", "lets", "hed" collide
        put("well", gated("we'll", "we'll"))
        put("we'll", gated("we'll", "we'll"))
        put("hell", gated("he'll", "he'll"))
        put("he'll", gated("he'll", "he'll"))
        put("shell", gated("she'll", "she'll"))
        put("she'll", gated("she'll", "she'll"))
        put("hes", gated("he's", "he's"))
        put("he's", gated("he's", "he's"))
        put("shes", gated("she's", "she's"))
        put("she's", gated("she's", "she's"))
        put("hed", gated("he'd", "he'd"))
        put("he'd", gated("he'd", "he'd"))
        put("shed", gated("she'd", "she'd"))
        put("she'd", gated("she'd", "she'd"))
        put("wed", gated("we'd", "we'd"))
        put("we'd", gated("we'd", "we'd"))
        put("lets", gated("let's", "let's"))
        put("let's", gated("let's", "let's"))
        // Intentionally NOT included: "were" → "we're" (past tense "to be" is far too common)

        // ---- Question words / demonstratives ('s/'re/'ll/'d/'ve) ---------------------
        // SAFE — none are English words
        put("whats", safe("what's", "what's"))
        put("what's", safe("what's", "what's"))
        put("whatre", safe("what're", "what're"))
        put("what're", safe("what're", "what're"))
        put("whatll", safe("what'll", "what'll"))
        put("what'll", safe("what'll", "what'll"))
        put("whatd", safe("what'd", "what'd"))
        put("what'd", safe("what'd", "what'd"))
        put("whatve", safe("what've", "what've"))
        put("what've", safe("what've", "what've"))
        put("whos", safe("who's", "who's"))
        put("who's", safe("who's", "who's"))
        put("whod", safe("who'd", "who'd"))
        put("who'd", safe("who'd", "who'd"))
        put("wholl", safe("who'll", "who'll"))
        put("who'll", safe("who'll", "who'll"))
        put("whove", safe("who've", "who've"))
        put("who've", safe("who've", "who've"))
        put("wheres", safe("where's", "where's"))
        put("where's", safe("where's", "where's"))
        put("whered", safe("where'd", "where'd"))
        put("where'd", safe("where'd", "where'd"))
        put("wherell", safe("where'll", "where'll"))
        put("where'll", safe("where'll", "where'll"))
        put("whens", safe("when's", "when's"))
        put("when's", safe("when's", "when's"))
        put("whend", safe("when'd", "when'd"))
        put("when'd", safe("when'd", "when'd"))
        put("whys", safe("why's", "why's"))
        put("why's", safe("why's", "why's"))
        put("whyd", safe("why'd", "why'd"))
        put("why'd", safe("why'd", "why'd"))
        put("hows", safe("how's", "how's"))
        put("how's", safe("how's", "how's"))
        put("howd", safe("how'd", "how'd"))
        put("how'd", safe("how'd", "how'd"))
        put("howll", safe("how'll", "how'll"))
        put("how'll", safe("how'll", "how'll"))
        put("theres", safe("there's", "there's"))
        put("there's", safe("there's", "there's"))
        put("thered", safe("there'd", "there'd"))
        put("there'd", safe("there'd", "there'd"))
        put("therell", safe("there'll", "there'll"))
        put("there'll", safe("there'll", "there'll"))
        put("thereve", safe("there've", "there've"))
        put("there've", safe("there've", "there've"))
        put("thatll", safe("that'll", "that'll"))
        put("that'll", safe("that'll", "that'll"))
        put("thatd", safe("that'd", "that'd"))
        put("that'd", safe("that'd", "that'd"))
        put("thatre", safe("that're", "that're"))
        put("that're", safe("that're", "that're"))
        put("thats", safe("that's", "that's"))
        put("that's", safe("that's", "that's"))
        put("heres", safe("here's", "here's"))
        put("here's", safe("here's", "here's"))

        // ---- Indefinite-pronoun + 's (all SAFE — none are English words) -------------
        put("someones", safe("someone's", "someone's"))
        put("someone's", safe("someone's", "someone's"))
        put("everyones", safe("everyone's", "everyone's"))
        put("everyone's", safe("everyone's", "everyone's"))
        put("anyones", safe("anyone's", "anyone's"))
        put("anyone's", safe("anyone's", "anyone's"))
        put("nobodys", safe("nobody's", "nobody's"))
        put("nobody's", safe("nobody's", "nobody's"))
        put("everybodys", safe("everybody's", "everybody's"))
        put("everybody's", safe("everybody's", "everybody's"))
        put("anybodys", safe("anybody's", "anybody's"))
        put("anybody's", safe("anybody's", "anybody's"))
        put("somebodys", safe("somebody's", "somebody's"))
        put("somebody's", safe("somebody's", "somebody's"))
        put("somethings", safe("something's", "something's"))
        put("something's", safe("something's", "something's"))
        put("nothings", safe("nothing's", "nothing's"))
        put("nothing's", safe("nothing's", "nothing's"))

        // ---- Misc fixed-form contractions (all SAFE) --------------------------------
        put("oclock", safe("o'clock", "o'clock"))
        put("o'clock", safe("o'clock", "o'clock"))
        put("yall", safe("y'all", "y'all"))
        put("y'all", safe("y'all", "y'all"))
        put("yalls", safe("y'all's", "y'all's"))
        put("y'all's", safe("y'all's", "y'all's"))
        put("maam", safe("ma'am", "ma'am"))
        put("ma'am", safe("ma'am", "ma'am"))
    }

    private val EnglishPhraseRepairs: Map<String, ImmediateAutocorrectCorrection> = buildMap {
        put("alot", safe("a lot", "a lot"))
        put("alotof", safe("a lot of", "a lot of"))
        put("alittle", safe("a little", "a little"))
        put("aswell", safe("as well", "as well"))
        put("aswellas", safe("as well as", "as well as"))
        put("atleast", safe("at least", "at least"))
        put("atall", safe("at all", "at all"))
        put("atthe", safe("at the", "at the"))
        put("attheend", safe("at the end", "at the end"))
        put("bytheend", safe("by the end", "by the end"))
        put("bytheway", safe("by the way", "by the way"))
        put("eachother", safe("each other", "each other"))
        put("everytime", safe("every time", "every time"))
        put("forthefirst", safe("for the first", "for the first"))
        put("fornow", safe("for now", "for now"))
        put("forthe", safe("for the", "for the"))
        put("fromnowon", safe("from now on", "from now on"))
        put("fromnow", safe("from now", "from now"))
        put("goodmorning", safe("good morning", "good morning"))
        put("infact", safe("in fact", "in fact"))
        put("infront", safe("in front", "in front"))
        put("infrontof", safe("in front of", "in front of"))
        put("intheend", safe("in the end", "in the end"))
        put("inthemorning", safe("in the morning", "in the morning"))
        put("inthe", safe("in the", "in the"))
        put("kindof", safe("kind of", "kind of"))
        put("letmeknow", safe("let me know", "let me know"))
        put("letme", safe("let me", "let me"))
        put("notatall", safe("not at all", "not at all"))
        put("notreally", safe("not really", "not really"))
        put("notsure", safe("not sure", "not sure"))
        put("notyet", safe("not yet", "not yet"))
        put("noone", safe("no one", "no one"))
        put("ofcourse", safe("of course", "of course"))
        put("ofthe", safe("of the", "of the"))
        put("onthe", safe("on the", "on the"))
        put("ontheway", safe("on the way", "on the way"))
        put("rightaway", safe("right away", "right away"))
        put("rightnow", safe("right now", "right now"))
        put("seeyou", safe("see you", "see you"))
        put("seeyousoon", safe("see you soon", "see you soon"))
        put("sortof", safe("sort of", "sort of"))
        put("talkto", safe("talk to", "talk to"))
        put("talktoyou", safe("talk to you", "talk to you"))
        put("thankyou", safe("thank you", "thank you"))
        put("thankyoufor", safe("thank you for", "thank you for"))
        put("tothe", safe("to the", "to the"))
    }

    /**
     * High-confidence bilingual (English + Pakistani Roman Urdu) spacebar auto-corrections
     * for common mobile fast-typing typos. Active for English ("en") and Urdu/Roman Urdu ("ur")
     * subtypes without interfering with strict locale contraction unit tests.
     */
    fun bilingualTypoCandidate(
        rawWord: String,
        languageCode: String,
    ): WordSuggestionCandidate? {
        val lang = normalizeLanguageCode(languageCode)
        if (lang != "en" && lang != "ur") return null
        val typedWord = rawWord.trim().trim { char -> !char.isLetter() && char != '\'' }
        if (typedWord.length < 2) return null
        val letters = typedWord.filter { it.isLetter() }
        if (letters.length > 1 && letters.all { it.isUpperCase() }) return null
        val normalized = typedWord.lowercase()
        val canonical = BilingualFastTypingCorrections[normalized] ?: return null
        val finalText = if (canonical.any { it.isUpperCase() }) {
            canonical
        } else {
            applyTypedCase(canonical, typedWord)
        }
        if (typedWord == finalText) return null
        return WordSuggestionCandidate(
            text = finalText,
            confidence = 1.0,
            isEligibleForAutoCommit = true,
            isEligibleForUserRemoval = false,
        )
    }

    fun bilingualPhraseRepairCandidate(
        rawWord: String,
        languageCode: String,
    ): WordSuggestionCandidate? {
        val lang = normalizeLanguageCode(languageCode)
        if (lang != "en" && lang != "ur") return null
        val typedWord = rawWord.trim().trim { char -> !char.isLetter() }
        if (typedWord.length < 3) return null
        val letters = typedWord.filter { it.isLetter() }
        if (letters.length > 1 && letters.all { it.isUpperCase() }) return null
        val normalized = typedWord.lowercase()
        val canonical = BilingualPhraseRepairs[normalized] ?: return null
        val finalText = applyTypedCase(canonical, typedWord)
        if (typedWord == finalText) return null
        return WordSuggestionCandidate(
            text = finalText,
            confidence = 1.0,
            isEligibleForAutoCommit = true,
            isEligibleForUserRemoval = false,
        )
    }

    private val BilingualFastTypingCorrections: Map<String, String> = mapOf(
        // Common English mobile typos
        "teh" to "the",
        "hte" to "the",
        "thn" to "then",
        "recieve" to "receive",
        "beleive" to "believe",
        "acheive" to "achieve",
        "seperate" to "separate",
        "definately" to "definitely",
        "definatly" to "definitely",
        "occured" to "occurred",
        "untill" to "until",
        "wierd" to "weird",
        "freind" to "friend",
        "becuase" to "because",
        "beacuse" to "because",
        "accomodate" to "accommodate",
        "tommorow" to "tomorrow",
        "tomorow" to "tomorrow",
        "enviroment" to "environment",
        "goverment" to "government",
        "neccessary" to "necessary",
        "necesary" to "necessary",
        "succesful" to "successful",
        "successfull" to "successful",
        "settung" to "setting",
        "seting" to "setting",
        "setings" to "settings",
        "coreect" to "correct",
        "corret" to "correct",
        "commect" to "connect",
        "conect" to "connect",
        "googlr" to "Google",
        "gogle" to "Google",
        "accoutn" to "account",
        "acount" to "account",
        "chargpt" to "ChatGPT",
        "chatgtp" to "ChatGPT",
        "chatgpt" to "ChatGPT",
        "demini" to "Gemini",
        "gemni" to "Gemini",
        "gemini" to "Gemini",
        "subscriton" to "subscription",
        "subscrption" to "subscription",
        "studeo" to "Studio",
        "studoe" to "Studio",
        "udpate" to "update",
        "udpte" to "update",
        "pdatet" to "update",
        "implwmr" to "implement",
        "implemnt" to "implement",
        "featire" to "feature",
        "feture" to "feature",
        "grammer" to "grammar",
        "grmammer" to "grammar",
        "memery" to "memory",
        "memry" to "memory",
        "passowrd" to "password",
        "pasword" to "password",
        "keybaord" to "keyboard",
        "keybord" to "keyboard",
        "andriod" to "Android",
        "whatsap" to "WhatsApp",
        // Common Pakistani Roman Urdu fast-typing slips & shorthands
        "mjy" to "mujhe",
        "mje" to "mujhe",
        "mjhy" to "mujhe",
        "tmy" to "tumhein",
        "apko" to "aapko",
        "apka" to "aapka",
        "apki" to "aapki",
        "chaye" to "chahiye",
        "chahye" to "chahiye",
        "chaiye" to "chahiye",
        "kaor" to "karo",
        "kro" to "karo",
        "krna" to "karna",
        "krni" to "karni",
        "krny" to "karne",
        "krdo" to "kar do",
        "krdy" to "kar de",
        "krain" to "karein",
        "krein" to "karein",
        "kren" to "karein",
        "kron" to "karoon",
        "krun" to "karoon",
        "akrwao" to "karwao",
        "krwao" to "karwao",
        "ayys" to "aise",
        "aysy" to "aise",
        "aisy" to "aise",
        "jesy" to "jaise",
        "jese" to "jaise",
        "meon" to "mein",
        "aath" to "saath",
        "sath" to "saath",
        "amsla" to "masla",
        "msla" to "masla",
        "tkay" to "taake",
        "taky" to "taake",
        "isku" to "isko",
        "usku" to "usko",
        "unku" to "unko",
        "dubara" to "dobara",
        "leay" to "liye",
        "liay" to "liye",
        "gya" to "gaya",
        "gyi" to "gayi",
        "gye" to "gaye",
        "hoa" to "hua",
        "hoi" to "hui",
        "hoay" to "hue",
        "nai" to "nahi",
        "nhi" to "nahi",
        "agr" to "agar",
        "mgr" to "magar",
        "lkn" to "lekin",
        "bht" to "bohat",
        "boht" to "bohat",
        "shukria" to "shukriya",
        "shukrya" to "shukriya",
        "inshallah" to "InshaAllah",
        "inshaallah" to "InshaAllah",
        "alhamdulilah" to "Alhamdulillah",
        "alhamdulillah" to "Alhamdulillah",
        "mashallah" to "MashaAllah",
        "mashaallah" to "MashaAllah",
        // Extended Pakistani Roman Urdu & everyday chat dictionary
        "bhtreen" to "behtareen",
        "behtreen" to "behtareen",
        "chhaye" to "chahiye",
        "chahye" to "chahiye",
        "takay" to "taake",
        "smj" to "samajh",
        "smjh" to "samajh",
        "bta" to "bata",
        "btana" to "batana",
        "btao" to "batao",
        "dnea" to "dena",
        "rha" to "raha",
        "rhi" to "rahi",
        "rhy" to "rahe",
        "thk" to "theek",
        "thik" to "theek",
        "zror" to "zaroor",
        "zaror" to "zaroor",
        "zindgi" to "zindagi",
        "meharbani" to "meherbani",
        "subha" to "subah",
        "wqt" to "waqt",
        "jldi" to "jaldi",
        "abhi" to "abhi",
        "abi" to "abhi",
        "phr" to "phir",
        "kyun" to "kyun",
        "q" to "kyun",
        "kaisy" to "kaise",
        "kesy" to "kaise",
        "kaha" to "kahan",
        "whan" to "wahan",
        "yhan" to "yahan",
        "opr" to "upar",
        "nichy" to "neeche",
        "pichy" to "peeche",
        "agay" to "aage",
        "pehle" to "pehle",
        "pehly" to "pehle",
        "baad" to "baad",
        "bd" to "baad",
        "sb" to "sab",
        "kch" to "kuch",
        "koi" to "koi",
        "hr" to "har",
        "bs" to "bas",
        "srk" to "sirf",
        "srf" to "sirf",
        "ap" to "aap",
        "tm" to "tum",
        "hm" to "hum",
        "wo" to "woh",
        "ye" to "yeh",
        "ya" to "yeh",
        "hy" to "hai",
        "hn" to "hain",
        "tha" to "tha",
        "thi" to "thi",
        "thy" to "the",
        "hoga" to "hoga",
        "hogi" to "hogi",
        "hogy" to "honge",
        "krty" to "karte",
        "krta" to "karta",
        "krti" to "karti",
        "skta" to "sakta",
        "skti" to "sakti",
        "skty" to "sakte",
        "lgta" to "lagta",
        "lgti" to "lagti",
        "mlta" to "milta",
        "mlna" to "milna",
        "dkho" to "dekho",
        "dkhao" to "dikhao",
        "dkehao" to "dikhao",
        "suno" to "suno",
        "chlo" to "chalo",
        "ruko" to "ruko",
        "btao" to "batao",
        "likho" to "likho",
        "bolna" to "bolna",
        "buuton" to "button",
        "bitton" to "button",
        "pannel" to "panel",
        "dictoey" to "dictionary",
        "dictionay" to "dictionary",
        "cirrecrion" to "correction",
        "tyoing" to "typing",
        "uodste" to "update",
        "comocat" to "compact",
        "plz" to "please",
        "pls" to "please",
        "msg" to "message",
        "msgs" to "messages",
        "fint" to "font",
        "feindly" to "friendly",
        "feisnly" to "friendly",
        "premonum" to "premium",
        "downalod" to "download",
        "downlaod" to "download",
        "isntall" to "install",
        "istalling" to "installing",
    )

    private val BilingualPhraseRepairs: Map<String, String> = mapOf(
        "ismein" to "is mein",
        "usmein" to "us mein",
        "jesyis" to "jaise is",
        "kaypdatet" to "ke update",
        "grmammerku" to "grammar ko",
        "donolgin" to "dono login",
        "krdo" to "kar do",
        "krdia" to "kar diya",
        "krdiya" to "kar diya",
        "hogya" to "ho gaya",
        "hogaya" to "ho gaya",
        "assaalamualaikum" to "Assalamu Alaikum",
        "assalamualaikum" to "Assalamu Alaikum",
        "walaikumsalam" to "Wa Alaikum Assalam",
        "khudahafiz" to "Khuda Hafiz",
        "allahhafiz" to "Allah Hafiz",
        "jazakallah" to "JazakAllah",
    )
}


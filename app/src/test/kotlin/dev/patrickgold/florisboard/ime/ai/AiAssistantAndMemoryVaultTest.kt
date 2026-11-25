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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class AiAssistantAndMemoryVaultTest : FunSpec({

    test("AiMemoryVault envelope round-trips Gemini and ChatGPT accounts plus writing memory") {
        val original = AiVaultState(
            geminiConnected = true,
            geminiAccountEmail = "user@gmail.com",
            geminiApiKey = "AIzaSyTestKeyFor8002KeysVerification12345",
            geminiModel = "gemini-2.5-flash",
            chatGptConnected = true,
            chatGptAccountEmail = "user@openai.com",
            chatGptApiKey = "sk-proj-TestKeyFor8002KeysVerification1234567890",
            chatGptModel = "gpt-4o-mini",
            preferredProvider = AiProviderType.AUTO_HYBRID,
            writingStyleMemory = "Fix grammar and support Roman Urdu + English.",
            learnedMemories = listOf(
                AiLearnedMemoryItem(
                    id = "m1",
                    originalText = "mjy ya chahye",
                    preferredText = "Mujhe ye chahiye",
                    taskLabel = "Fix Grammar & Spell ✨",
                    timestampMs = 1_700_000_000_000L,
                ),
            ),
            customWords = listOf("chahiye", "shukriya", "InshaAllah"),
            lastUpdatedMs = 1_700_000_000_500L,
        )

        val encoded = AiMemoryVault.encodeVaultEnvelope(original)
        val decoded = AiMemoryVault.decodeVaultEnvelope(encoded)
        decoded shouldNotBe null
        decoded!!.geminiConnected shouldBe true
        decoded.geminiAccountEmail shouldBe "user@gmail.com"
        decoded.geminiApiKey shouldBe "AIzaSyTestKeyFor8002KeysVerification12345"
        decoded.chatGptConnected shouldBe true
        decoded.chatGptAccountEmail shouldBe "user@openai.com"
        decoded.chatGptApiKey shouldBe "sk-proj-TestKeyFor8002KeysVerification1234567890"
        decoded.learnedMemories.size shouldBe 1
        decoded.customWords shouldContain "InshaAllah"
    }

    test("AiMemoryVault mergeVaultStates restores saved accounts and memories after fresh reinstall") {
        val freshInstallInternal = AiVaultState() // blank on fresh install
        val externalDeviceMemory = AiVaultState(
            geminiConnected = true,
            geminiAccountEmail = "salman@gmail.com",
            geminiApiKey = "AIzaSyPersistentDeviceMemoryKey123456789",
            chatGptConnected = true,
            chatGptAccountEmail = "salman@chatgpt.com",
            chatGptApiKey = "sk-PersistentChatGptKey123456789012345678",
            learnedMemories = listOf(
                AiLearnedMemoryItem(
                    id = "mem-old",
                    originalText = "i is here",
                    preferredText = "I am here.",
                    taskLabel = "Fix Grammar & Spell ✨",
                    timestampMs = 1_700_000_100_000L,
                ),
            ),
            customWords = listOf("Alhamdulillah", "MashaAllah"),
            lastUpdatedMs = 1_700_000_100_000L,
        )

        val restored = AiMemoryVault.mergeVaultStates(freshInstallInternal, externalDeviceMemory)
        restored.isGeminiReady shouldBe true
        restored.isChatGptReady shouldBe true
        restored.geminiAccountEmail shouldBe "salman@gmail.com"
        restored.chatGptAccountEmail shouldBe "salman@chatgpt.com"
        restored.learnedMemories.size shouldBe 1
        restored.customWords shouldContain "Alhamdulillah"
    }

    test("AiMemoryVault detectApiKeyInText detects Gemini AIza and OpenAI sk keys from clipboard") {
        val geminiClip = "Here is my key: AIzaSyD1234567890abcdefghijklmnopqrstuvwx"
        val detectedGemini = AiMemoryVault.detectApiKeyInText(geminiClip)
        detectedGemini shouldNotBe null
        detectedGemini!!.first shouldBe AiProviderType.GEMINI
        detectedGemini.second shouldBe "AIzaSyD1234567890abcdefghijklmnopqrstuvwx"

        val openAiClip = "sk-proj-1234567890abcdefghijklmnopqrstuvwxyzABCD"
        val detectedOpenAi = AiMemoryVault.detectApiKeyInText(openAiClip)
        detectedOpenAi shouldNotBe null
        detectedOpenAi!!.first shouldBe AiProviderType.CHATGPT
        detectedOpenAi.second shouldBe "sk-proj-1234567890abcdefghijklmnopqrstuvwxyzABCD"
    }

    test("LocalGrammarAndRewriteEngine fixes English and Roman Urdu grammar and spelling") {
        val fixedEnglish = LocalGrammarAndRewriteEngine.fixGrammarAndSpelling(
            "this are a apple and he dont recieve teh message",
        )
        fixedEnglish shouldBe "This is an apple and he doesn't receive the message"

        val fixedRomanUrdu = LocalGrammarAndRewriteEngine.fixGrammarAndSpelling(
            "mjy isku theek krna chahye tkay amsla hal ho",
        )
        fixedRomanUrdu shouldBe "Mujhe isko theek karna chahiye taake masla hal ho"

        val matches = LocalGrammarAndRewriteEngine.findSentenceGrammarMatches(
            "These is teh best featire and i is happy",
        )
        matches.shouldNotBeEmpty()

        val urduScript = LocalGrammarAndRewriteEngine.executeLocalFallback(
            task = AiWritingTask.TO_URDU_SCRIPT,
            inputText = "bohat shukriya aap kaise hain",
        )
        urduScript shouldContain "شکریہ"

        val userPromptFixed = LocalGrammarAndRewriteEngine.fixGrammarAndSpelling(
            "Kya bana ju task dia tha chatgpt aur demini ya Google account login ya chatgpt login editor kay leay ho gya hay? Test karo agr implwmr nai hoa tu kr do. Dubara sy",
        )
        userPromptFixed shouldBe "Kya bana jo task diya tha ChatGPT aur Gemini ya Google account login ya ChatGPT login editor kay liye ho gaya hai? Test karo agar implement nahi hua tu kar do. Dobara se"
    }
})

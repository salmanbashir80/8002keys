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

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryOverlay
import dev.patrickgold.florisboard.lib.FlorisLocale
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class AiProviderType(val displayName: String) {
    GEMINI("Google Gemini"),
    CHATGPT("OpenAI ChatGPT"),
    AUTO_HYBRID("Auto (Gemini + ChatGPT)"),
}

@Serializable
data class AiLearnedMemoryItem(
    val id: String,
    val originalText: String,
    val preferredText: String,
    val taskLabel: String,
    val timestampMs: Long,
)

@Serializable
data class AiHistoryEntry(
    val id: String,
    val taskLabel: String,
    val providerName: String,
    val inputText: String,
    val outputText: String,
    val timestampMs: Long,
)

@Serializable
data class AiVaultState(
    val geminiConnected: Boolean = false,
    val geminiAccountEmail: String = "",
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val chatGptConnected: Boolean = false,
    val chatGptAccountEmail: String = "",
    val chatGptApiKey: String = "",
    val chatGptModel: String = "gpt-4o-mini",
    val chatGptBaseUrl: String = "https://api.openai.com/v1",
    val preferredProvider: AiProviderType = AiProviderType.AUTO_HYBRID,
    val aiGrammarSpellCheckEnabled: Boolean = true,
    val autoLearnFromCorrections: Boolean = true,
    val autoPersistAcrossReinstalls: Boolean = true,
    val writingStyleMemory: String = "Keep my natural tone, fix grammar and spelling accurately, and support both English and Roman Urdu.",
    val learnedMemories: List<AiLearnedMemoryItem> = emptyList(),
    val customWords: List<String> = emptyList(),
    val recentHistory: List<AiHistoryEntry> = emptyList(),
    val lastUpdatedMs: Long = 0L,
    val lastRestoredFromDeviceMs: Long = 0L,
) {
    val hasAnyCloudProvider: Boolean
        get() = (geminiConnected && geminiApiKey.isNotBlank()) ||
            (chatGptConnected && chatGptApiKey.isNotBlank())

    val isGeminiReady: Boolean
        get() = geminiConnected && geminiApiKey.isNotBlank()

    val isChatGptReady: Boolean
        get() = chatGptConnected && chatGptApiKey.isNotBlank()

    fun activeProviderSummary(): String {
        return when {
            isGeminiReady && isChatGptReady -> "Gemini (${geminiModel}) + ChatGPT (${chatGptModel})"
            isGeminiReady -> "Google Gemini (${geminiModel})"
            isChatGptReady -> "OpenAI ChatGPT (${chatGptModel})"
            else -> "Smart Local Engine (Login Gemini / ChatGPT for Cloud AI)"
        }
    }

    /**
     * Formats the connected Google or ChatGPT account email in SwiftKey grid style
     * (e.g. "sal...@live.com" or "sal...@gmail.com").
     */
    fun swiftKeyAccountBadgeLabel(): String {
        val rawEmail = when {
            isGeminiReady && geminiAccountEmail.contains("@") -> geminiAccountEmail.trim()
            isChatGptReady && chatGptAccountEmail.contains("@") -> chatGptAccountEmail.trim()
            isGeminiReady && geminiAccountEmail.isNotBlank() -> geminiAccountEmail.trim()
            isChatGptReady && chatGptAccountEmail.isNotBlank() -> chatGptAccountEmail.trim()
            isGeminiReady -> "Google ✓"
            isChatGptReady -> "ChatGPT ✓"
            else -> "Google / GPT"
        }
        if (rawEmail.contains("@")) {
            val local = rawEmail.substringBefore("@")
            val domain = rawEmail.substringAfter("@")
            return if (local.length > 4) {
                "${local.take(3)}...@$domain"
            } else {
                "$local@$domain"
            }
        }
        return rawEmail.take(14)
    }
}

/**
 * Persistent Account & AI Memory Vault for 8002Keys.
 *
 * Stores connected Google Gemini and OpenAI ChatGPT accounts, user writing style memory,
 * learned corrections, and custom vocabulary across app sessions and reinstalls:
 *  1. Internal portable path `files/ime/keyboard/8002keys_ai_vault.json` (included in
 *     Android Auto-Backup `files/ime` and 8002Keys `.flbackup` archives).
 *  2. External persistent device storage `Download/8002Keys/8002keys_ai_vault.json` via
 *     `MediaStore.Downloads` (survives app uninstall and auto-restores on fresh install).
 */
object AiMemoryVault {
    private const val INTERNAL_REL_PATH = "ime/keyboard/8002keys_ai_vault.json"
    private const val EXTERNAL_SUBDIR = "8002Keys"
    private const val EXTERNAL_FILE_NAME = "8002keys_ai_vault.json"
    private const val ENVELOPE_PREFIX = "8002KEYS_VAULT_V1:"
    private const val MAX_LEARNED_MEMORIES = 120
    private const val MAX_CUSTOM_WORDS = 500
    private const val MAX_RECENT_HISTORY = 40

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _state = MutableStateFlow(AiVaultState())
    val state: StateFlow<AiVaultState> = _state.asStateFlow()

    private val _pendingLoginProvider = MutableStateFlow<AiProviderType?>(null)
    val pendingLoginProvider: StateFlow<AiProviderType?> = _pendingLoginProvider.asStateFlow()

    fun requestDirectLoginModal(provider: AiProviderType?) {
        _pendingLoginProvider.value = provider
    }

    @Volatile
    private var isInitialized = false

    val GeminiModels = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "gemini-2.0-flash",
    )

    val ChatGptModels = listOf(
        "gpt-4o-mini",
        "gpt-4o",
        "o3-mini",
    )

    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true
        val appContext = context.applicationContext
        scope.launch {
            val internalFile = File(appContext.filesDir, INTERNAL_REL_PATH)
            val internalState = readVaultFile(internalFile)
            val externalState = readExternalDeviceVault(appContext)
            val clipboardState = readClipboardVaultIfPresent(appContext)

            var resolved = when {
                internalState != null && externalState != null -> {
                    mergeVaultStates(internalState, externalState)
                }
                internalState != null -> internalState
                externalState != null -> externalState.copy(
                    lastRestoredFromDeviceMs = System.currentTimeMillis(),
                )
                else -> AiVaultState()
            }
            if (clipboardState != null) {
                resolved = mergeVaultStates(resolved, clipboardState).copy(
                    lastRestoredFromDeviceMs = System.currentTimeMillis(),
                )
            }
            _state.value = resolved
            writeVaultFile(internalFile, resolved)
            if (resolved.autoPersistAcrossReinstalls && resolved.hasAnyCloudProvider) {
                writeExternalDeviceVault(appContext, resolved)
            }
            hydrateCustomWordsIntoDictionary(
                (BUILTIN_TRILINGUAL_STARTER_WORDS + resolved.customWords).distinct(),
            )
        }
    }

    /**
     * Built-in Roman Urdu + English + Urdu Script (`اردو`) starter vocabulary baked into the APK
     * so every fresh install immediately knows common words across all three scripts while the
     * user's personal Google Account vault layers their own learned words on top.
     */
    private val BUILTIN_TRILINGUAL_STARTER_WORDS: List<String> = listOf(
        // Roman Urdu high-frequency vocabulary
        "Assalamu", "Alaikum", "InshaAllah", "MashaAllah", "Alhamdulillah", "JazakAllah",
        "Shukriya", "Meherbani", "Behtareen", "Chahiye", "Zaroor", "Bilkul", "Theek", "Accha",
        "Bohat", "Jaldi", "Samajh", "Masla", "Mujhe", "Aapko", "Aapka", "Aapki", "Tumhein",
        "Karna", "Karni", "Karne", "Karein", "Karoon", "Karwao", "Batao", "Batana", "Dikhao",
        "Likhna", "Bolna", "Milna", "Aana", "Jana", "Subah", "Shaam", "Waqt", "Zindagi",
        "Dost", "Bhai", "Yaar", "Ghar", "Kaam", "Baat", "Pehle", "Baad", "Saath", "Liye",
        "Upar", "Neeche", "Yahan", "Wahan", "Kahan", "Kaise", "Kyun", "Lekin", "Magar",
        "Agar", "Phir", "Abhi", "Sirf", "Dobara", "Taake", "Jaise", "Aise", "Isko", "Usko",
        // English high-frequency tech & chat vocabulary
        "Gemini", "ChatGPT", "WhatsApp", "Android", "Keyboard", "Dictionary", "Grammar",
        "Translate", "Assistant", "Memory", "Account", "Subscription", "Update", "Compact",
        // Urdu Script (اردو) high-frequency vocabulary
        "السلام", "علیکم", "انشاءاللہ", "ماشاءاللہ", "الحمدللہ", "شکریہ", "مہربانی",
        "بہترین", "چاہیے", "ضرور", "بالکل", "ٹھیک", "اچھا", "بہت", "جلدی", "سمجھ",
        "مسئلہ", "مجھے", "آپ", "کرنا", "بتائیں", "لکھنا", "بولنا", "ملنا", "وقت", "صبح",
        "شام", "دوست", "بھائی", "گھر", "کام", "بات", "پہلے", "بعد", "ساتھ", "لیے",
    )

    fun learnWordsFromText(context: Context, text: String) {
        val tokens = text.split(Regex("""[\s,.;:!?()\[\]"'\t]+"""))
            .map { it.trim() }
            .filter { it.length in 3..28 && it.any { ch -> ch.isLetter() } }
            .distinct()
            .take(250)
        if (tokens.isEmpty()) return
        val existingLower = _state.value.customWords.mapTo(HashSet()) { it.lowercase() }
        val newlyAdded = tokens.filter { it.lowercase() !in existingLower }
        if (newlyAdded.isEmpty()) return
        updateState(context) { current ->
            val merged = (newlyAdded + current.customWords)
                .distinctBy { it.lowercase() }
                .take(MAX_CUSTOM_WORDS)
            current.copy(customWords = merged)
        }
        scope.launch {
            hydrateCustomWordsIntoDictionary(newlyAdded)
        }
    }

    fun updateState(context: Context, transform: (AiVaultState) -> AiVaultState) {
        val appContext = context.applicationContext
        val updated = transform(_state.value).copy(
            lastUpdatedMs = System.currentTimeMillis(),
        )
        _state.value = updated
        scope.launch {
            val internalFile = File(appContext.filesDir, INTERNAL_REL_PATH)
            writeVaultFile(internalFile, updated)
            if (updated.autoPersistAcrossReinstalls) {
                writeExternalDeviceVault(appContext, updated)
            }
        }
    }

    fun connectGemini(
        context: Context,
        accountEmail: String,
        apiKey: String,
        model: String = _state.value.geminiModel,
    ) {
        val cleanKey = apiKey.trim()
        val cleanEmail = accountEmail.trim()
        updateState(context) { current ->
            current.copy(
                geminiConnected = cleanKey.isNotEmpty(),
                geminiAccountEmail = cleanEmail.ifEmpty { current.geminiAccountEmail },
                geminiApiKey = cleanKey,
                geminiModel = model.trim().ifEmpty { "gemini-2.5-flash" },
            )
        }
        // Auto-merge any previously saved dictionary & memories on this device for this Google Account
        scope.launch {
            val external = readExternalDeviceVault(context.applicationContext)
            if (external != null && (external.customWords.isNotEmpty() || external.learnedMemories.isNotEmpty())) {
                updateState(context) { current ->
                    mergeVaultStates(current, external).copy(
                        geminiConnected = cleanKey.isNotEmpty(),
                        geminiAccountEmail = cleanEmail.ifEmpty { current.geminiAccountEmail },
                        geminiApiKey = cleanKey,
                        lastRestoredFromDeviceMs = System.currentTimeMillis(),
                    )
                }
                hydrateCustomWordsIntoDictionary(
                    (BUILTIN_TRILINGUAL_STARTER_WORDS + _state.value.customWords).distinct(),
                )
            }
        }
    }

    fun disconnectGemini(context: Context) {
        updateState(context) { current ->
            current.copy(
                geminiConnected = false,
                geminiApiKey = "",
            )
        }
    }

    fun connectChatGpt(
        context: Context,
        accountEmail: String,
        apiKey: String,
        model: String = _state.value.chatGptModel,
        baseUrl: String = _state.value.chatGptBaseUrl,
    ) {
        val cleanKey = apiKey.trim()
        updateState(context) { current ->
            current.copy(
                chatGptConnected = cleanKey.isNotEmpty(),
                chatGptAccountEmail = accountEmail.trim().ifEmpty { "OpenAI ChatGPT Account" },
                chatGptApiKey = cleanKey,
                chatGptModel = model.trim().ifEmpty { "gpt-4o-mini" },
                chatGptBaseUrl = baseUrl.trim().trimEnd('/').ifEmpty { "https://api.openai.com/v1" },
            )
        }
    }

    fun disconnectChatGpt(context: Context) {
        updateState(context) { current ->
            current.copy(
                chatGptConnected = false,
                chatGptApiKey = "",
            )
        }
    }

    fun updateExecutionPreferences(
        context: Context,
        preferredProvider: AiProviderType = _state.value.preferredProvider,
        aiGrammarSpellCheckEnabled: Boolean = _state.value.aiGrammarSpellCheckEnabled,
        autoLearnFromCorrections: Boolean = _state.value.autoLearnFromCorrections,
        autoPersistAcrossReinstalls: Boolean = _state.value.autoPersistAcrossReinstalls,
        writingStyleMemory: String = _state.value.writingStyleMemory,
    ) {
        updateState(context) { current ->
            current.copy(
                preferredProvider = preferredProvider,
                aiGrammarSpellCheckEnabled = aiGrammarSpellCheckEnabled,
                autoLearnFromCorrections = autoLearnFromCorrections,
                autoPersistAcrossReinstalls = autoPersistAcrossReinstalls,
                writingStyleMemory = writingStyleMemory.trim().ifEmpty { current.writingStyleMemory },
            )
        }
    }

    fun recordTaskExecution(
        context: Context,
        taskLabel: String,
        providerName: String,
        inputText: String,
        outputText: String,
        isSensitiveOrIncognito: Boolean,
    ) {
        recordAiCorrectionAndMemory(
            context = context,
            taskLabel = taskLabel,
            providerName = providerName,
            inputText = inputText,
            outputText = outputText,
            isPrivateOrSensitive = isSensitiveOrIncognito,
        )
    }

    fun recordLearnedCorrection(
        context: Context,
        originalText: String,
        preferredText: String,
        taskLabel: String,
        isSensitiveOrIncognito: Boolean,
    ) {
        if (isSensitiveOrIncognito) return
        val cleanOrig = originalText.trim()
        val cleanPref = preferredText.trim()
        if (cleanPref.isEmpty()) return
        val now = System.currentTimeMillis()
        val words = extractLearnableWords(cleanPref)
        if (words.isNotEmpty()) {
            hydrateCustomWordsIntoDictionary(words)
        }
        updateState(context) { current ->
            val item = AiLearnedMemoryItem(
                id = "m_$now",
                originalText = cleanOrig.ifEmpty { "Preferred style" },
                preferredText = cleanPref,
                taskLabel = taskLabel,
                timestampMs = now,
            )
            current.copy(
                learnedMemories = (
                    listOf(item) + current.learnedMemories.filterNot {
                        it.originalText.equals(item.originalText, ignoreCase = true) &&
                            it.preferredText.equals(item.preferredText, ignoreCase = true)
                    }
                    ).take(MAX_LEARNED_MEMORIES),
                customWords = (words + current.customWords)
                    .map { it.trim() }
                    .filter { it.length in 2..36 }
                    .distinctBy { it.lowercase() }
                    .take(MAX_CUSTOM_WORDS),
            )
        }
    }

    fun recordAiCorrectionAndMemory(
        context: Context,
        taskLabel: String,
        providerName: String,
        inputText: String,
        outputText: String,
        isPrivateOrSensitive: Boolean,
    ) {
        if (isPrivateOrSensitive) return
        val cleanIn = inputText.trim()
        val cleanOut = outputText.trim()
        if (cleanOut.isEmpty()) return

        val now = System.currentTimeMillis()
        val extractedWords = extractLearnableWords(cleanOut)
        if (extractedWords.isNotEmpty()) {
            hydrateCustomWordsIntoDictionary(extractedWords)
        }

        updateState(context) { current ->
            val updatedHistory = (
                listOf(
                    AiHistoryEntry(
                        id = "h_$now",
                        taskLabel = taskLabel,
                        providerName = providerName,
                        inputText = cleanIn.take(300),
                        outputText = cleanOut.take(600),
                        timestampMs = now,
                    ),
                ) + current.recentHistory.filterNot { it.outputText == cleanOut }
                ).take(MAX_RECENT_HISTORY)

            val updatedMemories = if (current.autoLearnFromCorrections && cleanIn != cleanOut && cleanIn.length in 3..240) {
                (
                    listOf(
                        AiLearnedMemoryItem(
                            id = "m_$now",
                            originalText = cleanIn,
                            preferredText = cleanOut,
                            taskLabel = taskLabel,
                            timestampMs = now,
                        ),
                    ) + current.learnedMemories.filterNot {
                        it.originalText.equals(cleanIn, ignoreCase = true)
                    }
                    ).take(MAX_LEARNED_MEMORIES)
            } else {
                current.learnedMemories
            }

            val updatedWords = if (current.autoLearnFromCorrections) {
                (extractedWords + current.customWords)
                    .map { it.trim() }
                    .filter { it.length in 2..36 }
                    .distinctBy { it.lowercase() }
                    .take(MAX_CUSTOM_WORDS)
            } else {
                current.customWords
            }

            current.copy(
                learnedMemories = updatedMemories,
                customWords = updatedWords,
                recentHistory = updatedHistory,
            )
        }
    }

    fun addManualMemoryNote(context: Context, noteOrCorrection: String) {
        val trimmed = noteOrCorrection.trim()
        if (trimmed.isEmpty()) return
        val now = System.currentTimeMillis()
        val words = extractLearnableWords(trimmed)
        hydrateCustomWordsIntoDictionary(words)
        updateState(context) { current ->
            val item = AiLearnedMemoryItem(
                id = "m_$now",
                originalText = "User preference",
                preferredText = trimmed,
                taskLabel = "Custom Memory",
                timestampMs = now,
            )
            current.copy(
                learnedMemories = (listOf(item) + current.learnedMemories).take(MAX_LEARNED_MEMORIES),
                customWords = (words + current.customWords).distinctBy { it.lowercase() }.take(MAX_CUSTOM_WORDS),
            )
        }
    }

    fun removeLearnedMemory(context: Context, id: String) {
        updateState(context) { current ->
            current.copy(learnedMemories = current.learnedMemories.filterNot { it.id == id })
        }
    }

    fun clearHistory(context: Context) {
        updateState(context) { current ->
            current.copy(recentHistory = emptyList())
        }
    }

    fun saveToPersistentStorageNow(context: Context): Boolean {
        return runCatching {
            val appContext = context.applicationContext
            val current = _state.value
            writeVaultFile(File(appContext.filesDir, INTERNAL_REL_PATH), current)
            writeExternalDeviceVault(appContext, current)
            true
        }.getOrDefault(false)
    }

    fun restoreFromPersistentStorageNow(context: Context): Boolean {
        return runCatching {
            val appContext = context.applicationContext
            val external = readExternalDeviceVault(appContext) ?: return false
            val merged = mergeVaultStates(_state.value, external).copy(
                lastRestoredFromDeviceMs = System.currentTimeMillis(),
            )
            _state.value = merged
            writeVaultFile(File(appContext.filesDir, INTERNAL_REL_PATH), merged)
            hydrateCustomWordsIntoDictionary(merged.customWords)
            true
        }.getOrDefault(false)
    }

    suspend fun saveToDeviceStorageNow(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val current = _state.value
            val internalFile = File(appContext.filesDir, INTERNAL_REL_PATH)
            writeVaultFile(internalFile, current)
            writeExternalDeviceVault(appContext, current)
            "Download/$EXTERNAL_SUBDIR/$EXTERNAL_FILE_NAME"
        }
    }

    suspend fun restoreFromDeviceStorageNow(context: Context): Result<AiVaultState> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val external = readExternalDeviceVault(appContext)
                ?: error("No saved 8002Keys AI memory vault found in Download/$EXTERNAL_SUBDIR/$EXTERNAL_FILE_NAME")
            val merged = mergeVaultStates(_state.value, external).copy(
                lastRestoredFromDeviceMs = System.currentTimeMillis(),
            )
            _state.value = merged
            writeVaultFile(File(appContext.filesDir, INTERNAL_REL_PATH), merged)
            hydrateCustomWordsIntoDictionary(merged.customWords)
            merged
        }
    }

    fun exportPortableVaultCode(): String = exportVaultJsonString()

    fun importPortableVaultCode(context: Context, rawText: String): Boolean {
        return importVaultJsonString(context, rawText).isSuccess
    }

    fun exportVaultJsonString(): String {
        return encodeEnvelope(_state.value)
    }

    fun importVaultJsonString(context: Context, rawText: String): Result<AiVaultState> {
        return runCatching {
            val imported = decodeEnvelope(rawText)
                ?: error("Invalid 8002Keys AI memory backup format")
            val merged = mergeVaultStates(_state.value, imported).copy(
                lastRestoredFromDeviceMs = System.currentTimeMillis(),
            )
            _state.value = merged
            val appContext = context.applicationContext
            scope.launch {
                writeVaultFile(File(appContext.filesDir, INTERNAL_REL_PATH), merged)
                writeExternalDeviceVault(appContext, merged)
                hydrateCustomWordsIntoDictionary(merged.customWords)
            }
            merged
        }
    }

    /**
     * Detects a Google Gemini (`AIza...`) or OpenAI ChatGPT (`sk-...`) API key in clipboard text
     * so the user can 1-tap connect right after logging in on Google AI Studio or OpenAI.
     */
    fun detectApiKeyInText(text: String?): Pair<AiProviderType, String>? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        val geminiRegex = Regex("""\b(AIza[0-9A-Za-z_-]{33,45})\b""")
        geminiRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { key ->
            return AiProviderType.GEMINI to key
        }
        val openAiRegex = Regex("""\b(sk-[A-Za-z0-9_-]{20,220})\b""")
        openAiRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { key ->
            return AiProviderType.CHATGPT to key
        }
        return null
    }

    fun buildMemoryContextPrompt(maxItems: Int = 8): String {
        val current = _state.value
        return buildString {
            if (current.writingStyleMemory.isNotBlank()) {
                appendLine("User writing style & instructions: ${current.writingStyleMemory.trim()}")
            }
            if (current.learnedMemories.isNotEmpty()) {
                appendLine("User's remembered corrections & phrasing preferences:")
                current.learnedMemories.take(maxItems).forEach { item ->
                    appendLine("- [${item.taskLabel}] \"${item.originalText}\" -> \"${item.preferredText}\"")
                }
            }
            if (current.customWords.isNotEmpty()) {
                appendLine(
                    "User's personal vocabulary (preserve exact spelling when relevant): " +
                        current.customWords.take(30).joinToString(", "),
                )
            }
        }.trim()
    }

    internal fun mergeVaultStates(primary: AiVaultState, secondary: AiVaultState): AiVaultState {
        val newer = if (secondary.lastUpdatedMs > primary.lastUpdatedMs) secondary else primary
        val older = if (newer === primary) secondary else primary

        val geminiKey = newer.geminiApiKey.ifBlank { older.geminiApiKey }
        val chatGptKey = newer.chatGptApiKey.ifBlank { older.chatGptApiKey }
        val memories = (newer.learnedMemories + older.learnedMemories)
            .distinctBy { "${it.originalText.lowercase()}->${it.preferredText.lowercase()}" }
            .sortedByDescending { it.timestampMs }
            .take(MAX_LEARNED_MEMORIES)
        val words = (newer.customWords + older.customWords)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MAX_CUSTOM_WORDS)
        val history = (newer.recentHistory + older.recentHistory)
            .distinctBy { "${it.taskLabel}:${it.outputText}" }
            .sortedByDescending { it.timestampMs }
            .take(MAX_RECENT_HISTORY)

        return newer.copy(
            geminiConnected = geminiKey.isNotBlank(),
            geminiAccountEmail = newer.geminiAccountEmail.ifBlank { older.geminiAccountEmail },
            geminiApiKey = geminiKey,
            geminiModel = newer.geminiModel.ifBlank { older.geminiModel },
            chatGptConnected = chatGptKey.isNotBlank(),
            chatGptAccountEmail = newer.chatGptAccountEmail.ifBlank { older.chatGptAccountEmail },
            chatGptApiKey = chatGptKey,
            chatGptModel = newer.chatGptModel.ifBlank { older.chatGptModel },
            chatGptBaseUrl = newer.chatGptBaseUrl.ifBlank { older.chatGptBaseUrl },
            writingStyleMemory = newer.writingStyleMemory.ifBlank { older.writingStyleMemory },
            learnedMemories = memories,
            customWords = words,
            recentHistory = history,
        )
    }

    private fun extractLearnableWords(text: String): List<String> {
        return Regex("""[\p{L}]{3,28}""")
            .findAll(text)
            .map { it.value }
            .filter { word -> word.any { it.isLetter() } }
            .distinctBy { it.lowercase() }
            .take(15)
            .toList()
    }

    private fun hydrateCustomWordsIntoDictionary(words: List<String>) {
        if (words.isEmpty()) return
        try {
            val overlay = UserDictionaryOverlay.get()
            val enLocale = FlorisLocale.from("en", "US")
            val urRomanLocale = FlorisLocale.from("ur", "PK", "roman")
            words.forEach { word ->
                overlay.learn(word, enLocale)
                overlay.learn(word, urRomanLocale)
            }
            val dictManager = DictionaryManager.default()
            dictManager.loadUserDictionariesIfNecessary()
            val dao = dictManager.florisUserDictionaryDao() ?: return
            words.take(40).forEach { word ->
                if (dao.queryExact(word, enLocale).isEmpty()) {
                    dao.insert(
                        UserDictionaryEntry(
                            id = 0,
                            word = word,
                            freq = 220,
                            locale = enLocale.languageTag(),
                            shortcut = null,
                        ),
                    )
                }
            }
        } catch (_: Throwable) {
            // Safe fallback in unit test environments where Room DB is not initialized
        }
    }

    internal fun encodeVaultEnvelope(state: AiVaultState): String = encodeEnvelope(state)

    internal fun decodeVaultEnvelope(raw: String): AiVaultState? = decodeEnvelope(raw)

    private fun encodeEnvelope(state: AiVaultState): String {
        val rawJson = json.encodeToString(state)
        val encoded = Base64.getEncoder().encodeToString(rawJson.toByteArray(StandardCharsets.UTF_8))
        return "$ENVELOPE_PREFIX$encoded"
    }

    private fun decodeEnvelope(raw: String): AiVaultState? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val jsonPayload = if (trimmed.startsWith(ENVELOPE_PREFIX)) {
                val b64 = trimmed.removePrefix(ENVELOPE_PREFIX).trim()
                String(Base64.getMimeDecoder().decode(b64), StandardCharsets.UTF_8)
            } else {
                trimmed
            }
            json.decodeFromString<AiVaultState>(jsonPayload)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readVaultFile(file: File): AiVaultState? {
        if (!file.isFile || !file.canRead()) return null
        return try {
            decodeEnvelope(file.readText(StandardCharsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeVaultFile(file: File, state: AiVaultState) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(encodeEnvelope(state), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
        }
    }

    private fun readClipboardVaultIfPresent(context: Context): AiVaultState? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clipText = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                ?.trim()
                .orEmpty()
            if (clipText.startsWith(ENVELOPE_PREFIX)) {
                decodeEnvelope(clipText)
            } else {
                val detected = detectApiKeyInText(clipText)
                if (detected != null) {
                    val (prov, key) = detected
                    if (prov == AiProviderType.GEMINI) {
                        AiVaultState(
                            geminiConnected = true,
                            geminiAccountEmail = "Google Gemini Account",
                            geminiApiKey = key,
                            lastUpdatedMs = System.currentTimeMillis(),
                        )
                    } else {
                        AiVaultState(
                            chatGptConnected = true,
                            chatGptAccountEmail = "OpenAI ChatGPT Account",
                            chatGptApiKey = key,
                            lastUpdatedMs = System.currentTimeMillis(),
                        )
                    }
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readExternalDeviceVault(context: Context): AiVaultState? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collections = listOf(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                MediaStore.Files.getContentUri("external"),
            )
            for (collection in collections) {
                try {
                    val resolver = context.contentResolver
                    val projection = arrayOf(MediaStore.MediaColumns._ID)
                    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                    val selectionArgs = arrayOf(EXTERNAL_FILE_NAME, "8002keys_ai_vault%")
                    resolver.query(
                        collection,
                        projection,
                        selection,
                        selectionArgs,
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            val uri = ContentUris.withAppendedId(collection, id)
                            val text = resolver.openInputStream(uri)?.use { stream ->
                                stream.bufferedReader(StandardCharsets.UTF_8).readText()
                            }
                            val decoded = text?.let(::decodeEnvelope)
                            if (decoded != null) return decoded
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }
        val candidateDirs = listOfNotNull(
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) }.getOrNull(),
            File("/storage/emulated/0/Download"),
            File("/storage/emulated/0/Documents"),
        )
        for (dir in candidateDirs) {
            try {
                val file = File(File(dir, EXTERNAL_SUBDIR), EXTERNAL_FILE_NAME)
                val state = readVaultFile(file)
                if (state != null) return state
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun writeExternalDeviceVault(context: Context, state: AiVaultState) {
        val payload = encodeEnvelope(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(EXTERNAL_FILE_NAME)
                var existingId: Long? = null
                resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        existingId = cursor.getLong(0)
                    }
                }
                val targetUri = if (existingId != null) {
                    ContentUris.withAppendedId(collection, existingId!!)
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, EXTERNAL_FILE_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            "${Environment.DIRECTORY_DOWNLOADS}/$EXTERNAL_SUBDIR",
                        )
                    }
                    resolver.insert(collection, values)
                }
                if (targetUri != null) {
                    resolver.openOutputStream(targetUri, "wt")?.use { out ->
                        out.write(payload.toByteArray(StandardCharsets.UTF_8))
                    }
                    return
                }
            } catch (_: Throwable) {
            }
        }
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val fallbackFile = File(File(downloadsDir, EXTERNAL_SUBDIR), EXTERNAL_FILE_NAME)
            writeVaultFile(fallbackFile, state)
        } catch (_: Throwable) {
        }
    }
}

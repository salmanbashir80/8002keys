/*
 * Copyright (C) 2026 Salman Bashir (8002Keys)
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

package dev.patrickgold.florisboard.ime.voice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.ime.ai.AiMemoryVault
import dev.patrickgold.florisboard.ime.ai.AiWritingTask
import dev.patrickgold.florisboard.ime.ai.CloudAiClient
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

enum class VoiceAiOutputMode(
    val shortLabel: String,
    val aiTask: AiWritingTask?,
) {
    TO_ROMAN_URDU("🇵🇰 Roman Urdu", AiWritingTask.ENGLISH_TO_ROMAN_URDU),
    AUTO_FIX("✨ AI Auto-Fix", AiWritingTask.FIX_GRAMMAR_SPELLING),
    TO_ENGLISH("🇬🇧 English", AiWritingTask.ROMAN_URDU_TO_ENGLISH),
    TO_URDU("🇵🇰 اردو", AiWritingTask.TO_URDU_SCRIPT),
    RAW("🎤 Direct", null),
}

enum class VoiceAiSpeakLanguage(
    val label: String,
    val bcp47: String,
) {
    AUTO("Auto", ""),
    URDU_PK("اردو (PK)", "ur-PK"),
    ENGLISH_US("English", "en-US"),
}

/**
 * Thread-safe bridge that hands polished voice-to-text transcripts from [AiVoiceTypingActivity]
 * back to [FlorisImeService] as soon as the host app (e.g. WhatsApp) regains input focus.
 */
object VoiceAiCommitBridge {
    private const val TTL_MS = 20_000L

    private data class PendingCommit(
        val text: String,
        val createdAtMs: Long,
    )

    private val pending = AtomicReference<PendingCommit?>(null)

    @Volatile
    var lastSelectedMode: VoiceAiOutputMode = VoiceAiOutputMode.TO_ROMAN_URDU

    @Volatile
    var lastSelectedLanguage: VoiceAiSpeakLanguage = VoiceAiSpeakLanguage.URDU_PK

    fun enqueue(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        pending.set(PendingCommit(clean, System.currentTimeMillis()))
    }

    fun consume(): String? {
        val item = pending.getAndSet(null) ?: return null
        if ((System.currentTimeMillis() - item.createdAtMs) > TTL_MS) {
            return null
        }
        return item.text
    }
}

/**
 * Built-in Gemini & ChatGPT AI Voice Typing Activity.
 *
 * Launches Android's native neural speech recognizer (zero external voice keyboard install needed),
 * polishes or converts spoken Urdu/English into Roman Urdu, Urdu script, or English using
 * Google Gemini / ChatGPT, and saves learned phrases into [AiMemoryVault].
 */
class AiVoiceTypingActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_AUTO_START = "extra_auto_start"

        fun launch(context: Context, autoStartListening: Boolean = true): Boolean {
            return try {
                val intent = Intent(context, AiVoiceTypingActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    putExtra(EXTRA_AUTO_START, autoStartListening)
                }
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        AiMemoryVault.initialize(this)
        val autoStart = intent?.getBooleanExtra(EXTRA_AUTO_START, true) ?: true

        setContent {
            AiVoiceTypingOverlay(
                autoStart = autoStart,
                onCommitAndClose = { finalText ->
                    AiMemoryVault.learnWordsFromText(this@AiVoiceTypingActivity, finalText)
                    VoiceAiCommitBridge.enqueue(finalText)
                    FlorisImeService.flushPendingVoiceAiCommit()
                    finish()
                },
                onClose = { finish() },
            )
        }
    }

    @Composable
    private fun AiVoiceTypingOverlay(
        autoStart: Boolean,
        onCommitAndClose: (String) -> Unit,
        onClose: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val vaultState by AiMemoryVault.state.collectAsState()

        var selectedMode by remember { mutableStateOf(VoiceAiCommitBridge.lastSelectedMode) }
        var selectedLanguage by remember { mutableStateOf(VoiceAiCommitBridge.lastSelectedLanguage) }
        var isProcessingAi by remember { mutableStateOf(false) }
        var spokenPreview by remember { mutableStateOf("") }
        var statusLine by remember { mutableStateOf("Tap Speak Now — Gemini / ChatGPT Roman Urdu & English Voice") }
        var autoLaunchTriggered by remember { mutableStateOf(false) }

        val speechLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val rawSpoken = matches?.firstOrNull()?.trim().orEmpty()
                if (rawSpoken.isEmpty()) {
                    statusLine = "No speech detected. Tap 'Speak Now' to try again."
                    return@rememberLauncherForActivityResult
                }
                spokenPreview = rawSpoken
                val aiTask = selectedMode.aiTask
                if (aiTask == null) {
                    onCommitAndClose(rawSpoken)
                } else {
                    isProcessingAi = true
                    statusLine = "Converting with Gemini / ChatGPT (${selectedMode.shortLabel})..."
                    scope.launch {
                        val aiRes = CloudAiClient.executeTask(
                            context = this@AiVoiceTypingActivity,
                            task = aiTask,
                            inputText = rawSpoken,
                        )
                        isProcessingAi = false
                        val finalOut = aiRes.outputText.trim().ifBlank { rawSpoken }
                        AiMemoryVault.recordLearnedCorrection(
                            context = this@AiVoiceTypingActivity,
                            originalText = rawSpoken,
                            preferredText = finalOut,
                            taskLabel = "Voice ${selectedMode.shortLabel}",
                            isSensitiveOrIncognito = false,
                        )
                        onCommitAndClose(finalOut)
                    }
                }
            } else {
                statusLine = "Choose Roman Urdu, Urdu, or English below, then tap 'Speak Now'."
            }
        }

        var speechUnavailable by remember { mutableStateOf(false) }

        fun openVoiceSettings() {
            val intents = listOf(
                Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS),
                Intent(android.provider.Settings.ACTION_SETTINGS),
            )
            for (intent in intents) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { startActivity(intent) }.isSuccess) return
            }
        }

        fun openUrlInChrome(url: String) {
            val uri = android.net.Uri.parse(url)
            val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val genericIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { startActivity(chromeIntent) }.isFailure) {
                runCatching { startActivity(genericIntent) }
            }
        }

        fun openGoogleSpeechInstaller() {
            openUrlInChrome("https://play.google.com/store/apps/details?id=com.google.android.tts")
        }

        fun openFutoVoiceDownloadInChrome() {
            openUrlInChrome("https://voiceinput.futo.org/")
        }

        fun startNativeSpeechRecognizer() {
            val langTag = selectedLanguage.bcp47.ifBlank { Locale.getDefault().toLanguageTag() }
            val recognizeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    "8002Keys Voice AI (${selectedMode.shortLabel}) — Speak Now",
                )
            }
            runCatching {
                speechUnavailable = false
                speechLauncher.launch(recognizeIntent)
            }.onFailure {
                speechUnavailable = true
                statusLine = "Voice typing needs Google Speech enabled — tap a button below to enable in 1 tap:"
            }
        }

        LaunchedEffect(autoStart) {
            if (autoStart && !autoLaunchTriggered) {
                autoLaunchTriggered = true
                startNativeSpeechRecognizer()
            }
        }

        val bgOverlay = Color(0xAA000000)
        val cardBg = Color(0xFF141821)
        val chipBg = Color(0xFF232A3B)
        val activeGreen = Color(0xFF059669)
        val activeBlue = Color(0xFF2563EB)
        val textWhite = Color(0xFFF8FAFC)
        val textMuted = Color(0xFFCBD5E1)

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(bgOverlay)
                .clickable { if (!isProcessingAi) onClose() },
            color = Color.Transparent,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(cardBg)
                        .border(1.5.dp, activeBlue, RoundedCornerShape(20.dp))
                        .clickable(enabled = false) {}
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(24.dp),
                            )
                            Column {
                                val headerTitle = "8002Keys AI Voice Typing"
                                Text(
                                    text = headerTitle,
                                    color = textWhite,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                val engineLine = when {
                                    vaultState.isGeminiReady && vaultState.isChatGptReady ->
                                        "Gemini + ChatGPT Voice AI Active ✓"
                                    vaultState.isGeminiReady ->
                                        "Google Gemini Voice AI Active ✓"
                                    vaultState.isChatGptReady ->
                                        "ChatGPT Voice AI Active ✓"
                                    else ->
                                        "Neural Voice + Grammar AI Active ✓"
                                }
                                Text(
                                    text = engineLine,
                                    color = Color(0xFF34D399),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(chipBg)
                                .clickable { onClose() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = textWhite,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // Speak Language Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VoiceAiSpeakLanguage.entries.forEach { lang ->
                            val isSelected = selectedLanguage == lang
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) activeBlue else chipBg)
                                    .clickable {
                                        selectedLanguage = lang
                                        VoiceAiCommitBridge.lastSelectedLanguage = lang
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = lang.label,
                                    color = textWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }

                    // Output AI Mode Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VoiceAiOutputMode.entries.forEach { mode ->
                            val isSelected = selectedMode == mode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) activeGreen else chipBg)
                                    .clickable {
                                        selectedMode = mode
                                        VoiceAiCommitBridge.lastSelectedMode = mode
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = mode.shortLabel,
                                    color = textWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }

                    if (isProcessingAi) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                                color = Color(0xFF34D399),
                            )
                            Text(
                                text = statusLine,
                                color = Color(0xFF34D399),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        Text(
                            text = if (spokenPreview.isNotBlank()) "\"$spokenPreview\"" else statusLine,
                            color = textMuted,
                            fontSize = 14.sp,
                        )
                    }

                    val compactBtnPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)

                    if (speechUnavailable) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { openGoogleSpeechInstaller() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 46.dp),
                                    contentPadding = compactBtnPadding,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                ) {
                                    val googleSpeechLbl = "🌐 Google Speech"
                                    Text(
                                        text = googleSpeechLbl,
                                        color = textWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Button(
                                    onClick = { openFutoVoiceDownloadInChrome() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 46.dp),
                                    contentPadding = compactBtnPadding,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                                ) {
                                    val futoLbl = "🌐 Offline Voice"
                                    Text(
                                        text = futoLbl,
                                        color = textWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Button(
                                onClick = { openVoiceSettings() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 44.dp),
                                contentPadding = compactBtnPadding,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = activeBlue),
                            ) {
                                val openSettingsLbl = "⚙️ Open Phone Voice Settings"
                                Text(
                                    text = openSettingsLbl,
                                    color = textWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Primary Speak Now Button Row (zero clipping!)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { startNativeSpeechRecognizer() },
                            enabled = !isProcessingAi,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                            contentPadding = compactBtnPadding,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = activeGreen),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            val speakNowLabel = "Speak Now"
                            Text(
                                text = speakNowLabel,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        OutlinedButton(
                            onClick = { openVoiceSettings() },
                            enabled = !isProcessingAi,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            val settingsShort = "⚙️"
                            Text(settingsShort, color = textWhite, fontSize = 15.sp, maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = onClose,
                            enabled = !isProcessingAi,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            val cancelLabel = "Close"
                            Text(cancelLabel, color = textWhite, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

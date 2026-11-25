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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showShortToastSync
import org.florisboard.lib.snygg.ui.SnyggColumn

fun launchAiAssistantSettings(context: Context) {
    FlorisImeService.hideUi()
    runCatching {
        val intent = Intent(context, FlorisAppActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("ui://florisboard/settings/ai-assistant")
        }
        context.startActivity(intent)
    }.onFailure {
        FlorisImeService.launchSettings()
    }
}

private fun openExternalBrowserUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        context.showShortToastSync("Could not open browser: $url")
    }
}

@Composable
fun AiAssistantInputLayout(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val editorInstance by context.editorInstance()
    val clipboardManager by context.clipboardManager()
    val windowController = FlorisImeService.windowControllerOrNull()
    val scope = rememberCoroutineScope()

    val vaultState by AiMemoryVault.state.collectAsState()
    val activeContent by editorInstance.activeContentFlow.collectAsState()
    val primaryClip by clipboardManager.primaryClipFlow.collectAsState()

    val sourceEditorText = remember(activeContent) {
        activeContent.selectedText.ifBlank {
            activeContent.textBeforeSelection.takeLast(600)
        }
    }
    val usingSelection = activeContent.selectedText.isNotBlank()

    var selectedTask by remember { mutableStateOf(AiWritingTask.FIX_GRAMMAR_SPELLING) }
    var customInstruction by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<AiExecutionResult?>(null) }
    var lastSourceTextUsed by remember { mutableStateOf("") }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    val clipboardText = primaryClip?.text?.toString().orEmpty()
    val detectedClipboardKey = remember(clipboardText) {
        AiMemoryVault.detectApiKeyInText(clipboardText)
    }

    LaunchedEffect(Unit) {
        AiMemoryVault.initialize(context)
    }

    fun runAiTask(task: AiWritingTask, customPrompt: String = customInstruction) {
        if (isRunning) return
        val inputForTask = sourceEditorText.ifBlank {
            if (customPrompt.isNotBlank()) "" else clipboardText.take(400)
        }
        selectedTask = task
        lastSourceTextUsed = sourceEditorText
        isRunning = true
        statusBanner = null
        scope.launch {
            val info = editorInstance.activeInfo
            val res = CloudAiClient.executeTask(
                context = context,
                task = task,
                inputText = inputForTask,
                customInstruction = customPrompt,
                inputType = info.base.inputType,
                imeOptions = info.base.imeOptions,
                isIncognito = keyboardManager.activeState.isIncognitoMode,
            )
            lastResult = res
            statusBanner = res.warningMessage
            isRunning = false
        }
    }

    val panelBg = Color(0xFF101722)
    val cardBg = Color(0xFF192231)
    val accentTeal = Color(0xFF10B981)
    val accentBlue = Color(0xFF3B82F6)
    val accentAmber = Color(0xFFF59E0B)
    val textPrimary = Color(0xFFF8FAFC)
    val textSecondary = Color(0xFF94A3B8)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.imeUiHeight())
            .background(panelBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // 1. Header Bar: Back, Engine Status Toggle, Resize Keyboard, Login & Memory Settings
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Back to keyboard
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardBg)
                        .clickable { keyboardManager.transitionToImeUiMode(ImeUiMode.TEXT) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to keyboard",
                            tint = textPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text("ABC", color = textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Active AI Engine Switcher Badge
                val providerBadgeText = when (vaultState.preferredProvider) {
                    AiProviderType.AUTO_HYBRID -> when {
                        vaultState.isGeminiReady && vaultState.isChatGptReady -> "AI: Gemini + ChatGPT ✓"
                        vaultState.isGeminiReady -> "AI: Gemini ✓"
                        vaultState.isChatGptReady -> "AI: ChatGPT ✓"
                        else -> "AI: Local (Tap Login)"
                    }
                    AiProviderType.GEMINI -> if (vaultState.isGeminiReady) "Gemini ✓" else "Gemini (Login)"
                    AiProviderType.CHATGPT -> if (vaultState.isChatGptReady) "ChatGPT ✓" else "ChatGPT (Login)"
                }
                val badgeColor = if (vaultState.hasAnyCloudProvider) accentTeal else accentAmber
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.18f))
                        .border(1.dp, badgeColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable {
                            val next = when (vaultState.preferredProvider) {
                                AiProviderType.AUTO_HYBRID -> AiProviderType.GEMINI
                                AiProviderType.GEMINI -> AiProviderType.CHATGPT
                                AiProviderType.CHATGPT -> AiProviderType.AUTO_HYBRID
                            }
                            AiMemoryVault.updateExecutionPreferences(
                                context = context,
                                preferredProvider = next,
                            )
                            context.showShortToastSync("Switched AI provider to ${next.displayName}")
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = providerBadgeText,
                        color = badgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // 1-Tap Resize Keyboard shortcut
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardBg)
                        .clickable {
                            keyboardManager.transitionToImeUiMode(ImeUiMode.TEXT)
                            windowController?.editor?.enable()
                        }
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "Resize ⤢",
                        color = textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // Login / Memory Settings button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentBlue.copy(alpha = 0.22f))
                        .border(1.dp, accentBlue.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable { launchAiAssistantSettings(context) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "AI Login & Memory Settings",
                            tint = textPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "Login / Memory",
                            color = textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(5.dp))

        // 2. Scrollable Content Area
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Instant 1-tap Clipboard API Key Detector Banner (if user copied Gemini AIza... or OpenAI sk-... key)
            if (detectedClipboardKey != null) {
                val (detectedProvider, detectedKey) = detectedClipboardKey
                val alreadySaved = (detectedProvider == AiProviderType.GEMINI && vaultState.geminiApiKey == detectedKey) ||
                    (detectedProvider == AiProviderType.CHATGPT && vaultState.chatGptApiKey == detectedKey)
                if (!alreadySaved) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(accentTeal.copy(alpha = 0.2f))
                            .border(1.dp, accentTeal, RoundedCornerShape(8.dp))
                            .clickable {
                                if (detectedProvider == AiProviderType.GEMINI) {
                                    AiMemoryVault.connectGemini(
                                        context = context,
                                        accountEmail = vaultState.geminiAccountEmail.ifBlank { "Google Gemini Account" },
                                        apiKey = detectedKey,
                                        model = vaultState.geminiModel,
                                    )
                                } else {
                                    AiMemoryVault.connectChatGpt(
                                        context = context,
                                        accountEmail = vaultState.chatGptAccountEmail.ifBlank { "OpenAI ChatGPT Account" },
                                        apiKey = detectedKey,
                                        model = vaultState.chatGptModel,
                                        baseUrl = vaultState.chatGptBaseUrl,
                                    )
                                }
                                statusBanner = "${detectedProvider.displayName} logged in & saved to persistent memory!"
                                context.showShortToastSync("${detectedProvider.displayName} connected & saved to phone memory!")
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "✨ ${detectedProvider.displayName} Key detected on clipboard! Tap here to Login & Save",
                            color = textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "CONNECT",
                            color = accentTeal,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }

            // Quick Login / Restore bar when neither Gemini nor ChatGPT is logged in yet
            if (!vaultState.hasAnyCloudProvider) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardBg)
                        .border(1.dp, accentAmber.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = "Connect Google Gemini & OpenAI ChatGPT (Memory persists across reinstalls):",
                        color = textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AiSmallActionChip(
                            label = "1. Google Gemini Login",
                            color = accentBlue,
                            onClick = {
                                openExternalBrowserUrl(context, "https://aistudio.google.com/app/apikey")
                            },
                        )
                        AiSmallActionChip(
                            label = "2. ChatGPT Login",
                            color = accentTeal,
                            onClick = {
                                openExternalBrowserUrl(context, "https://platform.openai.com/api-keys")
                            },
                        )
                        AiSmallActionChip(
                            label = "Paste Copied Key",
                            color = accentAmber,
                            onClick = {
                                val sysClip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val rawClip = sysClip?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                    ?.takeIf { it.isNotBlank() }
                                    ?: clipboardText
                                val detected = AiMemoryVault.detectApiKeyInText(rawClip)
                                if (detected != null) {
                                    val (prov, key) = detected
                                    if (prov == AiProviderType.GEMINI) {
                                        AiMemoryVault.connectGemini(context, "Google Gemini", key, vaultState.geminiModel)
                                    } else {
                                        AiMemoryVault.connectChatGpt(context, "OpenAI ChatGPT", key, vaultState.chatGptModel, vaultState.chatGptBaseUrl)
                                    }
                                    statusBanner = "${prov.displayName} connected & saved to phone memory!"
                                    context.showShortToastSync("${prov.displayName} connected!")
                                } else {
                                    launchAiAssistantSettings(context)
                                }
                            },
                        )
                        AiSmallActionChip(
                            label = "Restore Old Memory",
                            color = Color(0xFF8B5CF6),
                            onClick = {
                                val restored = AiMemoryVault.restoreFromPersistentStorageNow(context)
                                statusBanner = if (restored) {
                                    "Restored saved accounts & memory from phone storage!"
                                } else {
                                    "No external memory backup found yet in Downloads/8002Keys."
                                }
                                context.showShortToastSync(statusBanner!!)
                            },
                        )
                    }
                }
            }

            // 3. 1-Tap AI Writing, Grammar, Spell & Roman Urdu Action Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AiWritingTask.entries.forEach { task ->
                    val isSelected = selectedTask == task
                    val chipBg = if (isSelected) accentBlue else cardBg
                    val chipBorder = if (isSelected) accentBlue else Color(0xFF334155)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(chipBg)
                            .border(1.dp, chipBorder, RoundedCornerShape(16.dp))
                            .clickable {
                                if (task == AiWritingTask.CUSTOM_PROMPT) {
                                    // Use current editor text or clipboard as custom prompt
                                    val promptText = sourceEditorText.ifBlank { clipboardText.take(400) }
                                    runAiTask(AiWritingTask.CUSTOM_PROMPT, customPrompt = promptText)
                                } else {
                                    runAiTask(task, customPrompt = "")
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = task.chipLabel,
                            color = textPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }

            // 4. Quick Custom Instruction presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Quick AI:",
                    color = textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val quickPrompts = listOf(
                    "Fix Grammar (Keep Roman Urdu)" to "Fix spelling and clarity while keeping natural Roman Urdu + English mix.",
                    "Polite WhatsApp Reply" to "Write a polite, friendly WhatsApp reply to this message.",
                    "Bullet Points" to "Convert this text into clean, concise bullet points.",
                    "Explain Simply" to "Explain or answer this clearly in 2 short sentences.",
                    "Ask AI from Clipboard" to clipboardText.take(300),
                )
                for ((label, promptDirective) in quickPrompts) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E293B))
                            .clickable {
                                if (promptDirective.isNotBlank()) {
                                    customInstruction = promptDirective
                                    runAiTask(AiWritingTask.CUSTOM_PROMPT, customPrompt = promptDirective)
                                } else {
                                    context.showShortToastSync("Clipboard is empty")
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = label,
                            color = Color(0xFFCBD5E1),
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            // 5. Result / Editor Preview Card + 1-Tap Apply Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(cardBg)
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val headerLabel = when {
                        isRunning -> "Running ${selectedTask.chipLabel}..."
                        lastResult != null -> "${selectedTask.chipLabel} • ${lastResult?.providerUsed}"
                        sourceEditorText.isNotBlank() -> if (usingSelection) {
                            "Selected Text (${sourceEditorText.length} chars) — Tap any AI action above"
                        } else {
                            "Editor Text (${sourceEditorText.length} chars) — Tap any AI action above"
                        }
                        else -> "Type or select text in the app, then tap Fix Grammar & Spell ✨"
                    }
                    Text(
                        text = headerLabel,
                        color = if (lastResult?.usedCloud == true) accentTeal else textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = accentTeal,
                        )
                    }
                }

                val displayedText = lastResult?.outputText?.takeIf { it.isNotBlank() }
                    ?: sourceEditorText.ifBlank { "No text in editor yet. Switch to ABC, type your sentence in Roman Urdu or English, and tap 'Fix Grammar & Spell ✨'!" }

                Text(
                    text = displayedText,
                    color = textPrimary,
                    fontSize = 12.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                if (!statusBanner.isNullOrBlank()) {
                    Text(
                        text = statusBanner!!,
                        color = accentAmber,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Action buttons row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val activeOut = lastResult?.outputText?.takeIf { it.isNotBlank() }

                    // 1-Tap Fix Grammar right now if not run yet, or Replace in App if result is ready
                    if (activeOut != null) {
                        // Replace Text in App
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(accentTeal)
                                .clickable {
                                    val content = editorInstance.activeContent
                                    val targetToReplace = lastSourceTextUsed.ifBlank { sourceEditorText }
                                    if (content.selectedText.isNotEmpty()) {
                                        editorInstance.commitText(activeOut)
                                    } else if (targetToReplace.isNotEmpty() && content.selection.isValid) {
                                        val replaceStart = (content.selection.end - targetToReplace.length).coerceAtLeast(0)
                                        editorInstance.setSelection(replaceStart, content.selection.end)
                                        editorInstance.commitText(activeOut)
                                    } else {
                                        editorInstance.commitText(activeOut)
                                    }
                                    if (!keyboardManager.activeState.isIncognitoMode && targetToReplace.isNotBlank()) {
                                        AiMemoryVault.recordLearnedCorrection(
                                            context = context,
                                            originalText = targetToReplace,
                                            preferredText = activeOut,
                                            taskLabel = selectedTask.chipLabel,
                                            isSensitiveOrIncognito = false,
                                        )
                                    }
                                    context.showShortToastSync("Replaced & learned in AI Memory!")
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Replace in App",
                                tint = Color.Black,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Replace in App",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        // Insert at Cursor
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(accentBlue)
                                .clickable {
                                    val content = editorInstance.activeContent
                                    if (content.selectedText.isNotEmpty() && content.selection.isValid) {
                                        editorInstance.setSelection(content.selection.end, content.selection.end)
                                    }
                                    val prefix = if (content.textBeforeSelection.isNotEmpty() && !content.textBeforeSelection.endsWith(" ")) " " else ""
                                    editorInstance.commitText(prefix + activeOut)
                                }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Insert at Cursor",
                                tint = textPrimary,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "Insert",
                                color = textPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        // Copy Result
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF334155))
                                .clickable {
                                    val sysClip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    sysClip?.setPrimaryClip(ClipData.newPlainText("8002Keys AI", activeOut))
                                    context.showShortToastSync("Copied AI text to clipboard")
                                }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = textPrimary,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "Copy",
                                color = textPrimary,
                                fontSize = 11.sp,
                            )
                        }

                        // Save to Persistent Memory
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF334155))
                                .clickable {
                                    AiMemoryVault.recordLearnedCorrection(
                                        context = context,
                                        originalText = lastSourceTextUsed.ifBlank { sourceEditorText },
                                        preferredText = activeOut,
                                        taskLabel = selectedTask.chipLabel,
                                        isSensitiveOrIncognito = keyboardManager.activeState.isIncognitoMode,
                                    )
                                    context.showShortToastSync("Saved to persistent AI Memory (${vaultState.learnedMemories.size + 1} items)")
                                }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Restore,
                                contentDescription = "Remember in AI Memory",
                                tint = accentAmber,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "Remember 🧠",
                                color = textPrimary,
                                fontSize = 11.sp,
                            )
                        }
                    } else {
                        // Instant 1-Tap Fix Grammar button
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(accentTeal)
                                .clickable { runAiTask(AiWritingTask.FIX_GRAMMAR_SPELLING) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Fix Grammar & Spelling Now",
                                tint = Color.Black,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Fix Grammar & Spelling Now ✨",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiSmallActionChip(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.22f))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = Color(0xFFF8FAFC),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Live AI Grammar, Spell Check, Roman Urdu ↔ English, and Resize Quick Strip
 * displayed directly at the top of the main typing keyboard (`TextInputLayout`).
 *
 * Automatically checks the user's active sentence for grammar and spelling errors
 * (using Gemini / ChatGPT when logged in, plus instant Local AI Grammar Engine)
 * and lets the user fix, translate, polish, or resize with a single tap while typing.
 */
@Composable
fun LiveAiSmartStrip(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val editorInstance by context.editorInstance()
    val clipboardManager by context.clipboardManager()
    val windowController = FlorisImeService.windowControllerOrNull()
    val scope = rememberCoroutineScope()

    val vaultState by AiMemoryVault.state.collectAsState()
    val activeContent by editorInstance.activeContentFlow.collectAsState()
    val primaryClip by clipboardManager.primaryClipFlow.collectAsState()

    LaunchedEffect(Unit) {
        AiMemoryVault.initialize(context)
    }

    if (!vaultState.aiGrammarSpellCheckEnabled) return

    val activeSentence = remember(activeContent) {
        activeContent.selectedText.ifBlank {
            activeContent.textBeforeSelection
                .substringAfterLast('\n')
                .takeLast(280)
        }
    }

    val clipboardText = primaryClip?.text?.toString().orEmpty()
    val detectedKey = remember(clipboardText) {
        AiMemoryVault.detectApiKeyInText(clipboardText)
    }

    var liveSuggestion by remember { mutableStateOf<Pair<String, String>?>(null) }
    var isWorking by remember { mutableStateOf(false) }

    // Real-time automatic grammar & spell check as user types
    LaunchedEffect(activeSentence, vaultState.hasAnyCloudProvider, vaultState.preferredProvider) {
        val clean = activeSentence.trim()
        if (clean.length < 4) {
            liveSuggestion = null
            return@LaunchedEffect
        }
        val info = editorInstance.activeInfo
        if (dev.patrickgold.florisboard.ime.smartcompose.SensitiveFieldGuard.isSensitive(info.base.inputType, info.base.imeOptions)) {
            liveSuggestion = null
            return@LaunchedEffect
        }

        // 1. Instant local grammar & spelling check (< 2ms)
        val localFixed = LocalGrammarAndRewriteEngine.fixGrammarAndSpelling(clean)
        if (localFixed.isNotBlank() && !localFixed.equals(clean, ignoreCase = false)) {
            liveSuggestion = clean to localFixed
        } else {
            liveSuggestion = null
        }

        // 2. Debounced Cloud AI (Gemini / ChatGPT) grammar check when logged in & user pauses typing
        val wordCount = clean.split(Regex("""\s+""")).size
        if (vaultState.hasAnyCloudProvider && wordCount >= 3 && !keyboardManager.activeState.isIncognitoMode) {
            delay(1150L)
            val res = CloudAiClient.executeTask(
                context = context,
                task = AiWritingTask.FIX_GRAMMAR_SPELLING,
                inputText = clean,
                inputType = info.base.inputType,
                imeOptions = info.base.imeOptions,
                isIncognito = false,
            )
            val cloudOut = res.outputText.trim()
            if (res.usedCloud && cloudOut.isNotBlank() && cloudOut != clean) {
                liveSuggestion = clean to cloudOut
            }
        }
    }

    fun applyTextInEditor(targetToReplace: String, newText: String, taskLabel: String) {
        val content = editorInstance.activeContent
        if (content.selectedText.isNotEmpty()) {
            editorInstance.commitText(newText)
        } else if (targetToReplace.isNotEmpty() && content.selection.isValid) {
            val replaceStart = (content.selection.end - targetToReplace.length).coerceAtLeast(0)
            editorInstance.setSelection(replaceStart, content.selection.end)
            editorInstance.commitText(newText)
        } else {
            editorInstance.commitText(newText)
        }
        if (!keyboardManager.activeState.isIncognitoMode && targetToReplace.isNotBlank()) {
            AiMemoryVault.recordLearnedCorrection(
                context = context,
                originalText = targetToReplace,
                preferredText = newText,
                taskLabel = taskLabel,
                isSensitiveOrIncognito = false,
            )
        }
    }

    fun executeInPlaceTask(task: AiWritingTask) {
        if (isWorking) return
        val target = activeSentence
        if (target.isBlank()) {
            keyboardManager.transitionToImeUiMode(ImeUiMode.AI_ASSISTANT)
            return
        }
        isWorking = true
        scope.launch {
            val info = editorInstance.activeInfo
            val res = CloudAiClient.executeTask(
                context = context,
                task = task,
                inputText = target,
                inputType = info.base.inputType,
                imeOptions = info.base.imeOptions,
                isIncognito = keyboardManager.activeState.isIncognitoMode,
            )
            isWorking = false
            val out = res.outputText.trim()
            if (out.isNotEmpty()) {
                applyTextInEditor(target, out, task.chipLabel)
                liveSuggestion = null
            }
        }
    }

    val stripBg = Color(0xFF0F172A)
    val pillBg = Color(0xFF1E293B)
    val accentTeal = Color(0xFF10B981)
    val accentBlue = Color(0xFF3B82F6)
    val accentAmber = Color(0xFFF59E0B)
    val textWhite = Color(0xFFF8FAFC)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(stripBg)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A. If a Gemini or ChatGPT key was just copied to clipboard, offer 1-tap connect right on the bar
        if (detectedKey != null) {
            val (prov, key) = detectedKey
            val isUnsaved = (prov == AiProviderType.GEMINI && vaultState.geminiApiKey != key) ||
                (prov == AiProviderType.CHATGPT && vaultState.chatGptApiKey != key)
            if (isUnsaved) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentTeal)
                        .clickable {
                            if (prov == AiProviderType.GEMINI) {
                                AiMemoryVault.connectGemini(context, "Google Gemini", key, vaultState.geminiModel)
                            } else {
                                AiMemoryVault.connectChatGpt(context, "OpenAI ChatGPT", key, vaultState.chatGptModel, vaultState.chatGptBaseUrl)
                            }
                            context.showShortToastSync("${prov.displayName} connected & saved to memory!")
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "✨ Connect ${prov.displayName} Key",
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }

        // B. Live Auto-Detected Grammar / Spelling Correction Pill (1-tap replaces sentence in editor!)
        val suggestion = liveSuggestion
        if (suggestion != null) {
            val (orig, fixed) = suggestion
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentTeal.copy(alpha = 0.22f))
                    .border(1.dp, accentTeal, RoundedCornerShape(12.dp))
                    .clickable {
                        applyTextInEditor(orig, fixed, "Live Grammar Fix")
                        liveSuggestion = null
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "✨ Fix: \"${fixed.take(42)}\" ✓",
                    color = Color(0xFF6EE7B7),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }

        // C. 1-Tap In-Place AI Grammar Fix Pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (isWorking) accentAmber.copy(alpha = 0.25f) else accentBlue.copy(alpha = 0.25f))
                .border(1.dp, if (isWorking) accentAmber else accentBlue, RoundedCornerShape(12.dp))
                .clickable { executeInPlaceTask(AiWritingTask.FIX_GRAMMAR_SPELLING) }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = if (isWorking) "AI Fixing..." else "✨ AI Fix",
                color = textWhite,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // D. 1-Tap In-Place Roman Urdu -> Clean English
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(pillBg)
                .clickable { executeInPlaceTask(AiWritingTask.ROMAN_URDU_TO_ENGLISH) }
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text("Roman → EN", color = textWhite, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }

        // E. 1-Tap In-Place English -> Roman Urdu
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(pillBg)
                .clickable { executeInPlaceTask(AiWritingTask.ENGLISH_TO_ROMAN_URDU) }
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text("EN → Roman", color = textWhite, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }

        // F. 1-Tap In-Place To Urdu Script (اردو)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(pillBg)
                .clickable { executeInPlaceTask(AiWritingTask.TO_URDU_SCRIPT) }
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text("اردو", color = textWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }

        // G. 1-Tap In-Place Polish & Improve
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(pillBg)
                .clickable { executeInPlaceTask(AiWritingTask.IMPROVE_WRITING) }
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text("Polish", color = textWhite, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }

        // H. 1-Tap In-Place Smart Reply
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(pillBg)
                .clickable { executeInPlaceTask(AiWritingTask.WHATSAPP_REPLY) }
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text("Reply 💬", color = textWhite, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }

        // I. Open Full AI Studio Panel
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(accentTeal.copy(alpha = 0.2f))
                .border(1.dp, accentTeal.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .clickable { keyboardManager.transitionToImeUiMode(ImeUiMode.AI_ASSISTANT) }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text("AI Studio ▾", color = textWhite, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }

        // J. 1-Tap Keyboard Resize Toggle right on the main keyboard bar
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(pillBg)
                .clickable { windowController?.editor?.toggleEnabled() }
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text("⤢ Resize", color = textWhite, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }

        // K. Provider Login Status / Settings Shortcut
        val loginLabel = when {
            vaultState.isGeminiReady && vaultState.isChatGptReady -> "Gemini+GPT ✓"
            vaultState.isGeminiReady -> "Gemini ✓"
            vaultState.isChatGptReady -> "ChatGPT ✓"
            else -> "Login AI ⚙️"
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (vaultState.hasAnyCloudProvider) pillBg else accentAmber.copy(alpha = 0.25f))
                .clickable { launchAiAssistantSettings(context) }
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text(
                text = loginLabel,
                color = if (vaultState.hasAnyCloudProvider) Color(0xFF94A3B8) else accentAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

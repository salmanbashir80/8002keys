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

package dev.patrickgold.florisboard.ime.ai

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
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.update.AppUpdateManager
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.keyboardManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showShortToastSync

private const val MAX_AI_EDITOR_CHARS = 2500

data class LastAiFixSnapshot(
    val originalText: String,
    val fixedText: String,
    val providerUsed: String,
    val taskLabel: String,
    val changedWordCount: Int,
) {
    fun shortProviderBadge(): String {
        val lower = providerUsed.lowercase()
        return when {
            "gemini" in lower -> "Gemini"
            "chatgpt" in lower || "openai" in lower -> "ChatGPT"
            "translate" in lower -> "Google Translate"
            "languagetool" in lower -> "Grammar AI"
            else -> "8002Keys AI"
        }
    }
}

object LastAiFixSession {
    private val _latestFix = MutableStateFlow<LastAiFixSnapshot?>(null)
    val latestFix: StateFlow<LastAiFixSnapshot?> = _latestFix.asStateFlow()

    fun record(
        originalText: String,
        fixedText: String,
        providerUsed: String,
        taskLabel: String,
    ): LastAiFixSnapshot {
        val diff = AiDiffHighlighter.buildHighlightedDiff(originalText, fixedText)
        val snapshot = LastAiFixSnapshot(
            originalText = originalText,
            fixedText = fixedText,
            providerUsed = providerUsed,
            taskLabel = taskLabel,
            changedWordCount = diff.changedWordCount,
        )
        _latestFix.value = snapshot
        return snapshot
    }

    fun clear() {
        _latestFix.value = null
    }
}

private fun extractFullEditorText(activeContent: EditorContent): String {
    if (activeContent.selectedText.isNotBlank()) {
        return activeContent.selectedText.take(MAX_AI_EDITOR_CHARS)
    }
    return activeContent.textBeforeSelection.takeLast(MAX_AI_EDITOR_CHARS)
}

private fun replaceTextSmartInEditor(
    context: Context,
    editorInstance: EditorInstance,
    isIncognito: Boolean,
    targetToReplace: String,
    newText: String,
    taskLabel: String,
) {
    val content = editorInstance.activeContent
    if (content.selectedText.isNotEmpty()) {
        editorInstance.commitText(newText)
    } else if (targetToReplace.isNotEmpty() && content.selection.isValid) {
        val before = content.textBeforeSelection
        val exactLen = when {
            before.endsWith(targetToReplace) -> targetToReplace.length
            before.trimEnd().endsWith(targetToReplace.trim()) -> {
                val trailingWs = before.length - before.trimEnd().length
                targetToReplace.trim().length + trailingWs
            }
            else -> targetToReplace.length
        }
        val replaceStart = (content.selection.end - exactLen).coerceAtLeast(0)
        editorInstance.setSelection(replaceStart, content.selection.end)
        editorInstance.commitText(newText)
    } else {
        editorInstance.commitText(newText)
    }
    if (!isIncognito && targetToReplace.isNotBlank()) {
        AiMemoryVault.recordLearnedCorrection(
            context = context,
            originalText = targetToReplace.trim(),
            preferredText = newText,
            taskLabel = taskLabel,
            isSensitiveOrIncognito = false,
        )
    }
}

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

/**
 * Full Bottom AI Assistant Arena (`ImeUiMode.AI_ASSISTANT`).
 *
 * Designed with large, high-contrast user-friendly fonts (14sp–17sp), intelligent long-text
 * support (up to 2,500 chars), explicit Google Gemini / ChatGPT attribution, and Light-Green
 * diff highlighting so the user immediately sees every word that was fixed.
 */
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
    val sharedLastFix by LastAiFixSession.latestFix.collectAsState()

    val sourceEditorText = remember(activeContent) {
        extractFullEditorText(activeContent)
    }
    val clipboardText = primaryClip?.text?.toString().orEmpty().trim()

    var selectedTask by remember { mutableStateOf(AiWritingTask.FIX_GRAMMAR_SPELLING) }
    var isRunning by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<AiExecutionResult?>(null) }
    var originalTextBeforeApply by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        AiMemoryVault.initialize(context)
    }

    fun runTaskAndApply(task: AiWritingTask, overrideInput: String? = null) {
        if (isRunning) return
        val rawEditorSnapshot = sourceEditorText
        val trimmedEditorSnapshot = rawEditorSnapshot.trim()
        val inputForTask = (overrideInput ?: trimmedEditorSnapshot).ifBlank {
            clipboardText.take(MAX_AI_EDITOR_CHARS)
        }.trim()

        if (inputForTask.isBlank()) {
            statusMessage = "Message box is empty. Tap '← Keyboard (ABC)' to type or tap a quick phrase below."
            return
        }

        selectedTask = task
        isRunning = true
        statusMessage = null
        scope.launch {
            val info = editorInstance.activeInfo
            val res = CloudAiClient.executeTask(
                context = context,
                task = task,
                inputText = inputForTask,
                inputType = info.base.inputType,
                imeOptions = info.base.imeOptions,
                isIncognito = keyboardManager.activeState.isIncognitoMode,
            )
            lastResult = res
            isRunning = false
            val out = res.outputText.trim()
            if (out.isNotEmpty()) {
                val origForDiff = trimmedEditorSnapshot.ifBlank { inputForTask }
                originalTextBeforeApply = origForDiff
                replaceTextSmartInEditor(
                    context = context,
                    editorInstance = editorInstance,
                    isIncognito = keyboardManager.activeState.isIncognitoMode,
                    targetToReplace = rawEditorSnapshot,
                    newText = out,
                    taskLabel = task.chipLabel,
                )
                val snap = LastAiFixSession.record(
                    originalText = origForDiff,
                    fixedText = out,
                    providerUsed = res.providerUsed,
                    taskLabel = task.chipLabel,
                )
                statusMessage = if (snap.changedWordCount > 0) {
                    "✨ Fixed by ${res.providerUsed} • ${snap.changedWordCount} word(s) highlighted"
                } else {
                    "✓ Verified by ${res.providerUsed}"
                }
            }
        }
    }

    val bgDark = Color(0xFF12151E)
    val cardDark = Color(0xFF1E2433)
    val btnBlue = Color(0xFF2563EB)
    val btnEmerald = Color(0xFF059669)
    val btnIndigo = Color(0xFF4F46E5)
    val btnTeal = Color(0xFF0D9488)
    val btnSlate = Color(0xFF2D3748)
    val textWhite = Color(0xFFF8FAFC)
    val textMuted = Color(0xFFCBD5E1)
    val accentGreen = Color(0xFF34D399)
    val accentAmber = Color(0xFFFBBF24)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.imeUiHeight())
            .background(bgDark)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1. Compact Header Row: ← ABC, 🎤 Voice, ⤢ Resize, ✓ Account
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Compact "← ABC" Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(btnBlue)
                        .clickable { keyboardManager.transitionToImeUiMode(ImeUiMode.TEXT) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = textWhite,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        val backLabel = "ABC"
                        Text(
                            text = backLabel,
                            color = textWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Quick Voice AI Button (Roman Urdu / English / Urdu)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFDC2626))
                        .clickable {
                            FlorisImeService.switchToVoiceInputMethod(showFailureToast = true)
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    val voiceTopLabel = "🎤 Voice"
                    Text(
                        text = voiceTopLabel,
                        color = textWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(btnSlate)
                        .clickable {
                            keyboardManager.transitionToImeUiMode(ImeUiMode.TEXT)
                            windowController?.editor?.enable()
                        }
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                ) {
                    val resizeLabel = "⤢"
                    Text(
                        text = resizeLabel,
                        color = textWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                val accountText = if (vaultState.hasAnyCloudProvider) {
                    "✓ ${vaultState.swiftKeyAccountBadgeLabel()}"
                } else {
                    "⚙️ Login"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (vaultState.hasAnyCloudProvider) btnEmerald else accentAmber.copy(alpha = 0.25f))
                        .clickable { launchAiAssistantSettings(context) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = accountText,
                        color = if (vaultState.hasAnyCloudProvider) textWhite else accentAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 2. TOP SECTION: Smart Message Reader + 1-Tap Auto-Suggested Message + Light-Green Diff
        val readableSource = sourceEditorText.trim().ifBlank { clipboardText }
        val autoSmartSuggestion = remember(readableSource) {
            if (readableSource.length < 3) {
                null
            } else {
                val fixed = LocalGrammarAndRewriteEngine.fixGrammarAndSpelling(readableSource)
                if (fixed.isNotBlank() && fixed != readableSource) {
                    fixed
                } else if (sourceEditorText.isBlank() && clipboardText.isNotBlank()) {
                    // Clipboard has an incoming message -> offer instant smart reply
                    LocalGrammarAndRewriteEngine.executeLocalFallback(
                        task = AiWritingTask.WHATSAPP_REPLY,
                        input = clipboardText,
                    )
                } else {
                    null
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cardDark)
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                .padding(11.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (isRunning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.5.dp,
                            color = accentGreen,
                        )
                        val workingText = "AI Processing (${selectedTask.chipLabel})..."
                        Text(
                            text = workingText,
                            color = accentGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    val activeSnapshot = sharedLastFix
                    val activeFixedText = lastResult?.outputText?.takeIf { it.isNotBlank() }
                        ?: activeSnapshot?.fixedText?.takeIf { it.isNotBlank() }
                    val activeOriginalText = originalTextBeforeApply
                        ?: activeSnapshot?.originalText
                    val activeProvider = lastResult?.providerUsed
                        ?: activeSnapshot?.providerUsed

                    // Auto-generated 1-Tap Paste Smart Suggestion Card (reads editor or clipboard)
                    if (!autoSmartSuggestion.isNullOrBlank()) {
                        val suggestionTitle = if (sourceEditorText.isBlank() && clipboardText.isNotBlank()) {
                            "✨ Smart Reply Ready — Tap to Paste"
                        } else {
                            "✨ Smart Fix Ready — Tap to Paste"
                        }
                        val autoDiff = remember(readableSource, autoSmartSuggestion) {
                            AiDiffHighlighter.buildHighlightedDiff(readableSource, autoSmartSuggestion)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF064E3B))
                                .border(1.dp, Color(0xFF34D399), RoundedCornerShape(10.dp))
                                .clickable {
                                    val rawTarget = sourceEditorText
                                    originalTextBeforeApply = readableSource
                                    replaceTextSmartInEditor(
                                        context = context,
                                        editorInstance = editorInstance,
                                        isIncognito = keyboardManager.activeState.isIncognitoMode,
                                        targetToReplace = rawTarget,
                                        newText = autoSmartSuggestion,
                                        taskLabel = "Smart Paste",
                                    )
                                    LastAiFixSession.record(
                                        originalText = readableSource,
                                        fixedText = autoSmartSuggestion,
                                        providerUsed = if (vaultState.isGeminiReady) "Google Gemini" else "8002Keys Smart AI",
                                        taskLabel = "Smart Paste",
                                    )
                                    statusMessage = "✓ Pasted smart message"
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = suggestionTitle,
                                    color = Color(0xFF86EFAC),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                Text(
                                    text = if (sourceEditorText.isNotBlank()) autoDiff.annotatedText else androidx.compose.ui.text.AnnotatedString(autoSmartSuggestion),
                                    color = textWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 20.sp,
                                )
                            }
                        }
                    } else if (!activeFixedText.isNullOrBlank() && !activeOriginalText.isNullOrBlank()) {
                        val diffResult = remember(activeOriginalText, activeFixedText) {
                            AiDiffHighlighter.buildHighlightedDiff(activeOriginalText, activeFixedText)
                        }
                        val headerBadge = if (diffResult.changedWordCount > 0) {
                            "✨ Fixed by ${activeProvider ?: "8002Keys AI"} • ${diffResult.changedWordCount} change(s) in green"
                        } else {
                            "✓ Verified by ${activeProvider ?: "8002Keys AI"}"
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF064E3B))
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = headerBadge,
                                color = Color(0xFF86EFAC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = diffResult.annotatedText,
                            color = textWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 21.sp,
                        )
                    } else if (readableSource.isNotBlank()) {
                        val headerHint = if (readableSource.length > 180) {
                            "📄 Reading long message (${readableSource.length} chars)"
                        } else {
                            "📄 Current Message (tap any button below)"
                        }
                        Text(
                            text = headerHint,
                            color = Color(0xFF93C5FD),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = readableSource,
                            color = textWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 20.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        val emptyGuide = "Type or tap 🎤 Voice to speak — or tap a quick message to paste:"
                        Text(
                            text = emptyGuide,
                            color = textMuted,
                            fontSize = 12.sp,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(
                                "Assalamu Alaikum, kya haal hai?",
                                "Ji bilkul, abhi karta hoon.",
                                "I am on my way, see you soon!",
                            ).forEach { sample ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(btnSlate)
                                        .clickable { editorInstance.commitText(sample) }
                                        .padding(horizontal = 9.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = "+ $sample",
                                        color = textWhite,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. BOTTOM SECTION: All Action Buttons Cleanly Below the Text Box
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(btnBlue)
                    .clickable { runTaskAndApply(AiWritingTask.FIX_GRAMMAR_SPELLING) }
                    .padding(horizontal = 6.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label1 = "✨ Fix"
                Text(
                    text = label1,
                    color = textWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(btnEmerald)
                    .clickable { runTaskAndApply(AiWritingTask.TO_URDU_SCRIPT) }
                    .padding(horizontal = 6.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label2 = "🇵🇰 Urdu"
                Text(
                    text = label2,
                    color = textWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(btnIndigo)
                    .clickable { runTaskAndApply(AiWritingTask.ROMAN_URDU_TO_ENGLISH) }
                    .padding(horizontal = 6.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label3 = "🇬🇧 Eng"
                Text(
                    text = label3,
                    color = textWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(btnTeal)
                    .clickable { runTaskAndApply(AiWritingTask.ENGLISH_TO_ROMAN_URDU) }
                    .padding(horizontal = 6.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label4 = "🇵🇰 Roman"
                Text(
                    text = label4,
                    color = textWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 4. Secondary Compact Row: Smart Reply, Professional, Undo
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(btnSlate)
                    .clickable { runTaskAndApply(AiWritingTask.WHATSAPP_REPLY) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val replyLabel = "💬 Smart Reply"
                Text(
                    replyLabel,
                    color = textWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(btnSlate)
                    .clickable { runTaskAndApply(AiWritingTask.TONE_PROFESSIONAL) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val proLabel = "✨ Professional"
                Text(
                    proLabel,
                    color = textWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val undoTarget = originalTextBeforeApply ?: sharedLastFix?.originalText
            if (!undoTarget.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentAmber.copy(alpha = 0.25f))
                        .border(1.dp, accentAmber, RoundedCornerShape(10.dp))
                        .clickable {
                            replaceTextSmartInEditor(
                                context = context,
                                editorInstance = editorInstance,
                                isIncognito = keyboardManager.activeState.isIncognitoMode,
                                targetToReplace = sourceEditorText,
                                newText = undoTarget,
                                taskLabel = "Undo",
                            )
                            originalTextBeforeApply = null
                            LastAiFixSession.clear()
                            statusMessage = "↩ Reverted to original text"
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val undoLabel = "↩ Undo"
                    Text(undoLabel, color = accentAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Large-Font, Easy-to-Read On-Keyboard AI Bar (`LiveAiSmartStrip`).
 *
 * Appears right above the typing keyboard (`ABC`) with 13sp–14sp bold buttons so the user
 * can fix grammar (short or long multi-paragraph text up to 2,500 chars), translate in Urdu (اردو),
 * translate in English, use Gemini/ChatGPT Voice AI, or inspect Light-Green highlighted diffs.
 */
@Composable
fun LiveAiSmartStrip(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val editorInstance by context.editorInstance()
    val windowController = FlorisImeService.windowControllerOrNull()
    val scope = rememberCoroutineScope()

    val vaultState by AiMemoryVault.state.collectAsState()
    val activeContent by editorInstance.activeContentFlow.collectAsState()
    val latestUpdate by AppUpdateManager.latestUpdateFlow.collectAsState()
    val sharedLastFix by LastAiFixSession.latestFix.collectAsState()

    LaunchedEffect(Unit) {
        AiMemoryVault.initialize(context)
        AppUpdateManager.triggerBackgroundCheck(
            force = false,
            autoPopupIfAvailable = false,
        )
    }

    if (!vaultState.aiGrammarSpellCheckEnabled) return

    val activeSentence = remember(activeContent) {
        activeContent.selectedText.ifBlank {
            activeContent.textBeforeSelection
                .substringAfterLast('\n')
                .takeLast(280)
        }
    }

    val fullEditorText = remember(activeContent) {
        extractFullEditorText(activeContent)
    }

    var liveSuggestion by remember { mutableStateOf<Pair<String, String>?>(null) }
    var isWorking by remember { mutableStateOf(false) }

    // Real-time automatic grammar & spell check as user types
    LaunchedEffect(activeSentence) {
        val clean = activeSentence.trim()
        if (clean.length < 3) {
            liveSuggestion = null
            return@LaunchedEffect
        }
        val info = editorInstance.activeInfo
        if (dev.patrickgold.florisboard.ime.smartcompose.SensitiveFieldGuard.isSensitive(info.base.inputType, info.base.imeOptions)) {
            liveSuggestion = null
            return@LaunchedEffect
        }

        val localFixed = LocalGrammarAndRewriteEngine.fixGrammarAndSpelling(clean)
        if (localFixed.isNotBlank() && !localFixed.equals(clean, ignoreCase = false)) {
            liveSuggestion = clean to localFixed
        } else {
            liveSuggestion = null
        }
    }

    fun executeInPlaceTask(task: AiWritingTask) {
        if (isWorking) return
        val rawTarget = fullEditorText
        val trimmedTarget = rawTarget.trim()
        if (trimmedTarget.isBlank()) {
            keyboardManager.transitionToImeUiMode(ImeUiMode.AI_ASSISTANT)
            return
        }
        isWorking = true
        scope.launch {
            val info = editorInstance.activeInfo
            val res = CloudAiClient.executeTask(
                context = context,
                task = task,
                inputText = trimmedTarget,
                inputType = info.base.inputType,
                imeOptions = info.base.imeOptions,
                isIncognito = keyboardManager.activeState.isIncognitoMode,
            )
            isWorking = false
            val out = res.outputText.trim()
            if (out.isNotEmpty()) {
                replaceTextSmartInEditor(
                    context = context,
                    editorInstance = editorInstance,
                    isIncognito = keyboardManager.activeState.isIncognitoMode,
                    targetToReplace = rawTarget,
                    newText = out,
                    taskLabel = task.chipLabel,
                )
                LastAiFixSession.record(
                    originalText = trimmedTarget,
                    fixedText = out,
                    providerUsed = res.providerUsed,
                    taskLabel = task.chipLabel,
                )
                liveSuggestion = null
            }
        }
    }

    val stripBg = Color(0xFF141414)
    val pillBg = Color(0xFF262626)
    val accentTeal = Color(0xFF059669)
    val accentBlue = Color(0xFF2563EB)
    val accentIndigo = Color(0xFF4F46E5)
    val accentAmber = Color(0xFFF59E0B)
    val textWhite = Color(0xFFF8FAFC)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(stripBg)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. Compact SwiftKey 4-Column Grid Menu Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(pillBg)
                .border(1.dp, Color(0xFF404040), RoundedCornerShape(8.dp))
                .clickable {
                    keyboardManager.activeState.isActionsOverflowVisible =
                        !keyboardManager.activeState.isActionsOverflowVisible
                }
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            val menuLabel = "⊞"
            Text(
                text = menuLabel,
                color = textWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 1b. Compact Built-in Gemini & ChatGPT AI Voice Typing Button (Roman Urdu / English / Urdu)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFDC2626))
                .clickable {
                    FlorisImeService.switchToVoiceInputMethod(showFailureToast = true)
                }
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            val voiceAiLabel = "🎤"
            Text(
                text = voiceAiLabel,
                color = textWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 1c. Compact Light-Green Diff Pill (tap to view highlighted changes + Undo)
        val latestFix = sharedLastFix
        if (latestFix != null) {
            val diffBadge = if (latestFix.changedWordCount > 0) {
                "✓ ${latestFix.changedWordCount} (${latestFix.shortProviderBadge()})"
            } else {
                "✓ ${latestFix.shortProviderBadge()}"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF86EFAC))
                    .clickable {
                        keyboardManager.transitionToImeUiMode(ImeUiMode.AI_ASSISTANT)
                    }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    text = diffBadge,
                    color = Color(0xFF052E16),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
            }
        }

        // 2. Compact Auto-Update Badge (instead of a wide banner)
        val updateInfo = latestUpdate
        if (updateInfo != null && updateInfo.isUpdateAvailable) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentAmber)
                    .clickable {
                        FlorisImeService.hideUi()
                        AppUpdateManager.openAppAndShowUpdateDialog(context)
                    }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                val updatePillLabel = "🚀 Upd"
                Text(
                    text = updatePillLabel,
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        // 3. Compact Live Auto-Detected Grammar / Spelling Fix Pill
        val suggestion = liveSuggestion
        if (suggestion != null) {
            val (orig, fixed) = suggestion
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentTeal)
                    .clickable {
                        replaceTextSmartInEditor(
                            context = context,
                            editorInstance = editorInstance,
                            isIncognito = keyboardManager.activeState.isIncognitoMode,
                            targetToReplace = orig,
                            newText = fixed,
                            taskLabel = "Live Grammar Fix",
                        )
                        LastAiFixSession.record(
                            originalText = orig,
                            fixedText = fixed,
                            providerUsed = "8002Keys Instant Grammar",
                            taskLabel = "Live Fix",
                        )
                        liveSuggestion = null
                    }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                val fixChipText = "✨ ${fixed.take(26)} ✓"
                Text(
                    text = fixChipText,
                    color = textWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }

        // 4. Compact 1-Tap Fix Grammar Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isWorking) accentAmber else accentBlue)
                .clickable { executeInPlaceTask(AiWritingTask.FIX_GRAMMAR_SPELLING) }
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            val fixBtnLabel = if (isWorking) "⏳" else "✨ Fix"
            Text(
                text = fixBtnLabel,
                color = textWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 5. Compact 1-Tap Urdu Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(accentTeal)
                .clickable { executeInPlaceTask(AiWritingTask.TO_URDU_SCRIPT) }
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            val urduBtnLabel = "🇵🇰 Urdu"
            Text(
                text = urduBtnLabel,
                color = textWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 6. Compact 1-Tap English Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(accentIndigo)
                .clickable { executeInPlaceTask(AiWritingTask.ROMAN_URDU_TO_ENGLISH) }
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            val engBtnLabel = "🇬🇧 Eng"
            Text(
                text = engBtnLabel,
                color = textWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 7. Compact Open Full AI Panel Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(pillBg)
                .border(1.dp, accentTeal, RoundedCornerShape(8.dp))
                .clickable { keyboardManager.transitionToImeUiMode(ImeUiMode.AI_ASSISTANT) }
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            val panelBtnLabel = "✨ AI"
            Text(
                text = panelBtnLabel,
                color = textWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 8. Compact Roman Urdu Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(pillBg)
                .border(1.dp, Color(0xFF404040), RoundedCornerShape(8.dp))
                .clickable { executeInPlaceTask(AiWritingTask.ENGLISH_TO_ROMAN_URDU) }
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            val romanBtnLabel = "🇵🇰 Roman"
            Text(
                text = romanBtnLabel,
                color = textWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 9. Compact Resize Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(pillBg)
                .clickable { windowController?.editor?.toggleEnabled() }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            val resizeBtnLabel = "⤢"
            Text(
                text = resizeBtnLabel,
                color = textWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 10. Compact Account Status Pill
        val loginLabel = if (vaultState.hasAnyCloudProvider) "👤 ✓" else "👤 Login"
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (vaultState.hasAnyCloudProvider) pillBg else accentAmber)
                .clickable { launchAiAssistantSettings(context) }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(
                text = loginLabel,
                color = if (vaultState.hasAnyCloudProvider) Color(0xFF34D399) else Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

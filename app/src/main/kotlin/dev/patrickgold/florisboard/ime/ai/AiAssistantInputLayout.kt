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
        // 1. Top Bar: Back to Keyboard (ABC), Connected Account Status, Resize
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Big "← Keyboard (ABC)" Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(btnBlue)
                    .clickable { keyboardManager.transitionToImeUiMode(ImeUiMode.TEXT) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = textWhite,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    val backLabel = "Keyboard (ABC)"
                    Text(
                        text = backLabel,
                        color = textWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Resize Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(btnSlate)
                        .clickable {
                            keyboardManager.transitionToImeUiMode(ImeUiMode.TEXT)
                            windowController?.editor?.enable()
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    val resizeLabel = "⤢ Resize"
                    Text(
                        text = resizeLabel,
                        color = textWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // Account Login Button
                val accountText = if (vaultState.hasAnyCloudProvider) {
                    "✓ ${vaultState.swiftKeyAccountBadgeLabel()}"
                } else {
                    "⚙️ Login Google/GPT"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (vaultState.hasAnyCloudProvider) btnEmerald else accentAmber.copy(alpha = 0.25f))
                        .clickable { launchAiAssistantSettings(context) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = accountText,
                        color = if (vaultState.hasAnyCloudProvider) textWhite else accentAmber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // 2. Main 2x2 Grid of Large, Easy-to-Read Action Buttons (zero clipping at any font scale)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Button 1: Fix Grammar & Spelling
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(btnBlue)
                    .clickable { runTaskAndApply(AiWritingTask.FIX_GRAMMAR_SPELLING) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label1 = "✨ Fix Grammar"
                Text(
                    text = label1,
                    color = textWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Button 2: Translate in Urdu (اردو)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(btnEmerald)
                    .clickable { runTaskAndApply(AiWritingTask.TO_URDU_SCRIPT) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label2 = "🇵🇰 Translate Urdu"
                Text(
                    text = label2,
                    color = textWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Button 3: Translate in English
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(btnIndigo)
                    .clickable { runTaskAndApply(AiWritingTask.ROMAN_URDU_TO_ENGLISH) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label3 = "🇬🇧 To English"
                Text(
                    text = label3,
                    color = textWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Button 4: English -> Roman Urdu
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(btnTeal)
                    .clickable { runTaskAndApply(AiWritingTask.ENGLISH_TO_ROMAN_URDU) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label4 = "🇵🇰 Roman Urdu"
                Text(
                    text = label4,
                    color = textWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 3. Secondary Quick Row: Voice AI, Smart Reply, Professional Polish, Undo
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFDC2626))
                    .clickable {
                        FlorisImeService.switchToVoiceInputMethod(showFailureToast = true)
                    }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val voiceLabel = "🎤 Voice AI"
                Text(
                    voiceLabel,
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
                    .defaultMinSize(minHeight = 40.dp)
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
                    .defaultMinSize(minHeight = 40.dp)
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
                        .height(38.dp)
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
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val undoLabel = "↩ Undo"
                    Text(undoLabel, color = accentAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 4. Large Readable Text Preview & Light-Green Diff Result Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cardDark)
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRunning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = accentGreen,
                        )
                        val workingText = "AI Processing (${selectedTask.chipLabel})..."
                        Text(
                            text = workingText,
                            color = accentGreen,
                            fontSize = 15.sp,
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

                    if (!activeFixedText.isNullOrBlank() && !activeOriginalText.isNullOrBlank()) {
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
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = headerBadge,
                                color = Color(0xFF86EFAC),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = diffResult.annotatedText,
                            color = textWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 23.sp,
                        )
                    } else {
                        val displayText = sourceEditorText.trim().takeIf { it.isNotBlank() }
                            ?: clipboardText.takeIf { it.isNotBlank() }

                        if (!statusMessage.isNullOrBlank()) {
                            Text(
                                text = statusMessage!!,
                                color = accentGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        } else if (!displayText.isNullOrBlank() && displayText.length > 180) {
                            Text(
                                text = "📄 Long text ready (${displayText.length} chars) — tap Fix Grammar or Translate",
                                color = Color(0xFF93C5FD),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        if (!displayText.isNullOrBlank()) {
                            Text(
                                text = displayText,
                                color = textWhite,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 22.sp,
                            )
                        } else {
                            val emptyGuide = "Tap any phrase below to test or insert, or tap 'Keyboard (ABC)' to type your message:"
                            Text(
                                text = emptyGuide,
                                color = textMuted,
                                fontSize = 14.sp,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(
                                    "Ana hay milna b hay",
                                    "I am coming to Lahore tomorrow",
                                    "Assalamu Alaikum, kya haal hai?",
                                ).forEach { sample ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(btnSlate)
                                            .clickable {
                                                editorInstance.commitText(sample)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = "+ \"$sample\"",
                                            color = textWhite,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }
                        }
                    }
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
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. SwiftKey 4-Column Grid Menu Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(pillBg)
                .border(1.dp, Color(0xFF404040), RoundedCornerShape(10.dp))
                .clickable {
                    keyboardManager.activeState.isActionsOverflowVisible =
                        !keyboardManager.activeState.isActionsOverflowVisible
                }
                .padding(horizontal = 11.dp, vertical = 6.dp),
        ) {
            val menuLabel = "⊞ Menu"
            Text(
                text = menuLabel,
                color = textWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 1b. Built-in Gemini & ChatGPT AI Voice Typing Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFDC2626))
                .clickable {
                    FlorisImeService.switchToVoiceInputMethod(showFailureToast = true)
                }
                .padding(horizontal = 11.dp, vertical = 6.dp),
        ) {
            val voiceAiLabel = "🎤 Voice AI"
            Text(
                text = voiceAiLabel,
                color = textWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 1c. Light-Green Model Attribution & Diff Pill (tap to view highlighted changes + Undo)
        val latestFix = sharedLastFix
        if (latestFix != null) {
            val diffBadge = if (latestFix.changedWordCount > 0) {
                "✓ ${latestFix.changedWordCount} fixed (${latestFix.shortProviderBadge()}) • Diff"
            } else {
                "✓ Verified (${latestFix.shortProviderBadge()})"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF86EFAC))
                    .clickable {
                        keyboardManager.transitionToImeUiMode(ImeUiMode.AI_ASSISTANT)
                    }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            ) {
                Text(
                    text = diffBadge,
                    color = Color(0xFF052E16),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
            }
        }

        // 2. Auto-Update Ready Pill (if a newer GitHub build exists)
        val updateInfo = latestUpdate
        if (updateInfo != null && updateInfo.isUpdateAvailable) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentAmber)
                    .clickable {
                        FlorisImeService.hideUi()
                        AppUpdateManager.openAppAndShowUpdateDialog(context)
                    }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            ) {
                val updatePillLabel = "🚀 Update APK (${updateInfo.remoteShortCommit})"
                Text(
                    text = updatePillLabel,
                    color = Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        // 3. Live Auto-Detected Grammar / Spelling Correction Pill (14sp Bold, 1-tap replaces sentence!)
        val suggestion = liveSuggestion
        if (suggestion != null) {
            val (orig, fixed) = suggestion
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
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
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                val fixChipText = "✨ Fix: \"${fixed.take(38)}\" ✓"
                Text(
                    text = fixChipText,
                    color = textWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }

        // 4. 1-Tap Fix Grammar Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (isWorking) accentAmber else accentBlue)
                .clickable { executeInPlaceTask(AiWritingTask.FIX_GRAMMAR_SPELLING) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            val fixBtnLabel = if (isWorking) "⏳ Working..." else "✨ Fix Grammar"
            Text(
                text = fixBtnLabel,
                color = textWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 5. 1-Tap Translate in Urdu (اردو) Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(accentTeal)
                .clickable { executeInPlaceTask(AiWritingTask.TO_URDU_SCRIPT) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            val urduBtnLabel = "🇵🇰 Translate in Urdu (اردو)"
            Text(
                text = urduBtnLabel,
                color = textWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 6. 1-Tap Translate in English Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(accentIndigo)
                .clickable { executeInPlaceTask(AiWritingTask.ROMAN_URDU_TO_ENGLISH) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            val engBtnLabel = "🇬🇧 Translate in English"
            Text(
                text = engBtnLabel,
                color = textWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 7. 1-Tap English -> Roman Urdu Button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(pillBg)
                .border(1.dp, Color(0xFF404040), RoundedCornerShape(10.dp))
                .clickable { executeInPlaceTask(AiWritingTask.ENGLISH_TO_ROMAN_URDU) }
                .padding(horizontal = 11.dp, vertical = 6.dp),
        ) {
            val romanBtnLabel = "🇵🇰 Roman Urdu"
            Text(
                text = romanBtnLabel,
                color = textWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 8. Open Full Bottom AI Panel
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(pillBg)
                .border(1.dp, accentTeal, RoundedCornerShape(10.dp))
                .clickable { keyboardManager.transitionToImeUiMode(ImeUiMode.AI_ASSISTANT) }
                .padding(horizontal = 11.dp, vertical = 6.dp),
        ) {
            val panelBtnLabel = "✨ AI Panel ▾"
            Text(
                text = panelBtnLabel,
                color = textWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 9. 1-Tap Resize Keyboard
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(pillBg)
                .clickable { windowController?.editor?.toggleEnabled() }
                .padding(horizontal = 11.dp, vertical = 6.dp),
        ) {
            val resizeBtnLabel = "⤢ Resize"
            Text(
                text = resizeBtnLabel,
                color = textWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 10. Account / Login Status
        val loginLabel = if (vaultState.hasAnyCloudProvider) {
            "👤 ${vaultState.swiftKeyAccountBadgeLabel()} ✓"
        } else {
            "👤 Login Google / ChatGPT"
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (vaultState.hasAnyCloudProvider) pillBg else accentAmber)
                .clickable { launchAiAssistantSettings(context) }
                .padding(horizontal = 11.dp, vertical = 6.dp),
        ) {
            Text(
                text = loginLabel,
                color = if (vaultState.hasAnyCloudProvider) Color(0xFF34D399) else Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

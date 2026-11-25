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

package dev.patrickgold.florisboard.app.settings.ai

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.ai.AiMemoryVault
import dev.patrickgold.florisboard.ime.ai.AiProviderType
import dev.patrickgold.florisboard.ime.ai.AiWritingTask
import dev.patrickgold.florisboard.ime.ai.CloudAiClient
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showLongToastSync
import org.florisboard.lib.android.showShortToastSync

private fun readSystemClipboardText(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val item = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
    return item?.coerceToText(context)?.toString()?.trim().orEmpty()
}

private fun writeSystemClipboardText(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun openBrowserUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }.onFailure {
        context.showShortToastSync("Unable to open URL: $url")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiAssistantScreen() = FlorisScreen {
    title = "AI Login, Grammar & Memory"
    previewFieldVisible = true

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault by AiMemoryVault.state.collectAsState()

    var geminiEmail by remember(vault.geminiAccountEmail) { mutableStateOf(vault.geminiAccountEmail) }
    var geminiKey by remember(vault.geminiApiKey) { mutableStateOf(vault.geminiApiKey) }
    var geminiModel by remember(vault.geminiModel) { mutableStateOf(vault.geminiModel) }
    var showGeminiKey by remember { mutableStateOf(false) }
    var geminiStatusMessage by remember { mutableStateOf<String?>(null) }
    var isTestingGemini by remember { mutableStateOf(false) }

    var chatGptEmail by remember(vault.chatGptAccountEmail) { mutableStateOf(vault.chatGptAccountEmail) }
    var chatGptKey by remember(vault.chatGptApiKey) { mutableStateOf(vault.chatGptApiKey) }
    var chatGptModel by remember(vault.chatGptModel) { mutableStateOf(vault.chatGptModel) }
    var chatGptBaseUrl by remember(vault.chatGptBaseUrl) { mutableStateOf(vault.chatGptBaseUrl) }
    var showChatGptKey by remember { mutableStateOf(false) }
    var chatGptStatusMessage by remember { mutableStateOf<String?>(null) }
    var isTestingChatGpt by remember { mutableStateOf(false) }

    var styleMemoryInput by remember(vault.writingStyleMemory) { mutableStateOf(vault.writingStyleMemory) }
    var customRuleOrigin by remember { mutableStateOf("") }
    var customRulePreferred by remember { mutableStateOf("") }
    var memoryStatusBanner by remember { mutableStateOf<String?>(null) }

    var sampleInputText by remember {
        mutableStateOf("mjy ismein gemini aur chatgpt sy spell check aur ai grammer correct krna hay")
    }
    var sampleResultText by remember { mutableStateOf<String?>(null) }
    var isRunningSample by remember { mutableStateOf(false) }

    var clipboardDetected by remember { mutableStateOf<Pair<AiProviderType, String>?>(null) }
    var inAppLoginProvider by remember { mutableStateOf<AiProviderType?>(null) }

    LaunchedEffect(Unit) {
        AiMemoryVault.initialize(context)
        val clip = readSystemClipboardText(context)
        clipboardDetected = AiMemoryVault.detectApiKeyInText(clip)
    }

    val activeLoginModal = inAppLoginProvider
    if (activeLoginModal != null) {
        InAppAiLoginWebViewDialog(
            provider = activeLoginModal,
            onKeyCaptured = { capturedProvider, capturedKey, capturedEmail ->
                if (capturedProvider == AiProviderType.GEMINI) {
                    geminiKey = capturedKey
                    if (capturedEmail.isNotBlank()) geminiEmail = capturedEmail
                    AiMemoryVault.connectGemini(
                        context = context,
                        accountEmail = capturedEmail.ifBlank { geminiEmail.ifBlank { "Google Gemini Account" } },
                        apiKey = capturedKey,
                        model = geminiModel,
                    )
                    geminiStatusMessage = "Google Gemini Logged In & Saved to Persistent Memory ✓"
                } else {
                    chatGptKey = capturedKey
                    if (capturedEmail.isNotBlank()) chatGptEmail = capturedEmail
                    AiMemoryVault.connectChatGpt(
                        context = context,
                        accountEmail = capturedEmail.ifBlank { chatGptEmail.ifBlank { "OpenAI ChatGPT Account" } },
                        apiKey = capturedKey,
                        model = chatGptModel,
                        baseUrl = chatGptBaseUrl,
                    )
                    chatGptStatusMessage = "OpenAI ChatGPT Logged In & Saved to Persistent Memory ✓"
                }
                inAppLoginProvider = null
                context.showLongToastSync("${capturedProvider.displayName} logged in & saved to phone memory!")
            },
            onDismiss = {
                val clip = readSystemClipboardText(context)
                clipboardDetected = AiMemoryVault.detectApiKeyInText(clip)
                inAppLoginProvider = null
            },
        )
    }

    content {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 0. Overview & Clipboard Auto-Detector Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Active AI Engine: ${vault.activeProviderSummary()}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Text(
                        text = "Connect both Google Gemini and OpenAI ChatGPT below. Your login accounts, writing style, and learned corrections automatically persist across app reinstalls in phone storage (Download/8002Keys).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )

                    val detected = clipboardDetected
                    if (detected != null) {
                        val (prov, key) = detected
                        val isNew = (prov == AiProviderType.GEMINI && key != vault.geminiApiKey) ||
                            (prov == AiProviderType.CHATGPT && key != vault.chatGptApiKey)
                        if (isNew) {
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    if (prov == AiProviderType.GEMINI) {
                                        geminiKey = key
                                        AiMemoryVault.connectGemini(
                                            context = context,
                                            accountEmail = geminiEmail.ifBlank { "Google Gemini Account" },
                                            apiKey = key,
                                            model = geminiModel,
                                        )
                                        geminiStatusMessage = "Connected Gemini key from clipboard & saved to persistent memory!"
                                    } else {
                                        chatGptKey = key
                                        AiMemoryVault.connectChatGpt(
                                            context = context,
                                            accountEmail = chatGptEmail.ifBlank { "OpenAI ChatGPT Account" },
                                            apiKey = key,
                                            model = chatGptModel,
                                            baseUrl = chatGptBaseUrl,
                                        )
                                        chatGptStatusMessage = "Connected ChatGPT key from clipboard & saved to persistent memory!"
                                    }
                                    context.showShortToastSync("${prov.displayName} connected & saved!")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("1-Tap Connect Detected ${prov.displayName} Key from Clipboard")
                            }
                        }
                    }
                }
            }

            // 1. Google Gemini Account Login & Setup
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "1. Google Gemini Login",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (vault.isGeminiReady) "Connected ✓" else "Not Connected",
                            color = if (vault.isGeminiReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "Sign in with your Google Account inside the app (or browser) to connect Google Gemini AI for spell check, grammar fixing, and Roman Urdu/English writing.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { inAppLoginProvider = AiProviderType.GEMINI },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Sign In with Google Inside App (Gemini Login)")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { openBrowserUrl(context, "https://aistudio.google.com/app/apikey") },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Open in Chrome")
                        }
                        OutlinedButton(
                            onClick = {
                                val clip = readSystemClipboardText(context)
                                val detected = AiMemoryVault.detectApiKeyInText(clip)
                                val keyToUse = if (detected?.first == AiProviderType.GEMINI) detected.second else clip
                                if (keyToUse.isNotBlank()) {
                                    geminiKey = keyToUse
                                    AiMemoryVault.connectGemini(
                                        context = context,
                                        accountEmail = geminiEmail.ifBlank { "Google Account" },
                                        apiKey = keyToUse,
                                        model = geminiModel,
                                    )
                                    geminiStatusMessage = "Gemini key pasted & saved to persistent memory!"
                                    context.showShortToastSync("Gemini key pasted & saved!")
                                } else {
                                    context.showShortToastSync("Clipboard is empty")
                                }
                            },
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Paste Key")
                        }
                    }

                    OutlinedTextField(
                        value = geminiEmail,
                        onValueChange = { geminiEmail = it },
                        label = { Text("Google Account Email / Label (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = { geminiKey = it.trim() },
                        label = { Text("Google Gemini API Key (AIza...)") },
                        singleLine = true,
                        visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            OutlinedButton(
                                onClick = { showGeminiKey = !showGeminiKey },
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text(if (showGeminiKey) "Hide" else "Show")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = "Gemini Model:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash").forEach { modelName ->
                            FilterChip(
                                selected = geminiModel == modelName,
                                onClick = { geminiModel = modelName },
                                label = { Text(modelName) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                if (geminiKey.isBlank()) {
                                    context.showShortToastSync("Please paste your Gemini API key first")
                                    return@Button
                                }
                                AiMemoryVault.connectGemini(
                                    context = context,
                                    accountEmail = geminiEmail.ifBlank { "Google Gemini Account" },
                                    apiKey = geminiKey,
                                    model = geminiModel,
                                )
                                isTestingGemini = true
                                geminiStatusMessage = "Saved to persistent memory. Verifying live connection..."
                                scope.launch {
                                    val res = CloudAiClient.testConnection(
                                        provider = AiProviderType.GEMINI,
                                        apiKey = geminiKey,
                                        model = geminiModel,
                                    )
                                    isTestingGemini = false
                                    geminiStatusMessage = res.fold(
                                        onSuccess = { "Verified & Logged In ✓ — $it" },
                                        onFailure = { "Key saved to memory (Live check note: ${it.message})" },
                                    )
                                }
                            },
                            enabled = !isTestingGemini,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (isTestingGemini) "Verifying Gemini..." else "Verify & Login Gemini")
                        }

                        if (vault.isGeminiReady) {
                            OutlinedButton(
                                onClick = {
                                    AiMemoryVault.disconnectGemini(context)
                                    geminiKey = ""
                                    geminiStatusMessage = "Logged out of Google Gemini."
                                },
                            ) {
                                Text("Logout")
                            }
                        }
                    }

                    if (!geminiStatusMessage.isNullOrBlank()) {
                        Text(
                            text = geminiStatusMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // 2. OpenAI ChatGPT Account Login & Setup
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "2. OpenAI ChatGPT Login",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (vault.isChatGptReady) "Connected ✓" else "Not Connected",
                            color = if (vault.isChatGptReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "Sign in with your ChatGPT / OpenAI account inside the app (or browser) to connect ChatGPT for grammar, spell check, and writing assistance.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { inAppLoginProvider = AiProviderType.CHATGPT },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Sign In with ChatGPT Inside App (OpenAI Login)")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { openBrowserUrl(context, "https://platform.openai.com/api-keys") },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Open in Chrome")
                        }
                        OutlinedButton(
                            onClick = {
                                val clip = readSystemClipboardText(context)
                                val detected = AiMemoryVault.detectApiKeyInText(clip)
                                val keyToUse = if (detected?.first == AiProviderType.CHATGPT) detected.second else clip
                                if (keyToUse.isNotBlank()) {
                                    chatGptKey = keyToUse
                                    AiMemoryVault.connectChatGpt(
                                        context = context,
                                        accountEmail = chatGptEmail.ifBlank { "ChatGPT Account" },
                                        apiKey = keyToUse,
                                        model = chatGptModel,
                                        baseUrl = chatGptBaseUrl,
                                    )
                                    chatGptStatusMessage = "ChatGPT key pasted & saved to persistent memory!"
                                    context.showShortToastSync("ChatGPT key pasted & saved!")
                                } else {
                                    context.showShortToastSync("Clipboard is empty")
                                }
                            },
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Paste Key")
                        }
                    }

                    OutlinedTextField(
                        value = chatGptEmail,
                        onValueChange = { chatGptEmail = it },
                        label = { Text("ChatGPT Account Email / Label (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = chatGptKey,
                        onValueChange = { chatGptKey = it.trim() },
                        label = { Text("OpenAI ChatGPT API Key (sk-...)") },
                        singleLine = true,
                        visualTransformation = if (showChatGptKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            OutlinedButton(
                                onClick = { showChatGptKey = !showChatGptKey },
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text(if (showChatGptKey) "Hide" else "Show")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = "ChatGPT Model:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("gpt-4o-mini", "gpt-4o", "o3-mini").forEach { modelName ->
                            FilterChip(
                                selected = chatGptModel == modelName,
                                onClick = { chatGptModel = modelName },
                                label = { Text(modelName) },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = chatGptBaseUrl,
                        onValueChange = { chatGptBaseUrl = it.trim() },
                        label = { Text("OpenAI-Compatible Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                if (chatGptKey.isBlank()) {
                                    context.showShortToastSync("Please paste your ChatGPT API key first")
                                    return@Button
                                }
                                AiMemoryVault.connectChatGpt(
                                    context = context,
                                    accountEmail = chatGptEmail.ifBlank { "OpenAI ChatGPT Account" },
                                    apiKey = chatGptKey,
                                    model = chatGptModel,
                                    baseUrl = chatGptBaseUrl,
                                )
                                isTestingChatGpt = true
                                chatGptStatusMessage = "Saved to persistent memory. Verifying live connection..."
                                scope.launch {
                                    val res = CloudAiClient.testConnection(
                                        provider = AiProviderType.CHATGPT,
                                        apiKey = chatGptKey,
                                        model = chatGptModel,
                                        baseUrl = chatGptBaseUrl,
                                    )
                                    isTestingChatGpt = false
                                    chatGptStatusMessage = res.fold(
                                        onSuccess = { "Verified & Logged In ✓ — $it" },
                                        onFailure = { "Key saved to memory (Live check note: ${it.message})" },
                                    )
                                }
                            },
                            enabled = !isTestingChatGpt,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (isTestingChatGpt) "Verifying ChatGPT..." else "Verify & Login ChatGPT")
                        }

                        if (vault.isChatGptReady) {
                            OutlinedButton(
                                onClick = {
                                    AiMemoryVault.disconnectChatGpt(context)
                                    chatGptKey = ""
                                    chatGptStatusMessage = "Logged out of OpenAI ChatGPT."
                                },
                            ) {
                                Text("Logout")
                            }
                        }
                    }

                    if (!chatGptStatusMessage.isNullOrBlank()) {
                        Text(
                            text = chatGptStatusMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // 3. AI Grammar, Spell Check & Live Test Playground
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "3. AI Grammar, Spell Check & Provider Routing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Text(
                        text = "Preferred AI Provider:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AiProviderType.entries.forEach { prov ->
                            FilterChip(
                                selected = vault.preferredProvider == prov,
                                onClick = {
                                    AiMemoryVault.updateExecutionPreferences(
                                        context = context,
                                        preferredProvider = prov,
                                    )
                                },
                                label = { Text(prov.displayName) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AI Grammar & Spell Check in Editor",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Powers real-time sentence grammar and Roman Urdu + English spelling suggestions",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = vault.aiGrammarSpellCheckEnabled,
                            onCheckedChange = {
                                AiMemoryVault.updateExecutionPreferences(
                                    context = context,
                                    aiGrammarSpellCheckEnabled = it,
                                )
                            },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Learn Vocabulary & Corrections into Memory",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Remembers words and grammar rewrites you apply in the keyboard",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = vault.autoLearnFromCorrections,
                            onCheckedChange = {
                                AiMemoryVault.updateExecutionPreferences(
                                    context = context,
                                    autoLearnFromCorrections = it,
                                )
                            },
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Live AI Grammar & Roman Urdu Test Playground:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedTextField(
                        value = sampleInputText,
                        onValueChange = { sampleInputText = it },
                        label = { Text("Type English or Roman Urdu to test AI Grammar / Translation") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            AiWritingTask.FIX_GRAMMAR_SPELLING,
                            AiWritingTask.ROMAN_URDU_TO_ENGLISH,
                            AiWritingTask.ENGLISH_TO_ROMAN_URDU,
                            AiWritingTask.TO_URDU_SCRIPT,
                            AiWritingTask.TONE_PROFESSIONAL,
                        ).forEach { task ->
                            OutlinedButton(
                                onClick = {
                                    isRunningSample = true
                                    scope.launch {
                                        val res = CloudAiClient.executeTask(
                                            context = context,
                                            task = task,
                                            inputText = sampleInputText,
                                        )
                                        isRunningSample = false
                                        sampleResultText = "[${res.providerUsed}]\n${res.outputText}" +
                                            (res.warningMessage?.let { "\n($it)" } ?: "")
                                    }
                                },
                                enabled = !isRunningSample,
                            ) {
                                Text(task.chipLabel)
                            }
                        }
                    }
                    if (!sampleResultText.isNullOrBlank()) {
                        Text(
                            text = sampleResultText!!,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // 4. Persistent AI Memory Across Reinstalls
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "4. Persistent Memory Across Reinstalls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "When enabled, 8002Keys automatically mirrors your connected Gemini & ChatGPT accounts, personal writing style, learned grammar fixes (${vault.learnedMemories.size}), and custom dictionary words (${vault.customWords.size}) to Download/8002Keys/8002keys_ai_vault.json so reinstalling the APK restores everything automatically.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Persist Accounts & Memory Across Reinstalls",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Automatically syncs & restores from device storage on every install",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = vault.autoPersistAcrossReinstalls,
                            onCheckedChange = {
                                AiMemoryVault.updateExecutionPreferences(
                                    context = context,
                                    autoPersistAcrossReinstalls = it,
                                )
                            },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val ok = AiMemoryVault.saveToPersistentStorageNow(context)
                                memoryStatusBanner = if (ok) {
                                    "Saved accounts & AI memory (${vault.learnedMemories.size} memories, ${vault.customWords.size} words) to phone storage!"
                                } else {
                                    "Saved to internal app memory vault."
                                }
                                context.showLongToastSync(memoryStatusBanner!!)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Save Memory to Phone")
                        }

                        OutlinedButton(
                            onClick = {
                                val ok = AiMemoryVault.restoreFromPersistentStorageNow(context)
                                val current = AiMemoryVault.state.value
                                memoryStatusBanner = if (ok) {
                                    "Loaded from phone storage! Accounts: ${current.activeProviderSummary()}, Memories: ${current.learnedMemories.size}, Words: ${current.customWords.size}"
                                } else {
                                    "No external memory file found in Download/8002Keys yet."
                                }
                                context.showLongToastSync(memoryStatusBanner!!)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Load Saved Memory")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val code = AiMemoryVault.exportPortableVaultCode()
                                writeSystemClipboardText(context, "8002Keys Memory Vault", code)
                                memoryStatusBanner = "Copied portable Memory Code to clipboard!"
                                context.showShortToastSync(memoryStatusBanner!!)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Copy Portable Memory Code")
                        }
                        OutlinedButton(
                            onClick = {
                                val clip = readSystemClipboardText(context)
                                val ok = AiMemoryVault.importPortableVaultCode(context, clip)
                                memoryStatusBanner = if (ok) {
                                    "Imported accounts & AI memory from clipboard code!"
                                } else {
                                    "Clipboard does not contain a valid 8002Keys memory code."
                                }
                                context.showShortToastSync(memoryStatusBanner!!)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Import Memory from Clipboard")
                        }
                    }

                    if (!memoryStatusBanner.isNullOrBlank()) {
                        Text(
                            text = memoryStatusBanner!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    OutlinedTextField(
                        value = styleMemoryInput,
                        onValueChange = { styleMemoryInput = it },
                        label = { Text("Personal Writing Style & AI Instructions Memory") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            AiMemoryVault.updateExecutionPreferences(
                                context = context,
                                writingStyleMemory = styleMemoryInput,
                            )
                            context.showShortToastSync("Saved personal writing style to AI Memory!")
                        },
                    ) {
                        Text("Save Writing Style Memory")
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Add Custom Vocabulary / Replacement Rule to Memory:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = customRuleOrigin,
                            onValueChange = { customRuleOrigin = it },
                            label = { Text("When I write (optional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = customRulePreferred,
                            onValueChange = { customRulePreferred = it },
                            label = { Text("Prefer / Remember") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            if (customRulePreferred.isBlank()) {
                                context.showShortToastSync("Enter a preferred word or phrase to remember")
                                return@OutlinedButton
                            }
                            AiMemoryVault.recordLearnedCorrection(
                                context = context,
                                originalText = customRuleOrigin,
                                preferredText = customRulePreferred,
                                taskLabel = "Manual Memory",
                                isSensitiveOrIncognito = false,
                            )
                            customRuleOrigin = ""
                            customRulePreferred = ""
                            context.showShortToastSync("Added to persistent AI Memory!")
                        },
                    ) {
                        Text("Add to Persistent Memory")
                    }

                    if (vault.learnedMemories.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Learned Writing Memories (${vault.learnedMemories.size}):",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        vault.learnedMemories.take(15).forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (item.originalText.isNotBlank() && item.originalText != item.preferredText) {
                                        "• \"${item.originalText.take(40)}\" → \"${item.preferredText.take(50)}\""
                                    } else {
                                        "• \"${item.preferredText.take(70)}\""
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { AiMemoryVault.removeLearnedMemory(context, item.id) },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. Keyboard Height & Resize Controls
            val prefs by FlorisPreferenceStore
            val heightPortrait by prefs.keyboard.keyboardHeightMultiplierPortrait.collectAsState()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "5. Keyboard Size & Resize Controls (Current: ${heightPortrait}%)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Tap any size below to resize the keyboard immediately, or tap '⤢ Resize' on the keyboard's top AI bar to drag handles and adjust bottom height.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "Small (85%)" to (85 to 0.85f),
                            "Normal (100%)" to (100 to 1.00f),
                            "Large (115%)" to (115 to 1.15f),
                            "XL (130%)" to (130 to 1.30f),
                            "XXL (145%)" to (145 to 1.45f),
                        ).forEach { (label, pair) ->
                            val (pct, scale) = pair
                            FilterChip(
                                selected = heightPortrait == pct,
                                onClick = {
                                    scope.launch {
                                        prefs.keyboard.keyboardHeightMultiplierPortrait.set(pct)
                                    }
                                    FlorisImeService.windowControllerOrNull()?.actions?.setFixedHeightPreset(scale)
                                    context.showShortToastSync("Keyboard size set to $pct%")
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InAppAiLoginWebViewDialog(
    provider: AiProviderType,
    onKeyCaptured: (provider: AiProviderType, apiKey: String, accountEmail: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val targetUrl = when (provider) {
        AiProviderType.GEMINI, AiProviderType.AUTO_HYBRID -> "https://aistudio.google.com/app/apikey"
        AiProviderType.CHATGPT -> "https://platform.openai.com/api-keys"
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var detectedPageKey by remember { mutableStateOf<String?>(null) }
    var detectedPageEmail by remember { mutableStateOf("") }
    var manualPasteKey by remember { mutableStateOf("") }

    fun scanTextForCredentials(rawText: String) {
        val emailMatch = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").find(rawText)
        if (emailMatch != null && detectedPageEmail.isBlank()) {
            detectedPageEmail = emailMatch.value
        }
        val found = AiMemoryVault.detectApiKeyInText(rawText)
        if (found != null) {
            detectedPageKey = found.second
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Sticky Top Bar inside In-App Login Modal
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${provider.displayName} In-App Sign-In",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { openBrowserUrl(context, targetUrl) },
                            ) {
                                Text("Chrome")
                            }
                            OutlinedButton(onClick = onDismiss) {
                                Text("Close")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                // 1. Check clipboard first
                                val clip = readSystemClipboardText(context)
                                scanTextForCredentials(clip)
                                val readyKey = detectedPageKey ?: manualPasteKey.trim().takeIf { it.isNotBlank() }
                                if (readyKey != null) {
                                    onKeyCaptured(provider, readyKey, detectedPageEmail)
                                    return@Button
                                }
                                // 2. Scan current WebView DOM text
                                val wv = webViewRef
                                if (wv != null) {
                                    wv.evaluateJavascript(
                                        "(function(){ return document.body ? document.body.innerText : ''; })();",
                                    ) { result ->
                                        val unescaped = result.orEmpty().trim('"')
                                        scanTextForCredentials(unescaped)
                                        val afterScanKey = detectedPageKey
                                            ?: AiMemoryVault.detectApiKeyInText(readSystemClipboardText(context))?.second
                                        if (afterScanKey != null) {
                                            onKeyCaptured(provider, afterScanKey, detectedPageEmail)
                                        } else {
                                            context.showLongToastSync(
                                                "Sign in below, tap 'Create API Key' / 'Copy', then tap this button again!",
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (detectedPageKey != null) {
                                    "Key Detected! Tap to Save Login ✓"
                                } else {
                                    "Auto-Grab Key from Page / Clipboard & Login ✓"
                                },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = manualPasteKey,
                            onValueChange = {
                                manualPasteKey = it.trim()
                                scanTextForCredentials(it)
                            },
                            label = { Text("Or paste/type API key here directly") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (manualPasteKey.isNotBlank()) {
                            Button(
                                onClick = {
                                    onKeyCaptured(provider, manualPasteKey, detectedPageEmail)
                                },
                            ) {
                                Text("Save")
                            }
                        }
                    }
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    view?.evaluateJavascript(
                                        "(function(){ return document.body ? document.body.innerText : ''; })();",
                                    ) { res ->
                                        scanTextForCredentials(res.orEmpty())
                                    }
                                    scanTextForCredentials(readSystemClipboardText(ctx))
                                }
                            }
                            loadUrl(targetUrl)
                            webViewRef = this
                        }
                    },
                )
            }
        }
    }
}

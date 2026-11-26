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

package dev.patrickgold.florisboard.app.settings.ai

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.patrickgold.florisboard.app.update.AppUpdateBannerCard
import dev.patrickgold.florisboard.app.update.AppUpdateHost
import dev.patrickgold.florisboard.ime.ai.AiDiffHighlighter
import dev.patrickgold.florisboard.ime.ai.AiExecutionResult
import dev.patrickgold.florisboard.ime.ai.AiMemoryVault
import dev.patrickgold.florisboard.ime.ai.AiProviderType
import dev.patrickgold.florisboard.ime.ai.AiWritingTask
import dev.patrickgold.florisboard.ime.ai.CloudAiClient
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showLongToastSync
import org.florisboard.lib.android.showShortToastSync

private fun openExternalUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.onFailure {
        context.showShortToastSync("Unable to open browser")
    }
}

@Composable
fun AiAssistantScreen() = FlorisScreen {
    title = "AI Login, Memory & Voice"
    previewFieldVisible = true

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault by AiMemoryVault.state.collectAsState()
    val pendingLogin by AiMemoryVault.pendingLoginProvider.collectAsState()

    var geminiEmail by remember(vault.geminiAccountEmail) {
        mutableStateOf(
            if (vault.geminiAccountEmail.equals("Google Account", ignoreCase = true)) {
                ""
            } else {
                vault.geminiAccountEmail
            },
        )
    }
    var geminiStatus by remember { mutableStateOf<String?>(null) }

    var chatGptEmail by remember(vault.chatGptAccountEmail) {
        mutableStateOf(
            if (vault.chatGptAccountEmail.equals("Google Account", ignoreCase = true) ||
                vault.chatGptAccountEmail.equals("ChatGPT Plus Subscription", ignoreCase = true)
            ) {
                ""
            } else {
                vault.chatGptAccountEmail
            },
        )
    }
    var chatGptStatus by remember { mutableStateOf<String?>(null) }
    var memoryStatus by remember { mutableStateOf<String?>(null) }

    var inlineWebViewProvider by remember { mutableStateOf<AiProviderType?>(null) }

    var testInput by remember {
        mutableStateOf("He dont know what they is doing yesterday and i am coming to Lahore tomorrow")
    }
    var testInputSnapshot by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<AiExecutionResult?>(null) }
    var isTestingTask by remember { mutableStateOf(false) }

    fun connectGoogleAccountNow(emailInput: String) {
        val cleanEmail = emailInput.trim()
        if (cleanEmail.isBlank()) {
            geminiStatus = "Please choose your phone's Google Account or type your Gmail first."
            return
        }
        val token = "${CloudAiClient.GOOGLE_WEB_SESSION_PREFIX}$cleanEmail"
        geminiEmail = cleanEmail
        AiMemoryVault.connectGemini(
            context = context,
            accountEmail = cleanEmail,
            apiKey = token,
            model = vault.geminiModel,
        )
        geminiStatus = "✓ Google Gemini Connected: $cleanEmail"
        inlineWebViewProvider = null
        context.showLongToastSync("Google Account ($cleanEmail) connected!")
    }

    fun connectChatGptAccountNow(emailInput: String) {
        val cleanEmail = emailInput.trim().ifBlank {
            geminiEmail.trim()
        }
        if (cleanEmail.isBlank()) {
            chatGptStatus = "Please type your ChatGPT email or sign in via ChatGPT Web."
            return
        }
        val token = "${CloudAiClient.CHATGPT_WEB_SESSION_PREFIX}$cleanEmail"
        chatGptEmail = cleanEmail
        AiMemoryVault.connectChatGpt(
            context = context,
            accountEmail = cleanEmail,
            apiKey = token,
            model = vault.chatGptModel,
            baseUrl = vault.chatGptBaseUrl,
        )
        chatGptStatus = "✓ ChatGPT Attached: $cleanEmail"
        inlineWebViewProvider = null
        context.showLongToastSync("ChatGPT Subscription ($cleanEmail) attached!")
    }

    val googleAccountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pickedEmail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim().orEmpty()
            if (pickedEmail.isNotEmpty()) {
                connectGoogleAccountNow(pickedEmail)
            }
        }
    }

    fun launchSystemGoogleAccountPicker() {
        runCatching {
            val intent = AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null,
            )
            googleAccountPickerLauncher.launch(intent)
        }.onFailure {
            inlineWebViewProvider = AiProviderType.GEMINI
        }
    }

    LaunchedEffect(Unit) {
        AiMemoryVault.initialize(context)
    }

    LaunchedEffect(pendingLogin) {
        val target = pendingLogin
        if (target != null) {
            AiMemoryVault.requestDirectLoginModal(null)
            if (target == AiProviderType.GEMINI) {
                launchSystemGoogleAccountPicker()
            } else {
                inlineWebViewProvider = target
            }
        }
    }

    val emeraldGreen = Color(0xFF059669)
    val royalBlue = Color(0xFF2563EB)
    val deepIndigo = Color(0xFF4F46E5)
    val tealColor = Color(0xFF0D9488)
    val btnPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)

    content {
        AppUpdateHost(autoCheckOnMount = true)
        AppUpdateBannerCard()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Optional Inline Login WebView (rendered directly in Activity window so keyboard ALWAYS opens!)
            val activeWebProvider = inlineWebViewProvider
            if (activeWebProvider != null) {
                InlineAccountSignInCard(
                    provider = activeWebProvider,
                    onAccountConnected = { prov, email ->
                        if (prov == AiProviderType.GEMINI) {
                            connectGoogleAccountNow(email)
                        } else {
                            connectChatGptAccountNow(email)
                        }
                    },
                    onClose = { inlineWebViewProvider = null },
                )
            }

            // ACTIVE MODEL SELECTOR BAR (Choose Gemini, ChatGPT, or Auto Both)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val modelHeader = "Active AI Engine (Who Fixes Your Text):"
                    Text(
                        text = modelHeader,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            AiProviderType.AUTO_HYBRID to "Auto (Both)",
                            AiProviderType.GEMINI to "Gemini",
                            AiProviderType.CHATGPT to "ChatGPT",
                        ).forEach { (prov, shortName) ->
                            val selected = vault.preferredProvider == prov
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) emeraldGreen else Color(0xFF262B36))
                                    .clickable {
                                        AiMemoryVault.updateExecutionPreferences(
                                            context = context,
                                            preferredProvider = prov,
                                        )
                                    }
                                    .padding(vertical = 10.dp, horizontal = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = shortName,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // CARD 1: GOOGLE ACCOUNT LOGIN (ZERO CLIPPING, CLEAR REAL EMAIL)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        color = if (vault.isGeminiReady) emeraldGreen else royalBlue,
                        shape = RoundedCornerShape(16.dp),
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = if (vault.isGeminiReady) emeraldGreen else royalBlue,
                                modifier = Modifier.size(26.dp),
                            )
                            val card1Title = "1. Google Gemini"
                            Text(
                                text = card1Title,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (vault.isGeminiReady) emeraldGreen.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            val badge = if (vault.isGeminiReady) "✓ Connected" else "Not Linked"
                            Text(
                                text = badge,
                                color = if (vault.isGeminiReady) emeraldGreen else MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }

                    if (vault.isGeminiReady) {
                        val hasRealEmail = vault.geminiAccountEmail.contains("@")
                        val accountInfo = if (hasRealEmail) {
                            "Active Google Account: ${vault.geminiAccountEmail}"
                        } else {
                            "Active Session (Tap 'Choose Phone Google Account' below to link your Gmail)"
                        }
                        Text(
                            text = accountInfo,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasRealEmail) emeraldGreen else Color(0xFFF59E0B),
                        )
                    }

                    Button(
                        onClick = { launchSystemGoogleAccountPicker() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                        contentPadding = btnPadding,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = royalBlue),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        val btn1Text = "Choose Phone Google Account"
                        Text(
                            text = btn1Text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    OutlinedTextField(
                        value = geminiEmail,
                        onValueChange = { geminiEmail = it },
                        label = {
                            val labelText = "Gmail Address (e.g. name@gmail.com)"
                            Text(labelText, fontSize = 13.sp)
                        },
                        textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { connectGoogleAccountNow(geminiEmail) },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp),
                            contentPadding = btnPadding,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = emeraldGreen),
                        ) {
                            val saveGoogleText = "Save Gmail"
                            Text(
                                text = saveGoogleText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        OutlinedButton(
                            onClick = { inlineWebViewProvider = AiProviderType.GEMINI },
                            modifier = Modifier.defaultMinSize(minHeight = 46.dp),
                            contentPadding = btnPadding,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            val webLoginText = "Web Login"
                            Text(
                                text = webLoginText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }

                        if (vault.isGeminiReady) {
                            OutlinedButton(
                                onClick = {
                                    AiMemoryVault.disconnectGemini(context)
                                    geminiEmail = ""
                                    geminiStatus = "Logged out of Google Gemini."
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 46.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                val outLabel = "Logout"
                                Text(outLabel, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }

                    if (!geminiStatus.isNullOrBlank()) {
                        Text(
                            text = geminiStatus!!,
                            color = emeraldGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // CARD 2: CHATGPT SUBSCRIPTION LOGIN (ZERO CLIPPING)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        color = if (vault.isChatGptReady) emeraldGreen else deepIndigo,
                        shape = RoundedCornerShape(16.dp),
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = if (vault.isChatGptReady) emeraldGreen else deepIndigo,
                                modifier = Modifier.size(26.dp),
                            )
                            val card2Title = "2. ChatGPT Plus"
                            Text(
                                text = card2Title,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (vault.isChatGptReady) emeraldGreen.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            val gptBadge = if (vault.isChatGptReady) "✓ Attached" else "Not Linked"
                            Text(
                                text = gptBadge,
                                color = if (vault.isChatGptReady) emeraldGreen else MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }

                    if (vault.isChatGptReady) {
                        val hasRealEmail = vault.chatGptAccountEmail.contains("@")
                        val gptInfo = if (hasRealEmail) {
                            "Active ChatGPT Account: ${vault.chatGptAccountEmail}"
                        } else {
                            "Active Session (Type your ChatGPT email or tap ChatGPT Web to link)"
                        }
                        Text(
                            text = gptInfo,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasRealEmail) emeraldGreen else Color(0xFFF59E0B),
                        )
                    }

                    Button(
                        onClick = { inlineWebViewProvider = AiProviderType.CHATGPT },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                        contentPadding = btnPadding,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = deepIndigo),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        val oneTapGptText = "Sign In to ChatGPT (chatgpt.com)"
                        Text(
                            text = oneTapGptText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    OutlinedTextField(
                        value = chatGptEmail,
                        onValueChange = { chatGptEmail = it },
                        label = {
                            val gptFieldLabel = "ChatGPT Email (e.g. name@live.com)"
                            Text(gptFieldLabel, fontSize = 13.sp)
                        },
                        textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { connectChatGptAccountNow(chatGptEmail) },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp),
                            contentPadding = btnPadding,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = emeraldGreen),
                        ) {
                            val attachGptText = "Save ChatGPT"
                            Text(
                                text = attachGptText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (vault.isChatGptReady) {
                            OutlinedButton(
                                onClick = {
                                    AiMemoryVault.disconnectChatGpt(context)
                                    chatGptEmail = ""
                                    chatGptStatus = "Logged out of ChatGPT."
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 46.dp),
                                contentPadding = btnPadding,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                val outGpt = "Logout"
                                Text(outGpt, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }

                    if (!chatGptStatus.isNullOrBlank()) {
                        Text(
                            text = chatGptStatus!!,
                            color = emeraldGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // CARD 3: PERSISTENT AI MEMORY VAULT (VISIBLE & ACTIVE!)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        color = emeraldGreen,
                        shape = RoundedCornerShape(16.dp),
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = emeraldGreen,
                                modifier = Modifier.size(26.dp),
                            )
                            val memTitle = "3. AI Memory Vault"
                            Text(
                                text = memTitle,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(emeraldGreen.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            val memBadge = if (vault.autoPersistAcrossReinstalls) "✓ Active" else "Paused"
                            Text(
                                text = memBadge,
                                color = emeraldGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }

                    val statsLine = "Learned Fixes: ${vault.learnedMemories.size}  •  Custom Words: ${vault.customWords.size}  •  History: ${vault.recentHistory.size}"
                    Text(
                        text = statsLine,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = emeraldGreen,
                    )
                    val pathLine = "Auto-saved to: Downloads/8002Keys/8002keys_ai_vault.json (survives reinstalls)"
                    Text(
                        text = pathLine,
                        fontSize = 12.sp,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val toggleLabel = "Auto-Learn & Persist Across Reinstalls"
                        Text(
                            text = toggleLabel,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = vault.autoPersistAcrossReinstalls,
                            onCheckedChange = { enabled ->
                                AiMemoryVault.updateExecutionPreferences(
                                    context = context,
                                    autoPersistAcrossReinstalls = enabled,
                                    autoLearnFromCorrections = enabled,
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
                                memoryStatus = if (ok) {
                                    "✓ Memory saved to Downloads/8002Keys/8002keys_ai_vault.json"
                                } else {
                                    "Saved to internal app memory vault."
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp),
                            contentPadding = btnPadding,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = emeraldGreen),
                        ) {
                            val saveMemLabel = "Save Memory"
                            Text(
                                text = saveMemLabel,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val ok = AiMemoryVault.restoreFromPersistentStorageNow(context)
                                memoryStatus = if (ok) {
                                    "✓ Loaded saved accounts & AI memory from phone storage!"
                                } else {
                                    "No previous vault file found in Downloads/8002Keys."
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp),
                            contentPadding = btnPadding,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            val loadMemLabel = "Load Memory"
                            Text(
                                text = loadMemLabel,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (!memoryStatus.isNullOrBlank()) {
                        Text(
                            text = memoryStatus!!,
                            color = emeraldGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // CARD 4: LIVE TEST PLAYGROUND (SHOWS WHICH MODEL FIXED IT + LIGHT GREEN DIFF!)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = royalBlue,
                            modifier = Modifier.size(24.dp),
                        )
                        val testCardTitle = "4. Live Test (Model + Green Diff)"
                        Text(
                            text = testCardTitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    OutlinedTextField(
                        value = testInput,
                        onValueChange = { testInput = it },
                        label = {
                            val testBoxLabel = "Type Short or Long Text to Test"
                            Text(testBoxLabel, fontSize = 13.sp)
                        },
                        textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                isTestingTask = true
                                testInputSnapshot = testInput
                                scope.launch {
                                    val res = CloudAiClient.executeTask(
                                        context = context,
                                        task = AiWritingTask.FIX_GRAMMAR_SPELLING,
                                        inputText = testInput,
                                    )
                                    isTestingTask = false
                                    testResult = res
                                }
                            },
                            enabled = !isTestingTask,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp),
                            contentPadding = btnPadding,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = royalBlue),
                        ) {
                            val fixTestLabel = "Fix Grammar"
                            Text(fixTestLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }

                        Button(
                            onClick = {
                                isTestingTask = true
                                testInputSnapshot = testInput
                                scope.launch {
                                    val res = CloudAiClient.executeTask(
                                        context = context,
                                        task = AiWritingTask.TO_URDU_SCRIPT,
                                        inputText = testInput,
                                    )
                                    isTestingTask = false
                                    testResult = res
                                }
                            },
                            enabled = !isTestingTask,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp),
                            contentPadding = btnPadding,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = emeraldGreen),
                        ) {
                            val urduTestLabel = "To Urdu اردو"
                            Text(urduTestLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                isTestingTask = true
                                testInputSnapshot = testInput
                                scope.launch {
                                    val res = CloudAiClient.executeTask(
                                        context = context,
                                        task = AiWritingTask.ROMAN_URDU_TO_ENGLISH,
                                        inputText = testInput,
                                    )
                                    isTestingTask = false
                                    testResult = res
                                }
                            },
                            enabled = !isTestingTask,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp),
                            contentPadding = btnPadding,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = deepIndigo),
                        ) {
                            val engTestLabel = "To English"
                            Text(engTestLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }

                        Button(
                            onClick = {
                                isTestingTask = true
                                testInputSnapshot = testInput
                                scope.launch {
                                    val res = CloudAiClient.executeTask(
                                        context = context,
                                        task = AiWritingTask.ENGLISH_TO_ROMAN_URDU,
                                        inputText = testInput,
                                    )
                                    isTestingTask = false
                                    testResult = res
                                }
                            },
                            enabled = !isTestingTask,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 46.dp),
                            contentPadding = btnPadding,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = tealColor),
                        ) {
                            val romanTestLabel = "Roman Urdu"
                            Text(romanTestLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }

                    if (isTestingTask) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            val runningLabel = "AI Processing..."
                            Text(runningLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    val activeRes = testResult
                    if (activeRes != null && activeRes.outputText.isNotBlank()) {
                        val diff = remember(testInputSnapshot, activeRes.outputText) {
                            AiDiffHighlighter.buildHighlightedDiff(testInputSnapshot, activeRes.outputText)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    emeraldGreen.copy(alpha = 0.12f),
                                    RoundedCornerShape(12.dp),
                                )
                                .border(1.dp, emeraldGreen, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val modelAttribution = "✨ Fixed by ${activeRes.providerUsed} • ${diff.changedWordCount} change(s)"
                            Text(
                                text = modelAttribution,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = emeraldGreen,
                            )
                            Text(
                                text = diff.annotatedText,
                                fontSize = 16.sp,
                                lineHeight = 23.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Inline WebView Sign-In Card rendered directly inside the main Activity layout
 * (NOT inside a Compose Dialog) so the soft keyboard opens normally on all input fields.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun InlineAccountSignInCard(
    provider: AiProviderType,
    onAccountConnected: (provider: AiProviderType, email: String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val isGemini = provider == AiProviderType.GEMINI || provider == AiProviderType.AUTO_HYBRID
    val loginUrl = if (isGemini) {
        "https://accounts.google.com/ServiceLogin?continue=https://gemini.google.com/app"
    } else {
        "https://chatgpt.com/"
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var detectedEmail by remember { mutableStateOf("") }
    var hasActiveSessionCookie by remember { mutableStateOf(false) }

    fun inspectPageAndCookies(pageText: String, currentUrl: String?) {
        val emailMatch = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").find(pageText)
        if (emailMatch != null && !emailMatch.value.endsWith("@example.com")) {
            detectedEmail = emailMatch.value
        }
        runCatching {
            val cookies = listOfNotNull(
                currentUrl?.let { CookieManager.getInstance().getCookie(it) },
                CookieManager.getInstance().getCookie("https://google.com"),
                CookieManager.getInstance().getCookie("https://gemini.google.com"),
                CookieManager.getInstance().getCookie("https://chatgpt.com"),
            ).joinToString("; ")
            if (isGemini) {
                if (cookies.contains("SID=") || cookies.contains("SAPISID=") || cookies.contains("__Secure-1PSID=")) {
                    hasActiveSessionCookie = true
                }
            } else {
                if (cookies.contains("session-token") || cookies.contains("oai-did")) {
                    hasActiveSessionCookie = true
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val titleText = if (isGemini) "Google Sign-In" else "ChatGPT Sign-In"
                Text(
                    text = titleText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { openExternalUrl(context, loginUrl) }) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        val chromeTxt = "Chrome"
                        Text(chromeTxt, fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Button(
                onClick = {
                    val wv = webViewRef
                    if (wv != null) {
                        wv.evaluateJavascript(
                            "(function(){ return (window.__chatgpt_session_json || '') + ' ' + (document.body ? document.body.innerText : ''); })();",
                        ) { res ->
                            inspectPageAndCookies(res.orEmpty(), wv.url)
                            val finalEmail = detectedEmail.ifBlank {
                                if (isGemini) "Google Gemini Session" else "ChatGPT Web Session"
                            }
                            onAccountConnected(provider, finalEmail)
                        }
                    } else {
                        val finalEmail = detectedEmail.ifBlank {
                            if (isGemini) "Google Gemini Session" else "ChatGPT Web Session"
                        }
                        onAccountConnected(provider, finalEmail)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 46.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
            ) {
                val doneLabel = when {
                    detectedEmail.isNotBlank() -> "✓ Connect ($detectedEmail)"
                    hasActiveSessionCookie -> "✓ Session Active — Tap to Save"
                    else -> "✓ Done Signing In — Connect Now"
                }
                Text(doneLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus(View.FOCUS_DOWN)
                        setOnTouchListener { v, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                                    if (!v.hasFocus()) {
                                        v.requestFocus()
                                    }
                                }
                            }
                            false
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        val cleanUa = settings.userAgentString
                            .replace("; wv)", ")")
                            .replace("; wv", "")
                            .replace(Regex("""Version/\d+\.\d+\s+"""), "")
                        settings.userAgentString = cleanUa
                        runCatching {
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(
                                    """
                                    (function(){
                                      try {
                                        if (location.hostname.indexOf('chatgpt.com') !== -1) {
                                          fetch('/api/auth/session', {credentials:'include'})
                                            .then(function(r){ return r.text(); })
                                            .then(function(t){ window.__chatgpt_session_json = t; });
                                        }
                                      } catch(e) {}
                                      return (window.__chatgpt_session_json || '') + ' ' + (document.body ? document.body.innerText : '');
                                    })();
                                    """.trimIndent(),
                                ) { res ->
                                    inspectPageAndCookies(res.orEmpty(), url)
                                }
                            }
                        }
                        loadUrl(loginUrl)
                        webViewRef = this
                    }
                },
            )
        }
    }
}

/*
 * Copyright (C) 2022-2026 The FlorisBoard & 8002Keys Contributors
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

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsApplications
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.update.AppUpdateManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.ai.AiMemoryVault
import dev.patrickgold.florisboard.ime.ai.AiProviderType
import dev.patrickgold.florisboard.ime.ai.AiWritingTask
import dev.patrickgold.florisboard.ime.ai.CloudAiClient
import dev.patrickgold.florisboard.ime.ai.launchAiAssistantSettings
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.onehanded.OneHandedMode
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showShortToastSync
import org.florisboard.lib.snygg.ui.SnyggBox

private data class SwiftKeyGridTile(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val drawableRes: Int? = null,
    val isGifBadge: Boolean = false,
    val isActive: Boolean = false,
    val onClick: () -> Unit,
)

private fun openSettingsDeepLink(context: Context, deepLink: String) {
    FlorisImeService.hideUi()
    runCatching {
        val intent = Intent(context, FlorisAppActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse(deepLink)
        }
        context.startActivity(intent)
    }.onFailure {
        FlorisImeService.launchSettings()
    }
}

@Composable
fun QuickActionsOverflowPanel() {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val subtypeManager by context.subtypeManager()
    val editorInstance by context.editorInstance()
    val windowController = FlorisImeService.windowControllerOrNull()
    val scope = rememberCoroutineScope()

    val vaultState by AiMemoryVault.state.collectAsState()
    val autoCorrectEnabled by prefs.correction.autoCorrect.collectAsState()
    val oneHandedMode by prefs.keyboard.oneHandedMode.collectAsState()
    val isIncognito = keyboardManager.activeState.isIncognitoMode

    var isReorderMode by remember { mutableStateOf(false) }
    var selectedReorderIndex by remember { mutableStateOf<Int?>(null) }

    val tileOrderIds = remember {
        mutableStateListOf(
            "account",
            "gif",
            "stickers",
            "tips",
            "settings",
            "languages",
            "layouts",
            "incognito",
            "resize",
            "themes",
            "modes",
            "emoji",
            "autocorrect",
            "google_login",
            "chatgpt_sub",
            "auto_update",
        )
    }

    LaunchedEffect(Unit) {
        AiMemoryVault.initialize(context)
    }

    fun closeOverflow() {
        keyboardManager.activeState.isActionsOverflowVisible = false
    }

    fun runQuickAiGrammarFix() {
        val content = editorInstance.activeContent
        val target = content.selectedText.ifBlank {
            content.textBeforeSelection.substringAfterLast('\n').takeLast(280)
        }.trim()
        if (target.isBlank()) {
            closeOverflow()
            keyboardManager.transitionToImeUiMode(ImeUiMode.AI_ASSISTANT)
            return
        }
        scope.launch {
            val info = editorInstance.activeInfo
            val res = CloudAiClient.executeTask(
                context = context,
                task = AiWritingTask.FIX_GRAMMAR_SPELLING,
                inputText = target,
                inputType = info.base.inputType,
                imeOptions = info.base.imeOptions,
                isIncognito = isIncognito,
            )
            val fixed = res.outputText.trim()
            if (fixed.isNotEmpty()) {
                if (content.selectedText.isNotEmpty()) {
                    editorInstance.commitText(fixed)
                } else if (content.selection.isValid) {
                    val start = (content.selection.end - target.length).coerceAtLeast(0)
                    editorInstance.setSelection(start, content.selection.end)
                    editorInstance.commitText(fixed)
                } else {
                    editorInstance.commitText(fixed)
                }
            }
            closeOverflow()
        }
    }

    val accountDisplayLabel = vaultState.swiftKeyAccountBadgeLabel()

    val tilesById = mapOf(
        "account" to SwiftKeyGridTile(
            id = "account",
            label = accountDisplayLabel,
            icon = Icons.Outlined.Person,
            isActive = vaultState.hasAnyCloudProvider,
            onClick = {
                closeOverflow()
                val targetProvider = if (!vaultState.isGeminiReady) {
                    AiProviderType.GEMINI
                } else if (!vaultState.isChatGptReady) {
                    AiProviderType.CHATGPT
                } else {
                    null
                }
                AiMemoryVault.requestDirectLoginModal(targetProvider)
                launchAiAssistantSettings(context)
            },
        ),
        "gif" to SwiftKeyGridTile(
            id = "gif",
            label = "GIF",
            isGifBadge = true,
            onClick = {
                closeOverflow()
                keyboardManager.transitionToImeUiMode(ImeUiMode.MEDIA)
            },
        ),
        "stickers" to SwiftKeyGridTile(
            id = "stickers",
            label = "Stickers",
            icon = Icons.Outlined.StickyNote2,
            onClick = {
                closeOverflow()
                keyboardManager.transitionToImeUiMode(ImeUiMode.MEDIA)
            },
        ),
        "tips" to SwiftKeyGridTile(
            id = "tips",
            label = "Tips / AI",
            icon = Icons.Outlined.Info,
            onClick = {
                closeOverflow()
                keyboardManager.transitionToImeUiMode(ImeUiMode.AI_ASSISTANT)
            },
        ),
        "settings" to SwiftKeyGridTile(
            id = "settings",
            label = "Settings",
            icon = Icons.Outlined.Settings,
            onClick = {
                closeOverflow()
                FlorisImeService.launchSettings()
            },
        ),
        "languages" to SwiftKeyGridTile(
            id = "languages",
            label = "Languages",
            icon = Icons.Outlined.Language,
            onClick = {
                if (subtypeManager.subtypes.size > 1) {
                    subtypeManager.switchToNextSubtype()
                    val active = subtypeManager.activeSubtype.primaryLocale.displayName()
                    context.showShortToastSync("Language: $active")
                } else {
                    closeOverflow()
                    openSettingsDeepLink(context, "ui://florisboard/settings/localization")
                }
            },
        ),
        "layouts" to SwiftKeyGridTile(
            id = "layouts",
            label = "Layouts",
            icon = Icons.Outlined.Keyboard,
            onClick = {
                closeOverflow()
                openSettingsDeepLink(context, "ui://florisboard/settings/localization")
            },
        ),
        "incognito" to SwiftKeyGridTile(
            id = "incognito",
            label = if (isIncognito) "Incognito ✓" else "Incognito",
            drawableRes = R.drawable.ic_incognito,
            isActive = isIncognito,
            onClick = {
                keyboardManager.handleHardwareOrSoftwareKeyEvent(
                    TextKeyData(code = KeyCode.TOGGLE_INCOGNITO_MODE),
                )
            },
        ),
        "resize" to SwiftKeyGridTile(
            id = "resize",
            label = "Resize",
            icon = Icons.Outlined.AspectRatio,
            onClick = {
                closeOverflow()
                windowController?.editor?.enable()
            },
        ),
        "themes" to SwiftKeyGridTile(
            id = "themes",
            label = "Themes",
            icon = Icons.Outlined.Palette,
            onClick = {
                closeOverflow()
                openSettingsDeepLink(context, "ui://florisboard/settings/theme")
            },
        ),
        "modes" to SwiftKeyGridTile(
            id = "modes",
            label = "Modes",
            icon = Icons.Outlined.SettingsApplications,
            isActive = oneHandedMode != OneHandedMode.OFF,
            onClick = {
                val nextMode = when (oneHandedMode) {
                    OneHandedMode.OFF -> OneHandedMode.END
                    OneHandedMode.END -> OneHandedMode.START
                    OneHandedMode.START -> OneHandedMode.OFF
                }
                scope.launch {
                    prefs.keyboard.oneHandedMode.set(nextMode)
                }
                closeOverflow()
            },
        ),
        "emoji" to SwiftKeyGridTile(
            id = "emoji",
            label = "Emoji",
            icon = Icons.Outlined.SentimentSatisfied,
            onClick = {
                closeOverflow()
                keyboardManager.transitionToImeUiMode(ImeUiMode.MEDIA)
            },
        ),
        "autocorrect" to SwiftKeyGridTile(
            id = "autocorrect",
            label = if (autoCorrectEnabled) "Auto-Correct ✓" else "Auto-Correct",
            icon = Icons.Default.Spellcheck,
            isActive = autoCorrectEnabled,
            onClick = {
                val next = !autoCorrectEnabled
                scope.launch {
                    prefs.correction.autoCorrect.set(next)
                }
                val stateLabel = if (next) "ON ✓" else "OFF"
                context.showShortToastSync("Auto-Correct: $stateLabel")
            },
        ),
        "google_login" to SwiftKeyGridTile(
            id = "google_login",
            label = if (vaultState.isGeminiReady) "Google ✓" else "Google Login",
            icon = Icons.Outlined.AccountCircle,
            isActive = vaultState.isGeminiReady,
            onClick = {
                closeOverflow()
                AiMemoryVault.requestDirectLoginModal(AiProviderType.GEMINI)
                launchAiAssistantSettings(context)
            },
        ),
        "chatgpt_sub" to SwiftKeyGridTile(
            id = "chatgpt_sub",
            label = if (vaultState.isChatGptReady) "ChatGPT ✓" else "ChatGPT Sub",
            icon = Icons.Default.AutoAwesome,
            isActive = vaultState.isChatGptReady,
            onClick = {
                closeOverflow()
                AiMemoryVault.requestDirectLoginModal(AiProviderType.CHATGPT)
                launchAiAssistantSettings(context)
            },
        ),
        "auto_update" to SwiftKeyGridTile(
            id = "auto_update",
            label = "Auto-Update",
            icon = Icons.Default.SystemUpdateAlt,
            onClick = {
                closeOverflow()
                AppUpdateManager.openAppAndShowUpdateDialog(context)
                AppUpdateManager.triggerBackgroundCheck(force = true, autoPopupIfAvailable = true)
            },
        ),
    )

    val orderedTiles = tileOrderIds.mapNotNull { tilesById[it] }

    val swiftDarkBg = Color(0xFF141414)
    val swiftTopBarBg = Color(0xFF1A1A1A)
    val swiftGridBorder = Color(0xFF262626)
    val swiftIconWhite = Color(0xFFF5F5F5)
    val swiftActiveTeal = Color(0xFF10B981)

    SnyggBox(
        elementName = FlorisImeUi.SmartbarActionsOverflow.elementName,
        modifier = Modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.keyboardUiHeight())
            .background(swiftDarkBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(swiftDarkBg),
        ) {
            // 1. SwiftKey Top Icon Toolbar (matches screenshot top bar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(swiftTopBarBg)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Copilot / AI Studio Icon
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            closeOverflow()
                            keyboardManager.transitionToImeUiMode(ImeUiMode.AI_ASSISTANT)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = swiftIconWhite,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Stickers / Emoji Icon
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            closeOverflow()
                            keyboardManager.transitionToImeUiMode(ImeUiMode.MEDIA)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.EmojiEmotions,
                        contentDescription = null,
                        tint = swiftIconWhite,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Clipboard Icon
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            closeOverflow()
                            keyboardManager.transitionToImeUiMode(ImeUiMode.CLIPBOARD)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Assignment,
                        contentDescription = null,
                        tint = swiftIconWhite,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Translate (aあ) Icon
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            closeOverflow()
                            keyboardManager.transitionToImeUiMode(ImeUiMode.AI_ASSISTANT)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = swiftIconWhite,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Grammar / Auto-Correct Award Icon
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { runQuickAiGrammarFix() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Spellcheck,
                        contentDescription = null,
                        tint = if (autoCorrectEnabled) swiftActiveTeal else swiftIconWhite,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Search Icon
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            closeOverflow()
                            keyboardManager.transitionToImeUiMode(ImeUiMode.AI_ASSISTANT)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = swiftIconWhite,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Close (✕) Icon
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { closeOverflow() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = swiftIconWhite,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = swiftGridBorder)

            // 2. Centered "Reorder items" Pill Button (matches screenshot)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(
                            width = 1.dp,
                            color = if (isReorderMode) swiftActiveTeal else Color(0xFFD4D4D4),
                            shape = RoundedCornerShape(50),
                        )
                        .clickable {
                            isReorderMode = !isReorderMode
                            selectedReorderIndex = null
                        }
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    val reorderText = if (isReorderMode) "Done reordering ✓" else "Reorder items"
                    Text(
                        text = reorderText,
                        color = if (isReorderMode) swiftActiveTeal else swiftIconWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // 3. Authentic 4-Column SwiftKey Grid with Thin Dividers
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                itemsIndexed(orderedTiles) { index, tile ->
                    val isSelectedForSwap = isReorderMode && selectedReorderIndex == index
                    Box(
                        modifier = Modifier
                            .height(66.dp)
                            .border(
                                width = if (isSelectedForSwap) 1.5.dp else 0.5.dp,
                                color = if (isSelectedForSwap) swiftActiveTeal else swiftGridBorder,
                            )
                            .background(
                                if (tile.isActive) {
                                    swiftActiveTeal.copy(alpha = 0.10f)
                                } else {
                                    swiftDarkBg
                                },
                            )
                            .clickable {
                                if (isReorderMode) {
                                    val prev = selectedReorderIndex
                                    if (prev == null) {
                                        selectedReorderIndex = index
                                    } else if (prev == index) {
                                        selectedReorderIndex = null
                                    } else {
                                        val tmp = tileOrderIds[prev]
                                        tileOrderIds[prev] = tileOrderIds[index]
                                        tileOrderIds[index] = tmp
                                        selectedReorderIndex = null
                                    }
                                } else {
                                    tile.onClick()
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            when {
                                tile.isGifBadge -> {
                                    Box(
                                        modifier = Modifier
                                            .border(1.4.dp, swiftIconWhite, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val gifLabel = "GIF"
                                        Text(
                                            text = gifLabel,
                                            color = swiftIconWhite,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                    }
                                }
                                tile.drawableRes != null -> {
                                    Icon(
                                        painter = painterResource(tile.drawableRes),
                                        contentDescription = null,
                                        tint = if (tile.isActive) swiftActiveTeal else swiftIconWhite,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                                tile.icon != null -> {
                                    Icon(
                                        imageVector = tile.icon,
                                        contentDescription = null,
                                        tint = if (tile.isActive) swiftActiveTeal else swiftIconWhite,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = tile.label,
                                color = if (tile.isActive) swiftActiveTeal else swiftIconWhite,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

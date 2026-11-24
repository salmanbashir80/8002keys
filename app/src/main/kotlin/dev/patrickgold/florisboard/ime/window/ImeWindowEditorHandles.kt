/*
 * Copyright (C) 2025-2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.window

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.delay
import org.florisboard.lib.compose.conditional
import org.florisboard.lib.compose.drawableRes
import org.florisboard.lib.compose.toDp
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

enum class ImeWindowResizeHandle(
    val left: Boolean = false,
    val top: Boolean = false,
    val right: Boolean = false,
    val bottom: Boolean = false,
) {
    LEFT(left = true),
    TOP_LEFT(top = true, left = true),
    TOP(top = true),
    TOP_RIGHT(top = true, right = true),
    RIGHT(right = true),
    BOTTOM_RIGHT(bottom = true, right = true),
    BOTTOM(bottom = true),
    BOTTOM_LEFT(bottom = true, left = true);

    fun shape(thickness: Dp, cornerRadius: Dp): Shape {
        return object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline = with(density) {
                val path = Path()
                val radius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())

                if (top || bottom) {
                    val topLeftOffset = if (bottom) Offset(0f, size.height - thickness.toPx()) else Offset.Zero
                    path.addRoundRect(
                        RoundRect(
                            left = topLeftOffset.x,
                            top = topLeftOffset.y,
                            right = topLeftOffset.x + size.width,
                            bottom = topLeftOffset.y + thickness.toPx(),
                            cornerRadius = radius,
                        )
                    )
                }

                if (left || right) {
                    val topLeftOffset = if (right) Offset(size.width - thickness.toPx(), 0f) else Offset.Zero
                    path.addRoundRect(
                        RoundRect(
                            left = topLeftOffset.x,
                            top = topLeftOffset.y,
                            right = topLeftOffset.x + thickness.toPx(),
                            bottom = topLeftOffset.y + size.height,
                            cornerRadius = radius,
                        )
                    )
                }

                return Outline.Generic(path)
            }
        }
    }
}

@Composable
private fun ImeWindowResizeHandle(
    handle: ImeWindowResizeHandle,
    modifier: Modifier,
    alphaState: State<Float>,
) {
    val windowController = LocalWindowController.current
    val prefs by FlorisPreferenceStore

    val currentAlpha by alphaState

    val constraints by rememberWindowConstraints(windowController)
    val windowConfig by windowController.activeWindowConfig.collectAsState()

    val thickness = constraints.resizeHandleDrawThickness
    val cornerRadius = constraints.resizeHandleDrawCornerRadius

    val showWindowResizeHandleBoundaries by prefs.devtools.showWindowResizeHandleBoundaries.collectAsState()

    val attributes = remember(windowConfig.mode) {
        mapOf(
            FlorisImeUi.Attr.WindowMode to windowConfig.mode.toString(),
        )
    }

    val style = rememberSnyggThemeQuery(FlorisImeUi.WindowResizeHandle.elementName, attributes)
    val handleColor by rememberUpdatedState(style.background(default = Color.Black))
    val shadowColor by rememberUpdatedState(style.shadowColor(default = Color.Black))
    val shadowElevation by rememberUpdatedState(style.shadowElevation(default = 8.dp))

    Box(
        modifier = modifier
            .size(constraints.resizeHandleTouchSize)
            .imeWindowResizeHandle(windowController, handle)
            .conditional(showWindowResizeHandleBoundaries) {
                Modifier
                    .background(Color.Yellow)
            }
            .systemGestureExclusion()
            .padding(constraints.resizeHandleDrawPadding),
    ) {
        val handleShape = remember(handle, thickness, cornerRadius) {
            handle.shape(thickness, cornerRadius)
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    this.shape = handleShape
                    this.clip = true
                    this.shadowElevation = shadowElevation.toPx()
                    this.ambientShadowColor = shadowColor
                    this.spotShadowColor = shadowColor
                    this.alpha = currentAlpha
                }
                .background(handleColor),
        )
    }
}

/**
 * The live window spec's constraints. A resize drag gives the spec new props on every frame but keeps the same
 * constraints, so reading them through this doesn't recompose the caller on each frame of the drag (issue #23).
 */
@Composable
internal fun rememberWindowConstraints(windowController: ImeWindowController): State<ImeWindowConstraints> {
    val windowSpec = windowController.activeWindowSpec.collectAsState()
    return remember(windowSpec) {
        derivedStateOf { windowSpec.value.constraints }
    }
}

@Composable
fun BoxScope.ImeWindowResizeHandlesFixed() {
    val windowController = LocalWindowController.current

    val windowSpec = windowController.activeWindowSpec.collectAsState()
    val constraints by rememberWindowConstraints(windowController)
    val windowConfig by windowController.activeWindowConfig.collectAsState()
    val editorState by windowController.editor.state.collectAsState()

    val visible by remember {
        derivedStateOf {
            editorState.isEnabled && windowSpec.value is ImeWindowSpec.Fixed
        }
    }
    val animatedAlpha = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(350),
    )

    val attributes = remember(windowConfig.mode) {
        mapOf(
            FlorisImeUi.Attr.WindowMode to windowConfig.mode
        )
    }

    val overlayStyle = rememberSnyggThemeQuery(FlorisImeUi.WindowResizeOverlayFixed.elementName)
    val overlayColor by rememberUpdatedState(overlayStyle.background(Color.Gray.copy(alpha = 0.5f)))

    if (visible) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    while (true) {
                        awaitPointerEventScope {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
                .background(overlayColor)
        )

        Column(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = constraints.resizeHandleTouchSize / 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ResizeOverlayChip(
                    label = "−",
                    onClick = { windowController.actions.stepFixedHeight((-18).dp) },
                )
                SnyggIconButton(
                    elementName = FlorisImeUi.WindowResizeAction.elementName,
                    attributes = attributes,
                    onClick = { windowController.actions.resetFixedSize() },
                ) {
                    SnyggIcon(
                        elementName = FlorisImeUi.WindowResizeAction.elementName,
                        imageVector = drawableRes(R.drawable.ic_restart_alt),
                    )
                }

                SnyggIconButton(
                    FlorisImeUi.WindowMoveHandle.elementName,
                    attributes = attributes,
                    onClick = {},
                    modifier = Modifier.imeWindowMoveHandle(windowController),
                ) {
                    SnyggIcon(
                        elementName = FlorisImeUi.WindowMoveHandle.elementName,
                        imageVector = drawableRes(R.drawable.ic_drag_pan),
                    )
                }

                SnyggIconButton(
                    elementName = FlorisImeUi.WindowResizeAction.elementName,
                    attributes = attributes,
                    onClick = { windowController.editor.disable() },
                ) {
                    SnyggIcon(
                        elementName = FlorisImeUi.WindowResizeAction.elementName,
                        imageVector = drawableRes(R.drawable.ic_check),
                    )
                }
                ResizeOverlayChip(
                    label = "+",
                    onClick = { windowController.actions.stepFixedHeight(18.dp) },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    "S" to 0.85f,
                    "M" to 1.00f,
                    "L" to 1.15f,
                    "XL" to 1.30f,
                ).forEach { (label, scale) ->
                    ResizeOverlayChip(
                        label = label,
                        onClick = { windowController.actions.setFixedHeightPreset(scale) },
                    )
                }
                ResizeOverlayChip(
                    label = "1-Hand",
                    onClick = { windowController.actions.toggleCompactLayout() },
                )
                ResizeOverlayChip(
                    label = "Float",
                    onClick = { windowController.actions.toggleFloatingWindow() },
                )
            }
        }

        ImeWindowResizeHandle(
            handle = ImeWindowResizeHandle.LEFT,
            modifier = Modifier
                .align(Alignment.CenterStart),
            alphaState = animatedAlpha,
        )
        ImeWindowResizeHandle(
            handle = ImeWindowResizeHandle.TOP_LEFT,
            modifier = Modifier
                .align(Alignment.TopStart),
            alphaState = animatedAlpha,
        )
        ImeWindowResizeHandle(
            handle = ImeWindowResizeHandle.TOP,
            modifier = Modifier
                .align(Alignment.TopCenter),
            alphaState = animatedAlpha,
        )
        ImeWindowResizeHandle(
            handle = ImeWindowResizeHandle.TOP_RIGHT,
            modifier = Modifier
                .align(Alignment.TopEnd),
            alphaState = animatedAlpha,
        )
        ImeWindowResizeHandle(
            handle = ImeWindowResizeHandle.RIGHT,
            modifier = Modifier
                .align(Alignment.CenterEnd),
            alphaState = animatedAlpha,
        )
        ImeWindowResizeHandle(
            handle = ImeWindowResizeHandle.BOTTOM_RIGHT,
            modifier = Modifier
                .align(Alignment.BottomEnd),
            alphaState = animatedAlpha,
        )
        ImeWindowResizeHandle(
            handle = ImeWindowResizeHandle.BOTTOM,
            modifier = Modifier
                .align(Alignment.BottomCenter),
            alphaState = animatedAlpha,
        )
        ImeWindowResizeHandle(
            handle = ImeWindowResizeHandle.BOTTOM_LEFT,
            modifier = Modifier
                .align(Alignment.BottomStart),
            alphaState = animatedAlpha,
        )
    }

}

@Composable
private fun ResizeOverlayChip(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun BoxScope.ImeWindowResizeHandlesFloating() {
    val windowController = LocalWindowController.current

    val windowSpec by windowController.activeWindowSpec.collectAsState()
    val editorState by windowController.editor.state.collectAsState()

    val isFloatingAndEditorAndNoGestureActive by remember {
        derivedStateOf {
            editorState.isEnabled && !editorState.isAnyGesture && windowSpec is ImeWindowSpec.Floating
        }
    }
    LaunchedEffect(isFloatingAndEditorAndNoGestureActive) {
        if (isFloatingAndEditorAndNoGestureActive) {
            delay(8000L)
            windowController.editor.disable()
        }
    }

    val visible by remember {
        derivedStateOf {
            editorState.isEnabled && !editorState.isMoveGesture && windowSpec is ImeWindowSpec.Floating
        }
    }
    val animatedAlpha = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(350),
    )

    val offset = windowSpec.constraints.resizeHandleTouchOffsetFloating
    if (visible) {
        ImeWindowResizeHandle(
            handle = ImeWindowResizeHandle.TOP_LEFT,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(-offset, -offset),
            alphaState = animatedAlpha,
        )
        ImeWindowResizeHandle(
            handle = ImeWindowResizeHandle.TOP_RIGHT,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(offset, -offset),
            alphaState = animatedAlpha,
        )
        ImeWindowResizeHandle(
            handle = ImeWindowResizeHandle.BOTTOM_RIGHT,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(offset, offset),
            alphaState = animatedAlpha,
        )
        ImeWindowResizeHandle(
            handle = ImeWindowResizeHandle.BOTTOM_LEFT,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(-offset, offset),
            alphaState = animatedAlpha,
        )
    }
}

fun Modifier.imeWindowMoveHandle(
    windowController: ImeWindowController,
    onTap: () -> Unit = {},
): Modifier = imeWindowEditorHandle(
    onTap = onTap,
    onBeginGesture = { windowController.editor.beginMoveGesture() },
    onGesture = { initialSpec, offset, rowCount, smartbarRowCount ->
        initialSpec.movedBy(offset, rowCount, smartbarRowCount).also { updatedSpec ->
            windowController.editor.onSpecUpdated(updatedSpec)
        }
    },
    onEndGesture = { windowController.editor.endMoveGesture(it) },
    onCancelGesture = { windowController.editor.cancelGesture() },
)

fun Modifier.imeWindowResizeHandle(
    windowController: ImeWindowController,
    handle: ImeWindowResizeHandle,
    onTap: () -> Unit = {},
): Modifier = imeWindowEditorHandle(
    onTap = onTap,
    onBeginGesture = { windowController.editor.beginResizeGesture() },
    onGesture = { initialSpec, offset, rowCount, smartbarRowCount ->
        initialSpec.resizedBy(offset, handle, rowCount, smartbarRowCount).also { updatedSpec ->
            windowController.editor.onSpecUpdated(updatedSpec)
        }
    },
    onEndGesture = { windowController.editor.endResizeGesture(it) },
    onCancelGesture = { windowController.editor.cancelGesture() },
)

private fun Modifier.imeWindowEditorHandle(
    onTap: () -> Unit,
    onBeginGesture: () -> ImeWindowSpec,
    onGesture: (initialSpec: ImeWindowSpec, offset: DpOffset, rowCount: Int, smartbarRowCount: Int) -> ImeWindowSpec,
    onEndGesture: (finalSpec: ImeWindowSpec) -> Unit,
    onCancelGesture: () -> Unit,
): Modifier = this.composed {
    val rowCount by FlorisImeSizing.rowCountAsState()
    val smartbarRowCount by FlorisImeSizing.smartbarRowCountAsState()
    var layoutCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Modifier
        .onGloballyPositioned { coords ->
            layoutCoordinates[0] = coords
        }
        .pointerInput(Unit) {
            detectTapGestures {
                onTap()
            }
        }
        .pointerInput(Unit) {
            var accumulatedOffset = Offset.Zero
            var startRootOffset = Offset.Zero
            var hasRootStart = false
            var initialSpec = ImeWindowSpec.Fallback
            var currentSpec = ImeWindowSpec.Fallback
            detectDragGestures(
                onDragStart = { startLocal ->
                    accumulatedOffset = Offset.Zero
                    val coords = layoutCoordinates[0]
                    if (coords != null && coords.isAttached) {
                        startRootOffset = coords.localToRoot(startLocal)
                        hasRootStart = true
                    } else {
                        hasRootStart = false
                    }
                    initialSpec = onBeginGesture()
                    currentSpec = initialSpec
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    accumulatedOffset += dragAmount
                    val coords = layoutCoordinates[0]
                    val totalOffset = if (hasRootStart && coords != null && coords.isAttached) {
                        coords.localToRoot(change.position) - startRootOffset
                    } else {
                        accumulatedOffset
                    }
                    currentSpec = onGesture(initialSpec, totalOffset.toDp(), rowCount, smartbarRowCount)
                },
                onDragEnd = {
                    onEndGesture(currentSpec)
                },
                onDragCancel = {
                    onCancelGesture()
                },
            )
        }
}

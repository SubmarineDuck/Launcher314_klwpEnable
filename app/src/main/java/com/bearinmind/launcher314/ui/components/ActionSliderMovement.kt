package com.bearinmind.launcher314.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Custom vertical slider for icon size selection — smooth dragging with animated snap-on-release. */
@Composable
fun VerticalIconSizeSlider(
    currentSize: Float,
    sliderHeight: Dp,
    isLinked: Boolean,
    onSizeChange: (Float) -> Unit,
    onSizeChangeFinished: () -> Unit,
    overflowThreshold: Float = 125f  // above this value, track/thumb turns red (visual warning only)
) {
    // Experimental (issue #50): "Extended icon sizes" opens the range to 200%.
    val extendedCtx = androidx.compose.ui.platform.LocalContext.current
    val extendedSizes = remember { com.bearinmind.launcher314.data.getExtendedIconSizes(extendedCtx) }
    val minValue = 50f
    val effectiveMax = if (extendedSizes) 200f else 125f
    // Major tick values
    val majorTickValues = if (extendedSizes) listOf(50, 100, 150, 200) else listOf(50, 75, 100, 125)
    // Minor tick values (every 5%)
    val minorTickValues = (50..effectiveMax.toInt() step 5).toList()
    // Snap to 5% increments (when linked, snap to linked values within range)
    val snapTickValues = if (isLinked) listOf(67, 80, 100, 133, 160, 200).filter { it <= effectiveMax.toInt() } else minorTickValues

    // Clamp currentSize to full range (allow dragging into red zone)
    val clampedSize = currentSize.coerceIn(minValue, effectiveMax)
    // Animated value for smooth transitions
    val animatedValue = remember { Animatable(clampedSize) }
    val coroutineScope = rememberCoroutineScope()

    // Track if user is currently dragging
    var isDragging by remember { mutableStateOf(false) }
    var isDragOnThumb by remember { mutableStateOf(false) }  // Only drag if started on thumb
    // Finger value, set synchronously for the release — animatedValue lags when busy (issue #118).
    var dragValue by remember { mutableFloatStateOf(clampedSize) }
    var downY by remember { mutableFloatStateOf(0f) }  // touch-down point for the thumb hit test
    // Fresh callbacks without re-keying pointerInput — a restart kills the drag (issue #118).
    val currentOnSizeChange by rememberUpdatedState(onSizeChange)
    val currentOnSizeChangeFinished by rememberUpdatedState(onSizeChangeFinished)

    // Sync animated value with external changes (e.g., linked slider); animate when not dragging.
    LaunchedEffect(clampedSize) {
        if (!isDragging) {
            animatedValue.animateTo(
                targetValue = clampedSize,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .height(sliderHeight)
    ) {
        // Icon Size label at top
        Text(
            text = "Icon Size",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.offset(x = 10.dp, y = (-12).dp)
        )

        // Custom vertical slider with tick marks
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Numbers on the left (only major tick values)
            BoxWithConstraints(
                modifier = Modifier
                    .width(16.dp)
                    .fillMaxHeight()
            ) {
                val totalHeight = maxHeight
                majorTickValues.reversed().forEachIndexed { index, value ->
                    val fraction = index.toFloat() / (majorTickValues.size - 1)
                    val yOffset = totalHeight * fraction
                    Text(
                        text = "$value",
                        fontSize = 8.sp,
                        fontWeight = if (currentSize.roundToInt() == value) FontWeight.Bold else FontWeight.Normal,
                        color = if (currentSize.roundToInt() == value)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(x = 4.dp, y = yOffset - 6.dp),
                        textAlign = TextAlign.End
                    )
                }
            }

            // Track, tick marks, and thumb
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onEachDown { downY = it.y }
                    .pointerInput(isLinked) { // Rebuild when linked state changes
                        detectDragGestures(
                            onDragStart = { _ ->
                                val y = downY
                                val height = size.height.toFloat()
                                // Compute thumb position from current animated value (not stale captured thumbFraction)
                                val currentThumbFraction = (1f - (animatedValue.value - minValue) / (effectiveMax - minValue)).coerceIn(0f, 1f)
                                val currentThumbY = currentThumbFraction * height
                                val thumbTouchRadius = 48.dp.toPx()  // Touch area around thumb

                                // Only start drag if touch is on the thumb
                                if (kotlin.math.abs(y - currentThumbY) <= thumbTouchRadius) {
                                    isDragging = true
                                    isDragOnThumb = true
                                    dragValue = animatedValue.value
                                } else {
                                    isDragOnThumb = false
                                }
                            },
                            onDragEnd = {
                                if (isDragOnThumb) {
                                    // Nearest tick; the red zone is a warning only (issue #118).
                                    val snappedValue = snapTickValues.minByOrNull {
                                        kotlin.math.abs(it - dragValue)
                                    }?.toFloat() ?: dragValue
                                    currentOnSizeChange(snappedValue)
                                    coroutineScope.launch {
                                        animatedValue.snapTo(dragValue) // catch up with the finger first
                                        animatedValue.animateTo(
                                            targetValue = snappedValue,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                        currentOnSizeChangeFinished()
                                    }
                                }
                                isDragging = false
                                isDragOnThumb = false
                            },
                            onDragCancel = {
                                if (isDragOnThumb) {
                                    // Same snap as onDragEnd (a cancel can carry the release).
                                    val snappedValue = snapTickValues.minByOrNull {
                                        kotlin.math.abs(it - dragValue)
                                    }?.toFloat() ?: dragValue
                                    currentOnSizeChange(snappedValue)
                                    coroutineScope.launch {
                                        animatedValue.snapTo(dragValue)
                                        animatedValue.animateTo(
                                            targetValue = snappedValue,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                    }
                                }
                                isDragging = false
                                isDragOnThumb = false
                            },
                            onDrag = { change, _ ->
                                if (isDragOnThumb) {
                                    change.consume()
                                    val y = change.position.y
                                    val height = size.height.toFloat()
                                    val fraction = 1f - (y / height).coerceIn(0f, 1f)
                                    val newValue = (minValue + fraction * (effectiveMax - minValue)).coerceIn(minValue, effectiveMax)
                                    dragValue = newValue  // synchronous — the release reads this
                                    coroutineScope.launch {
                                        animatedValue.snapTo(newValue)
                                    }
                                    currentOnSizeChange(newValue)
                                }
                            }
                        )
                    }
            ) {
                val trackHeight = maxHeight

                val overflowRed = Color(0xFFCC4444)
                val normalTrackColor = MaterialTheme.colorScheme.onSurface
                val overflowRange = effectiveMax - overflowThreshold
                // How red the overflow zone should be — driven by thumb position
                val zoneRedFraction = if (overflowThreshold >= effectiveMax || animatedValue.value <= overflowThreshold) 0f
                    else if (overflowRange > 0f) ((animatedValue.value - overflowThreshold) / overflowRange).coerceIn(0f, 1f) else 1f

                // Vertical track line
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                        .background(normalTrackColor.copy(alpha = 0.3f))
                )
                // Red tint overlay on track — gradient fade at the threshold boundary
                if (overflowThreshold < effectiveMax && zoneRedFraction > 0f) {
                    val thresholdFraction = (1f - (overflowThreshold - minValue) / (effectiveMax - minValue)).coerceIn(0f, 1f)
                    // Extend 5% past threshold for a smooth fade
                    val fadePadding = 0.05f * (1f - thresholdFraction)
                    val extendedFraction = (thresholdFraction + fadePadding).coerceAtMost(1f)
                    val tintColor = overflowRed.copy(alpha = zoneRedFraction * 0.8f)
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight(extendedFraction)
                            .align(Alignment.TopCenter)
                            .background(Brush.verticalGradient(
                                0f to tintColor,
                                (thresholdFraction / extendedFraction).coerceIn(0f, 1f) to tintColor,
                                1f to Color.Transparent
                            ))
                    )
                }

                // Minor tick marks (every 5%)
                minorTickValues.forEach { value ->
                    if (value !in majorTickValues) {
                        val fraction = 1f - (value - minValue) / (effectiveMax - minValue)
                        val yOffset = trackHeight * fraction

                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(1.dp)
                                .align(Alignment.TopCenter)
                                .offset(y = yOffset - 0.5.dp)
                                .background(normalTrackColor.copy(alpha = 0.3f))
                        )
                        // Red tint overlay on tick
                        if (overflowThreshold < effectiveMax && value.toFloat() > overflowThreshold && zoneRedFraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(1.dp)
                                    .align(Alignment.TopCenter)
                                    .offset(y = yOffset - 0.5.dp)
                                    .background(overflowRed.copy(alpha = zoneRedFraction * 0.8f))
                            )
                        }
                    }
                }

                // Major tick marks
                majorTickValues.reversed().forEachIndexed { index, value ->
                    val fraction = index.toFloat() / (majorTickValues.size - 1)
                    val yOffset = trackHeight * fraction

                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .height(2.dp)
                            .align(Alignment.TopCenter)
                            .offset(y = yOffset - 1.dp)
                            .background(normalTrackColor.copy(alpha = 0.5f))
                    )
                    // Red tint overlay on tick
                    if (overflowThreshold < effectiveMax && value.toFloat() > overflowThreshold && zoneRedFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .width(8.dp)
                                .height(2.dp)
                                .align(Alignment.TopCenter)
                                .offset(y = yOffset - 1.dp)
                                .background(overflowRed.copy(alpha = zoneRedFraction * 0.8f))
                        )
                    }
                }

                // Touch area indicator (visible when dragging)
                val thumbFraction = (1f - (animatedValue.value - minValue) / (effectiveMax - minValue)).coerceIn(0f, 1f)
                val thumbYOffset = trackHeight * thumbFraction

                // Thumb color: normal when at or below threshold, gradient red only when past it
                val primaryColor = MaterialTheme.colorScheme.primary
                val thumbColor = if (overflowThreshold >= effectiveMax || animatedValue.value <= overflowThreshold) {
                    primaryColor
                } else {
                    val t = if (overflowRange > 0f) ((animatedValue.value - overflowThreshold) / overflowRange).coerceIn(0f, 1f) else 1f
                    androidx.compose.ui.graphics.lerp(primaryColor, overflowRed, t)
                }

                if (isDragging) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.TopCenter)
                            .offset(y = thumbYOffset - 24.dp)
                            .clip(CircleShape)
                            .background(thumbColor.copy(alpha = 0.15f))
                    )
                }

                // Thumb - horizontal rectangle (red when in overflow zone)
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(6.dp)
                        .align(Alignment.TopCenter)
                        .offset(y = thumbYOffset - 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(thumbColor)
                )
            }
        }
    }
}

/** Custom horizontal slider for column count selection — smooth dragging with animated snap-on-release. */
@Composable
fun BottomColumnsSlider(
    currentSize: Float,
    onSizeChange: (Float) -> Unit,
    onSizeChangeFinished: () -> Unit
) {
    // Experimental (issue #105): "Extended grid size" opens the column range to 20.
    val extGridCtx = androidx.compose.ui.platform.LocalContext.current
    val extendedGrid = remember { com.bearinmind.launcher314.data.getExtendedGridSize(extGridCtx) }
    val tickValues = if (extendedGrid) (3..20).toList() else listOf(3, 4, 5, 6, 7)
    val minValue = 3f
    val maxValue = if (extendedGrid) 20f else 7f

    // Animated value for smooth transitions
    val animatedValue = remember { Animatable(currentSize) }
    val coroutineScope = rememberCoroutineScope()

    // Track if user is currently dragging
    var isDragging by remember { mutableStateOf(false) }
    var isDragOnThumb by remember { mutableStateOf(false) }  // Only drag if started on thumb
    // Finger value, set synchronously for the release — animatedValue lags when busy (issue #118).
    var dragValue by remember { mutableFloatStateOf(currentSize) }
    var downX by remember { mutableFloatStateOf(0f) }  // touch-down point for the thumb hit test

    // Sync animated value with external changes (e.g., linked slider); animate when not dragging.
    LaunchedEffect(currentSize) {
        if (!isDragging) {
            animatedValue.animateTo(
                targetValue = currentSize,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    // Thumb position for both boxes, clamped so out-of-range values stay on the track (issue #118)
    val thumbFraction = ((animatedValue.value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Wrapper Box to allow touch indicator overflow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp), // Taller to accommodate the circle
            contentAlignment = Alignment.Center
        ) {
            // Custom horizontal slider
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .onEachDown { downX = it.x }
                    .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { _ ->
                            val x = downX
                            val width = size.width.toFloat()
                            // Compute thumb position from current animated value (not stale captured thumbFraction)
                            val currentThumbFraction = ((animatedValue.value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
                            val currentThumbX = currentThumbFraction * width
                            val thumbTouchRadius = 48.dp.toPx()  // Touch area around thumb

                            // Only start drag if touch is on the thumb
                            if (kotlin.math.abs(x - currentThumbX) <= thumbTouchRadius) {
                                isDragging = true
                                isDragOnThumb = true
                                dragValue = animatedValue.value
                            } else {
                                isDragOnThumb = false
                            }
                        },
                        onDragEnd = {
                            if (isDragOnThumb) {
                                // Calculate snapped value immediately
                                val snappedValue = tickValues.minByOrNull {
                                    kotlin.math.abs(it - dragValue)
                                }?.toFloat() ?: dragValue
                                // Update parent state with snapped value first
                                onSizeChange(snappedValue)
                                // Then animate to snapped position
                                coroutineScope.launch {
                                    animatedValue.snapTo(dragValue) // catch up with the finger first
                                    animatedValue.animateTo(
                                        targetValue = snappedValue,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                    onSizeChangeFinished()
                                }
                            }
                            isDragging = false
                            isDragOnThumb = false
                        },
                        onDragCancel = {
                            if (isDragOnThumb) {
                                // Calculate snapped value immediately
                                val snappedValue = tickValues.minByOrNull {
                                    kotlin.math.abs(it - dragValue)
                                }?.toFloat() ?: dragValue
                                // Update parent state with snapped value first
                                onSizeChange(snappedValue)
                                // Then animate to snapped position
                                coroutineScope.launch {
                                    animatedValue.snapTo(dragValue)
                                    animatedValue.animateTo(
                                        targetValue = snappedValue,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                            isDragging = false
                            isDragOnThumb = false
                        },
                        onDrag = { change, _ ->
                            if (isDragOnThumb) {
                                change.consume()
                                val x = change.position.x
                                val width = size.width.toFloat()
                                val fraction = (x / width).coerceIn(0f, 1f)
                                val newValue = (minValue + fraction * (maxValue - minValue)).coerceIn(minValue, maxValue)
                                dragValue = newValue  // synchronous — the release reads this
                                coroutineScope.launch {
                                    animatedValue.snapTo(newValue)
                                }
                                onSizeChange(newValue)
                            }
                        }
                    )
                }
            ) {
                val trackWidth = maxWidth
                val trackCenterY = maxHeight / 2

                // Horizontal track line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                )

                // Tick marks
                tickValues.forEachIndexed { index, _ ->
                    val fraction = index.toFloat() / (tickValues.size - 1)
                    val xOffset = trackWidth * fraction

                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(8.dp)
                            .offset(x = xOffset - 1.dp, y = trackCenterY - 4.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    )
                }

                // Thumb - vertical rectangle
                val thumbXOffset = trackWidth * thumbFraction
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(20.dp)
                        .offset(x = thumbXOffset - 3.dp, y = trackCenterY - 10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            } // End BoxWithConstraints

            // Touch area indicator drawn in wrapper Box (outside BoxWithConstraints to avoid clipping)
            if (isDragging) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val thumbXOffset = maxWidth * thumbFraction
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .offset(x = thumbXOffset - 24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    )
                }
            }
        } // End wrapper Box

        // Number labels
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            val trackWidth = maxWidth
            tickValues.forEachIndexed { index, value ->
                val fraction = index.toFloat() / (tickValues.size - 1)
                val xOffset = trackWidth * fraction

                Text(
                    text = "$value",
                    fontSize = 10.sp,
                    fontWeight = if (currentSize.roundToInt() == value) FontWeight.Bold else FontWeight.Normal,
                    color = if (currentSize.roundToInt() == value)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.offset(x = xOffset - 4.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        // App Drawer Columns label
        Text(
            text = "App Drawer Columns",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Center
        )
    }
}

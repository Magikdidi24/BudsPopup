package io.github.magikdidi24.budspopup.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.magikdidi24.budspopup.R
import io.github.magikdidi24.budspopup.protocol.BudsColor
import io.github.magikdidi24.budspopup.protocol.BudsStatus
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Horizontal centre of the left bud, the case and the right bud in the 3D view (tools/build_model.py prints them).
private val GAUGE_X = floatArrayOf(0.249f, 0.5f, 0.751f)
private val GREEN = Color(0xFF34C759)
private val ORANGE = Color(0xFFFF9500)
private val RED = Color(0xFFFF3B30)

private fun levelColor(level: Int) = when {
    level >= 50 -> GREEN
    level >= 20 -> ORANGE
    else -> RED
}

@Composable
fun PopupCard(
    title: String,
    status: BudsStatus?,
    color: BudsColor,
    visible: MutableTransitionState<Boolean>,
    onDismiss: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF1C1C1E) else Color.White
    val foreground = if (dark) Color.White else Color.Black
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visibleState = visible,
        enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it },
        exit = slideOutVertically(tween(250)) { it },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(10.dp)
                .offset { IntOffset(0, drag.value.roundToInt()) }
                .draggable(
                    rememberDraggableState { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } },
                    Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (drag.value > 150f || velocity > 1500f) onDismiss() else drag.animateTo(0f, spring())
                    },
                )
                .shadow(24.dp, RoundedCornerShape(32.dp))
                .clip(RoundedCornerShape(32.dp))
                .background(background)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    title,
                    Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                    color = foreground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                CloseButton(foreground, onDismiss, Modifier.align(Alignment.CenterEnd))
            }
            BudsScene(
                color,
                leftOut = status?.placementLeft?.isOut == true,
                rightOut = status?.placementRight?.isOut == true,
                modifier = Modifier.fillMaxWidth().aspectRatio(SCENE_ASPECT),
            )
            BatteryRow(status, foreground)
        }
    }
}

@Composable
private fun CloseButton(foreground: Color, onClick: () -> Unit, modifier: Modifier) {
    val label = stringResource(R.string.close)
    Box(
        modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(foreground.copy(alpha = 0.1f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text("✕", color = foreground.copy(alpha = 0.6f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BatteryRow(status: BudsStatus?, foreground: Color) {
    Layout(
        content = {
            // A bud's level shows only once it is out, as it lands beside the case; the case's always shows.
            BudGauge(status?.placementLeft?.isOut == true) {
                BatteryGauge(status?.left, status?.leftCharging == true, stringResource(R.string.left), foreground)
            }
            BatteryGauge(status?.case, status?.caseCharging == true, stringResource(R.string.case_label), foreground)
            BudGauge(status?.placementRight?.isOut == true) {
                BatteryGauge(status?.right, status?.rightCharging == true, stringResource(R.string.right), foreground)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        layout(constraints.maxWidth, placeables.maxOf { it.height }) {
            placeables.forEachIndexed { i, p -> p.place((GAUGE_X[i] * constraints.maxWidth - p.width / 2f).roundToInt(), 0) }
        }
    }
}

@Composable
private fun BudGauge(out: Boolean, content: @Composable () -> Unit) {
    Box { // always laid out, even empty, so the row keeps each gauge in its own slot
        AnimatedVisibility(
            visible = out,
            enter = fadeIn(tween(400, delayMillis = 700)) + scaleIn(tween(400, delayMillis = 700), initialScale = 0.6f),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.6f),
        ) { content() }
    }
}

@Composable
private fun BatteryGauge(level: Int?, charging: Boolean, label: String, foreground: Color) {
    val progress by animateFloatAsState((level ?: 0) / 100f, tween(900), label = "battery")
    val ring = level?.let(::levelColor) ?: foreground.copy(alpha = 0.12f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 5.dp.toPx()
                val topLeft = Offset(stroke / 2, stroke / 2)
                val arcSize = Size(size.width - stroke, size.height - stroke)
                // An empty battery still gets a visible red circle.
                val track = if (level != null && level < 20) RED.copy(alpha = 0.35f) else foreground.copy(alpha = 0.12f)
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                if (level != null && level > 0) {
                    drawArc(ring, -90f, 360f * progress, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Text(level?.let { "$it %" } ?: "–", color = foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (charging) Bolt(ring)
            Text(label, color = foreground.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun Bolt(color: Color) {
    Canvas(Modifier.size(width = 8.dp, height = 12.dp)) {
        val w = size.width
        val h = size.height
        drawPath(
            Path().apply {
                moveTo(w * 0.62f, 0f); lineTo(0f, h * 0.58f); lineTo(w * 0.45f, h * 0.58f)
                lineTo(w * 0.35f, h); lineTo(w, h * 0.4f); lineTo(w * 0.55f, h * 0.4f); close()
            },
            color,
        )
    }
}

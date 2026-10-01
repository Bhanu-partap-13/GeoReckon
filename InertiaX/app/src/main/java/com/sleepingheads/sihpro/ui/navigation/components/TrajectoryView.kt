package com.sleepingheads.sihpro.ui.navigation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepingheads.sihpro.data.model.DemoSample
import com.sleepingheads.sihpro.data.model.TripMetadata
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalTextApi::class)
@Composable
fun TrajectoryView(
    allSamples: List<DemoSample>,
    currentSample: DemoSample?,
    metadata: TripMetadata?,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(0.45f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var autoFollow by remember { mutableStateOf(true) }

    val originLat = metadata?.originLat ?: 28.6315
    val originLon = metadata?.originLon ?: 77.2167
    val metersPerDegLat = 111320.0
    val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(originLat))

    Box(modifier = modifier.background(Color(0xFF0F141C))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        autoFollow = false
                        scale = (scale * zoom).coerceIn(0.1f, 3.0f)
                        panOffset += pan
                    }
                }
        ) {
            val canvasCenter = Offset(size.width / 2f, size.height / 2f)

            // Convert world meters (East, North) to canvas coordinates
            fun toCanvas(localX: Double, localY: Double): Offset {
                val screenX = canvasCenter.x + (localX.toFloat() * scale) + panOffset.x
                val screenY = canvasCenter.y - (localY.toFloat() * scale) + panOffset.y // Invert Y (North is up)
                return Offset(screenX, screenY)
            }

            // Auto-follow vehicle
            if (autoFollow && currentSample != null) {
                panOffset = Offset(
                    -currentSample.estX.toFloat() * scale,
                    currentSample.estY.toFloat() * scale
                )
            }

            // 1. Draw coordinate grid & distance markers
            drawSpatialGrid(scale, panOffset, canvasCenter)

            // 2. Draw Tunnel Outage Zone
            val outStart = metadata?.outageStartSec ?: 35.0
            val outEnd = metadata?.outageEndSec ?: 75.0
            val outageSamples = allSamples.filter {
                val s = it.timestampMs / 1000.0
                s in outStart..outEnd
            }
            if (outageSamples.size >= 2) {
                val tunnelPath = Path().apply {
                    val first = outageSamples.first()
                    val startPt = toCanvas(first.estX, first.estY)
                    moveTo(startPt.x, startPt.y)
                    for (i in 1 until outageSamples.size) {
                        val pt = toCanvas(outageSamples[i].estX, outageSamples[i].estY)
                        lineTo(pt.x, pt.y)
                    }
                }
                drawPath(
                    path = tunnelPath,
                    color = Color(0x33FF9800),
                    style = Stroke(width = 32f * scale, cap = StrokeCap.Round)
                )
            }

            // 3. Draw Ground Truth Reference Path (Green)
            if (allSamples.size >= 2) {
                val refPath = Path().apply {
                    val first = allSamples.first()
                    val p0 = toCanvas(first.trueX, first.trueY)
                    moveTo(p0.x, p0.y)
                    for (i in 1 until allSamples.size) {
                        val s = allSamples[i]
                        val pt = toCanvas(s.trueX, s.trueY)
                        lineTo(pt.x, pt.y)
                    }
                }
                drawPath(
                    path = refPath,
                    color = Color(0xFF10B981),
                    style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                )
            }

            val currentIdx = allSamples.indexOf(currentSample).coerceAtLeast(0)

            // 4. Draw Conventional Naive INS (Red - diverging wildly into the buildings)
            if (currentIdx > 0) {
                val naivePath = Path().apply {
                    val p0 = toCanvas(allSamples[0].naiveX, allSamples[0].naiveY)
                    moveTo(p0.x, p0.y)
                    for (i in 1..currentIdx) {
                        val pt = toCanvas(allSamples[i].naiveX, allSamples[i].naiveY)
                        lineTo(pt.x, pt.y)
                    }
                }
                drawPath(
                    path = naivePath,
                    color = Color(0xFFEF4444),
                    style = Stroke(
                        width = 3.0f,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                )
            }

            // 5. Draw DRISHTI Trajectory Driven So Far (Glowing Cyan)
            if (currentIdx > 0) {
                val drivenPath = Path().apply {
                    val p0 = toCanvas(allSamples[0].estX, allSamples[0].estY)
                    moveTo(p0.x, p0.y)
                    for (i in 1..currentIdx) {
                        val pt = toCanvas(allSamples[i].estX, allSamples[i].estY)
                        lineTo(pt.x, pt.y)
                    }
                }

                drawPath(
                    path = drivenPath,
                    color = Color(0xFF00F2FE),
                    style = Stroke(width = 5.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // 6. Draw Vehicle Marker and Uncertainty Ellipse
            if (currentSample != null) {
                val vehiclePos = toCanvas(currentSample.estX, currentSample.estY)
                val uncertaintyRadiusPx = (currentSample.uncertaintyM.toFloat() * scale).coerceAtLeast(10f)

                val accentColor = when (currentSample.mode) {
                    "DEAD_RECKONING" -> Color(0xFF00F2FE)
                    "REACQUIRING" -> Color(0xFFF59E0B)
                    else -> Color(0xFF10B981)
                }

                // Uncertainty halo
                drawCircle(
                    color = accentColor.copy(alpha = 0.2f),
                    radius = uncertaintyRadiusPx,
                    center = vehiclePos,
                    style = Fill
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.8f),
                    radius = uncertaintyRadiusPx,
                    center = vehiclePos,
                    style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f))
                )

                // Vehicle chevron oriented towards heading
                drawVehicleChevron(vehiclePos, currentSample.headingDeg.toFloat(), accentColor)

                // Naive INS position marker if in outage
                if (currentSample.mode == "DEAD_RECKONING" && currentSample.naiveErrorM > 25.0) {
                    val naivePt = toCanvas(currentSample.naiveX, currentSample.naiveY)
                    drawCircle(
                        color = Color(0xFFEF4444),
                        radius = 6f,
                        center = naivePt,
                        style = Fill
                    )
                    // Line from vehicle to naive drift point
                    drawLine(
                        color = Color(0x66EF4444),
                        start = vehiclePos,
                        end = naivePt,
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                }
            }
        }

        // Top Legend HUD
        Card(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xD90C1220)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(12.dp, 3.dp).background(Color(0xFF10B981)))
                    Text("GROUND TRUTH", fontSize = 10.sp, color = Color(0xFF94A3B8), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(12.dp, 3.dp).background(Color(0xFF00F2FE)))
                    Text("DRISHTI (AI + NHC + MAP)", fontSize = 10.sp, color = Color(0xFF00F2FE), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(12.dp, 3.dp).background(Color(0xFFEF4444)))
                    Text("NAIVE INS DRIFT", fontSize = 10.sp, color = Color(0xFFEF4444), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }

        // Floating GNSS Denied HUD during outage
        if (currentSample?.mode == "DEAD_RECKONING") {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xE6B91C1C)),
                shape = RoundedCornerShape(6.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️ GNSS DENIED", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White)
                    Text("ESTIMATED DRIFT: ${currentSample.errorM} m  |  NAIVE: ${currentSample.naiveErrorM} m", fontSize = 10.sp, color = Color(0xFFFECACA), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }

        // Map Control Floating Buttons (Recenter, Zoom In, Zoom Out)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(
                onClick = { autoFollow = true },
                containerColor = if (autoFollow) MaterialTheme.colorScheme.primary else Color(0xFF1E2638),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = "Recenter on vehicle", modifier = Modifier.size(20.dp))
            }

            FloatingActionButton(
                onClick = { scale = (scale * 1.25f).coerceAtMost(3.0f) },
                containerColor = Color(0xFF1E2638),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom In", modifier = Modifier.size(20.dp))
            }

            FloatingActionButton(
                onClick = { scale = (scale / 1.25f).coerceAtLeast(0.1f) },
                containerColor = Color(0xFF1E2638),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom Out", modifier = Modifier.size(20.dp))
            }
        }

        // Legend Badge
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0xCC131924), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(10.dp, 3.dp).background(Color(0xFF00E676)))
                Text("Reference Path", color = Color(0xFFB0BEC5), fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(10.dp, 3.dp).background(Color(0xFFFF9800)))
                Text("Dead Reckoning", color = Color(0xFFB0BEC5), fontSize = 11.sp)
            }
        }
    }
}

private fun DrawScope.drawSpatialGrid(scale: Float, panOffset: Offset, center: Offset) {
    val step = (100f * scale).coerceAtLeast(40f)
    val width = size.width
    val height = size.height
    val gridColor = Color(0x11FFFFFF)

    var x = (center.x + panOffset.x) % step
    while (x < width) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, height), strokeWidth = 1f)
        x += step
    }

    var y = (center.y + panOffset.y) % step
    while (y < height) {
        drawLine(gridColor, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
        y += step
    }
}

private fun DrawScope.drawVehicleChevron(center: Offset, headingDeg: Float, color: Color) {
    val angleRad = Math.toRadians(headingDeg.toDouble() - 90.0) // 0 deg is East
    val length = 18f
    val wingSpan = 13f

    val tip = Offset(
        (center.x + length * cos(angleRad)).toFloat(),
        (center.y + length * sin(angleRad)).toFloat()
    )
    val leftWing = Offset(
        (center.x + wingSpan * cos(angleRad + 2.5)).toFloat(),
        (center.y + wingSpan * sin(angleRad + 2.5)).toFloat()
    )
    val rightWing = Offset(
        (center.x + wingSpan * cos(angleRad - 2.5)).toFloat(),
        (center.y + wingSpan * sin(angleRad - 2.5)).toFloat()
    )

    val chevron = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(leftWing.x, leftWing.y)
        lineTo(center.x, center.y)
        lineTo(rightWing.x, rightWing.y)
        close()
    }

    drawPath(chevron, color, style = Fill)
    drawPath(chevron, Color.White, style = Stroke(width = 1.5f))
    drawCircle(Color.White, radius = 2.5f, center = center)
}

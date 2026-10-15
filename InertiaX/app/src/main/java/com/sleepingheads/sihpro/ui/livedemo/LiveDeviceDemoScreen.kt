package com.sleepingheads.sihpro.ui.livedemo

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepingheads.sihpro.network.GeoReckonCloudManager
import com.sleepingheads.sihpro.network.GeoReckonConnectionState
import com.sleepingheads.sihpro.network.SocketConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiveDeviceDemoScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Real Socket.IO & Cloud State
    val socketState by GeoReckonCloudManager.socketState.collectAsState()
    val connState by GeoReckonCloudManager.connectionState.collectAsState()
    val rttMs by GeoReckonCloudManager.lastRttMs.collectAsState()
    val lastPosition by GeoReckonCloudManager.lastPosition.collectAsState()
    val lastCalibration by GeoReckonCloudManager.lastCalibration.collectAsState()
    val liveDemoState by GeoReckonCloudManager.liveDemoState.collectAsState()

    // Real Hardware Metrics
    val isAccelActive by GeoReckonCloudManager.isAccelActive.collectAsState()
    val isGyroActive by GeoReckonCloudManager.isGyroActive.collectAsState()
    val isGravityActive by GeoReckonCloudManager.isGravityActive.collectAsState()
    val lastGnssLocation by GeoReckonCloudManager.lastGnssLocation.collectAsState()
    val isGnssActive by GeoReckonCloudManager.isGnssActive.collectAsState()
    val sensorRateHz by GeoReckonCloudManager.sensorRateHz.collectAsState()
    val packetIntervalMs by GeoReckonCloudManager.packetIntervalMs.collectAsState()

    // Real Network Metrics
    val packetsSent by GeoReckonCloudManager.packetsSent.collectAsState()
    val packetsReceived by GeoReckonCloudManager.packetsReceived.collectAsState()
    val dropsCount by GeoReckonCloudManager.dropsCount.collectAsState()
    val errorsCount by GeoReckonCloudManager.errorsCount.collectAsState()

    // Real Event Log
    val eventLog by GeoReckonCloudManager.liveEventLog.collectAsState()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header with Status Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.size(40.dp).background(Color(0x22FFFFFF), CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "LIVE DEVICE DEMONSTRATION",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Real Physical Sensors → AWS EC2 Pipeline",
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp
                )
            }

            // Connection Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (socketState == SocketConnectionState.CONNECTED) Color(0x2200E676) else Color(0x22FF5252))
                    .border(1.dp, if (socketState == SocketConnectionState.CONNECTED) Color(0xFF00E676) else Color(0xFFFF5252), RoundedCornerShape(20.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier.size(6.dp).background(
                        if (socketState == SocketConnectionState.CONNECTED) Color(0xFF00E676) else Color(0xFFFF5252),
                        CircleShape
                    )
                )
                Text(
                    text = socketState.name,
                    color = if (socketState == SocketConnectionState.CONNECTED) Color(0xFF00E676) else Color(0xFFFF5252),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 2. Control Action Panel (Start / Calibrate / Stop & Reset)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
            shape = RoundedCornerShape(18.dp),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = Brush.horizontalGradient(listOf(Color(0xFF00E676), Color(0xFF00E5FF)))
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SESSION STATUS: $liveDemoState",
                        color = Color(0xFF00E676),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "allow_low_health=false",
                        color = Color(0xFF90A4AE),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { GeoReckonCloudManager.startLivePipeline(context, scope) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("START TEST", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    FilledTonalButton(
                        onClick = { GeoReckonCloudManager.triggerLiveCalibration() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF2C384E)),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFF00E5FF))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("CALIBRATE", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    FilledTonalButton(
                        onClick = { GeoReckonCloudManager.stopAndResetLivePipeline() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF3E2723)),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, tint = Color(0xFFFF5252))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("RESET", color = Color(0xFFFF5252), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Section 1: CLOUD CONNECTION
        LiveSection(title = "1. CLOUD CONNECTION") {
            LiveRow("Connection Status", socketState.name, if (socketState == SocketConnectionState.CONNECTED) Color(0xFF00E676) else Color(0xFFFF5252))
            LiveRow("Endpoint", "${GeoReckonCloudManager.config.host}:${GeoReckonCloudManager.config.port}")
            LiveRow("Transport Layer", "WebSocket (101 Switching Protocols)")
            LiveRow("Ping RTT", if (rttMs > 0) String.format("%.1f ms", rttMs) else "N/A")
            LiveRow("Active Session ID", GeoReckonCloudManager.client.getActiveSessionId())
            LiveRow("Cloud Server", "AWS EC2 Ubuntu 24.04")
        }

        // Section 2: REAL CALIBRATION
        val calStatus = when {
            lastCalibration != null -> "READY"
            connState == GeoReckonConnectionState.CALIBRATING -> "CALIBRATING"
            connState == GeoReckonConnectionState.ERROR -> "FAILED"
            else -> "NOT CALIBRATED"
        }
        LiveSection(title = "2. REAL CALIBRATION") {
            LiveRow("Calibration Status", calStatus, if (calStatus == "READY") Color(0xFF00E676) else Color(0xFFFFB300))
            LiveRow("Alignment Health", lastCalibration?.let { String.format("%.4f", it.alignmentHealth) } ?: "N/A")
            LiveRow("Degraded Mode", lastCalibration?.let { if (it.degraded) "TRUE" else "FALSE" } ?: "N/A")
            LiveRow("Session State", connState.name)
            LiveRow("Rule", "allow_low_health = false (Strict Production)")
        }

        // Section 3: REAL IMU TELEMETRY
        LiveSection(title = "3. REAL IMU TELEMETRY") {
            LiveRow("Accelerometer", if (isAccelActive) "ACTIVE" else "INACTIVE", if (isAccelActive) Color(0xFF00E676) else Color(0xFFFF5252))
            LiveRow("Gyroscope", if (isGyroActive) "ACTIVE" else "INACTIVE", if (isGyroActive) Color(0xFF00E676) else Color(0xFFFF5252))
            LiveRow("Gravity Sensor", if (isGravityActive) "ACTIVE" else "INACTIVE", if (isGravityActive) Color(0xFF00E676) else Color(0xFFFF5252))
            LiveRow("Streaming Rate", String.format("%.1f Hz", sensorRateHz))
            LiveRow("Target Rate", "10 Hz (100 ms interval)")
            LiveRow("Packets Sent", "$packetsSent")
            LiveRow("Packets Received", "$packetsReceived")
            LiveRow("Packet Interval (dt)", String.format("%.1f ms", packetIntervalMs))
            LiveRow("Drops / Errors", "$dropsCount / $errorsCount")
        }

        // Section 4: REAL GNSS TELEMETRY
        val gnssStatus = when {
            lastGnssLocation != null && isGnssActive -> "AVAILABLE"
            isGnssActive -> "SEARCHING"
            else -> "NO FIX"
        }
        LiveSection(title = "4. REAL GNSS TELEMETRY") {
            LiveRow("GNSS Hardware", gnssStatus, if (gnssStatus == "AVAILABLE") Color(0xFF00E676) else Color(0xFFFFB300))
            LiveRow("Latitude", lastGnssLocation?.let { String.format("%.6f°", it.latitude) } ?: "N/A")
            LiveRow("Longitude", lastGnssLocation?.let { String.format("%.6f°", it.longitude) } ?: "N/A")
            LiveRow("Accuracy", lastGnssLocation?.let { String.format("%.1f m", it.accuracy) } ?: "N/A")
            LiveRow("Altitude", lastGnssLocation?.let { String.format("%.1f m", it.altitude) } ?: "N/A")
            LiveRow("Last GNSS Fix", lastGnssLocation?.let { timeFormat.format(Date(it.time)) } ?: "N/A")
        }

        // Section 5: LIVE DEAD RECKONING
        val drStatus = when {
            connState == GeoReckonConnectionState.DEAD_RECKONING -> "ACTIVE"
            connState == GeoReckonConnectionState.ERROR -> "ERROR"
            else -> "IDLE"
        }
        LiveSection(title = "5. LIVE DEAD RECKONING") {
            LiveRow("Engine Status", drStatus, if (drStatus == "ACTIVE") Color(0xFFFF9100) else Color(0xFF00E5FF))
            LiveRow("Latitude", lastPosition?.let { String.format("%.6f°", it.lat) } ?: "N/A")
            LiveRow("Longitude", lastPosition?.let { String.format("%.6f°", it.lon) } ?: "N/A")
            LiveRow("Speed", lastPosition?.let { String.format("%.2f m/s (%.1f km/h)", it.speedMps, it.speedMps * 3.6) } ?: "N/A")
            LiveRow("Heading", lastPosition?.let { String.format("%.1f°", it.bearingDeg) } ?: "N/A")
            LiveRow("Distance", lastPosition?.let { String.format("%.1f m", it.drDistanceM ?: 0.0) } ?: "N/A")
            LiveRow("Last Update", lastPosition?.let { timeFormat.format(Date()) } ?: "N/A")
        }

        // Section 6: POSITION SOURCE
        val positionSource = when {
            connState == GeoReckonConnectionState.DEAD_RECKONING -> "DEAD RECKONING ACTIVE"
            gnssStatus == "AVAILABLE" -> "GNSS"
            else -> "WAITING"
        }
        LiveSection(title = "6. POSITION SOURCE") {
            LiveRow("Active Source", positionSource, if (positionSource == "DEAD RECKONING ACTIVE") Color(0xFFFF9100) else Color(0xFF00E676))
        }

        // Section 7: KINONET-R2
        LiveSection(title = "7. KINONET-R2 STATUS") {
            LiveRow("Server KinoNet", "LOADED", Color(0xFF00E676))
            LiveRow("Runtime Execution", "SHADOW (PyTorch CPU on EC2)", Color(0xFF00E5FF))
            LiveRow("Android KinoNet", "NOT EXECUTED", Color(0xFF90A4AE))
        }

        // Section 8: LIVE EVENT LOG
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0x22FFFFFF), Color(0x11FFFFFF))))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "8. LIVE DEVICE EVENT LOG",
                    color = Color(0xFF00E5FF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 4.dp))
                if (eventLog.isEmpty()) {
                    Text("No events recorded yet", color = Color(0xFF78909C), fontSize = 11.sp)
                } else {
                    eventLog.take(12).forEach { entry ->
                        Text(
                            text = entry,
                            color = Color(0xFFB0BEC5),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0x22FFFFFF), Color(0x11FFFFFF))))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 4.dp))
            content()
        }
    }
}

@Composable
private fun LiveRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFF90A4AE), fontSize = 12.sp)
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

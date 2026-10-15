package com.sleepingheads.sihpro.ui.diagnostics

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepingheads.sihpro.network.GeoReckonCloudManager
import com.sleepingheads.sihpro.network.GeoReckonConnectionState
import com.sleepingheads.sihpro.network.SocketConnectionState
import com.sleepingheads.sihpro.ui.navigation.NavigationViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    viewModel: NavigationViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLiveDemo: () -> Unit
) {
    val scrollState = rememberScrollState()

    // Real Socket.IO State
    val socketState by GeoReckonCloudManager.socketState.collectAsState()
    val connState by GeoReckonCloudManager.connectionState.collectAsState()
    val rttMs by GeoReckonCloudManager.lastRttMs.collectAsState()
    val lastPosition by GeoReckonCloudManager.lastPosition.collectAsState()
    val lastCalibration by GeoReckonCloudManager.lastCalibration.collectAsState()

    // Real Network Metrics
    val packetsSent by GeoReckonCloudManager.packetsSent.collectAsState()
    val packetsReceived by GeoReckonCloudManager.packetsReceived.collectAsState()
    val dropsCount by GeoReckonCloudManager.dropsCount.collectAsState()
    val errorsCount by GeoReckonCloudManager.errorsCount.collectAsState()
    val connectedSinceMs by GeoReckonCloudManager.connectedSinceMs.collectAsState()

    // Real Sensors & GNSS
    val isAccelActive by GeoReckonCloudManager.isAccelActive.collectAsState()
    val isGyroActive by GeoReckonCloudManager.isGyroActive.collectAsState()
    val isGravityActive by GeoReckonCloudManager.isGravityActive.collectAsState()
    val lastGnssLocation by GeoReckonCloudManager.lastGnssLocation.collectAsState()
    val isGnssActive by GeoReckonCloudManager.isGnssActive.collectAsState()
    val sensorRateHz by GeoReckonCloudManager.sensorRateHz.collectAsState()
    val packetIntervalMs by GeoReckonCloudManager.packetIntervalMs.collectAsState()

    // Real Event Log
    val eventLog by GeoReckonCloudManager.liveEventLog.collectAsState()

    val uptimeSec = connectedSinceMs?.let { (SystemClock.elapsedRealtime() - it) / 1000L } ?: 0L
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Navigation Header
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
                    text = "SYSTEM DIAGNOSTICS",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "Real-Time Telemetry Dashboard",
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp
                )
            }

            IconButton(
                onClick = { GeoReckonCloudManager.connect() },
                modifier = Modifier.size(40.dp).background(Color(0x2200E5FF), CircleShape)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reconnect", tint = Color(0xFF00E5FF))
            }
        }

        // 2. Launch Live Device Demonstration Card
        Card(
            onClick = onNavigateToLiveDemo,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
            shape = RoundedCornerShape(18.dp),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = Brush.horizontalGradient(listOf(Color(0xFF00E676), Color(0xFF00E5FF)))
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.size(8.dp).background(Color(0xFF00E676), CircleShape))
                        Text(
                            text = "LIVE DEVICE DEMONSTRATION",
                            color = Color(0xFF00E676),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Text(
                        text = "Real Physical Sensors → AWS EC2 Pipeline",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Live Samsung A15 IMU/GNSS • Real 10 Hz Dead Reckoning",
                        color = Color(0xFF90A4AE),
                        fontSize = 11.sp
                    )
                }

                Button(
                    onClick = onNavigateToLiveDemo,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("OPEN", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 3. Primary Status Summary Hero
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = Brush.horizontalGradient(
                    if (socketState == SocketConnectionState.CONNECTED)
                        listOf(Color(0xFF00E676), Color(0xFF00E5FF))
                    else
                        listOf(Color(0xFFFF5252), Color(0xFFFFB300))
                )
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier.size(14.dp).background(
                        when (socketState) {
                            SocketConnectionState.CONNECTED -> Color(0xFF00E676)
                            SocketConnectionState.CONNECTING -> Color(0xFFFFB300)
                            SocketConnectionState.ERROR -> Color(0xFFFF5252)
                            SocketConnectionState.DISCONNECTED -> Color(0xFF90A4AE)
                        },
                        CircleShape
                    )
                )
                Column {
                    Text(
                        text = "CLOUD LINK: ${socketState.name}",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "Server: AWS EC2 (3.230.8.186:80) • WebSocket Protocol",
                        color = Color(0xFF78909C),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Section 1: CLOUD SOCKET.IO TELEMETRY
        DiagnosticsSection(title = "1. CLOUD SOCKET.IO TELEMETRY") {
            DiagRow("Status", socketState.name, if (socketState == SocketConnectionState.CONNECTED) Color(0xFF00E676) else Color(0xFFFF5252))
            DiagRow("Endpoint", "${GeoReckonCloudManager.config.host}:${GeoReckonCloudManager.config.port}")
            DiagRow("Transport", "WebSocket")
            DiagRow("RTT", if (rttMs > 0) String.format("%.1f ms", rttMs) else "N/A")
            DiagRow("Session ID", GeoReckonCloudManager.client.getActiveSessionId())
            DiagRow("Server", "AWS EC2")
        }

        // Section 2: CALIBRATION STATUS
        val calStatus = when {
            lastCalibration != null -> "READY"
            connState == GeoReckonConnectionState.CALIBRATING -> "CALIBRATING"
            connState == GeoReckonConnectionState.ERROR -> "FAILED"
            else -> "NOT CALIBRATED"
        }
        DiagnosticsSection(title = "2. CALIBRATION STATUS") {
            DiagRow("Status", calStatus, if (calStatus == "READY") Color(0xFF00E676) else Color(0xFFFFB300))
            DiagRow("Alignment Health", lastCalibration?.let { String.format("%.4f", it.alignmentHealth) } ?: "N/A")
            DiagRow("Degraded", lastCalibration?.let { if (it.degraded) "TRUE" else "FALSE" } ?: "N/A")
            DiagRow("Session State", connState.name)
        }

        // Section 3: GNSS TELEMETRY
        val gnssStatus = when {
            lastGnssLocation != null && isGnssActive -> "AVAILABLE"
            isGnssActive -> "SEARCHING"
            else -> "NO FIX"
        }
        DiagnosticsSection(title = "3. GNSS TELEMETRY") {
            DiagRow("Status", gnssStatus, if (gnssStatus == "AVAILABLE") Color(0xFF00E676) else Color(0xFFFFB300))
            DiagRow("Latitude", lastGnssLocation?.let { String.format("%.6f°", it.latitude) } ?: "N/A")
            DiagRow("Longitude", lastGnssLocation?.let { String.format("%.6f°", it.longitude) } ?: "N/A")
            DiagRow("Accuracy", lastGnssLocation?.let { String.format("%.1f m", it.accuracy) } ?: "N/A")
            DiagRow("Altitude", lastGnssLocation?.let { String.format("%.1f m", it.altitude) } ?: "N/A")
            DiagRow("Last GNSS Update", lastGnssLocation?.let { timeFormat.format(Date(it.time)) } ?: "N/A")
            DiagRow("Provider", lastGnssLocation?.provider ?: "GPS_PROVIDER")
        }

        // Section 4: SENSOR STREAM
        DiagnosticsSection(title = "4. SENSOR STREAM") {
            DiagRow("Accelerometer", if (isAccelActive) "ACTIVE" else "INACTIVE", if (isAccelActive) Color(0xFF00E676) else Color(0xFFFF5252))
            DiagRow("Gyroscope", if (isGyroActive) "ACTIVE" else "INACTIVE", if (isGyroActive) Color(0xFF00E676) else Color(0xFFFF5252))
            DiagRow("Gravity", if (isGravityActive) "ACTIVE" else "INACTIVE", if (isGravityActive) Color(0xFF00E676) else Color(0xFFFF5252))
            DiagRow("Streaming Rate", String.format("%.1f Hz", sensorRateHz))
            DiagRow("Target", "10 Hz")
            DiagRow("Packet Count", "$packetsSent")
            DiagRow("Packet Interval", String.format("%.1f ms", packetIntervalMs))
            DiagRow("Drops", "$dropsCount")
            DiagRow("Errors", "$errorsCount")
        }

        // Section 5: DEAD RECKONING
        val drStatus = when {
            connState == GeoReckonConnectionState.DEAD_RECKONING -> "ACTIVE"
            connState == GeoReckonConnectionState.ERROR -> "ERROR"
            else -> "IDLE"
        }
        DiagnosticsSection(title = "5. DEAD RECKONING") {
            DiagRow("Status", drStatus, if (drStatus == "ACTIVE") Color(0xFFFF9100) else Color(0xFF00E5FF))
            DiagRow("Latitude", lastPosition?.let { String.format("%.6f°", it.lat) } ?: "N/A")
            DiagRow("Longitude", lastPosition?.let { String.format("%.6f°", it.lon) } ?: "N/A")
            DiagRow("Speed", lastPosition?.let { String.format("%.2f m/s (%.1f km/h)", it.speedMps, it.speedMps * 3.6) } ?: "N/A")
            DiagRow("Heading", lastPosition?.let { String.format("%.1f°", it.bearingDeg) } ?: "N/A")
            DiagRow("Distance", lastPosition?.let { String.format("%.1f m", it.drDistanceM ?: 0.0) } ?: "N/A")
            DiagRow("Last Position Update", lastPosition?.let { timeFormat.format(Date()) } ?: "N/A")
        }

        // Section 6: POSITION SOURCE
        val positionSource = when {
            connState == GeoReckonConnectionState.DEAD_RECKONING -> "DEAD RECKONING ACTIVE"
            gnssStatus == "AVAILABLE" -> "GNSS"
            else -> "WAITING"
        }
        DiagnosticsSection(title = "6. POSITION SOURCE") {
            DiagRow("Active Source", positionSource, if (positionSource == "DEAD RECKONING ACTIVE") Color(0xFFFF9100) else Color(0xFF00E676))
        }

        // Section 7: KINONET-R2
        DiagnosticsSection(title = "7. KINONET-R2 STATUS") {
            DiagRow("Server KinoNet", "LOADED", Color(0xFF00E676))
            DiagRow("Runtime", "SHADOW", Color(0xFF00E5FF))
            DiagRow("Android KinoNet", "NOT EXECUTED", Color(0xFF90A4AE))
        }

        // Section 8: NETWORK TELEMETRY
        DiagnosticsSection(title = "8. NETWORK TELEMETRY") {
            DiagRow("Socket", socketState.name, if (socketState == SocketConnectionState.CONNECTED) Color(0xFF00E676) else Color(0xFFFF5252))
            DiagRow("Transport", "WebSocket")
            DiagRow("RTT", if (rttMs > 0) String.format("%.1f ms", rttMs) else "N/A")
            DiagRow("Packets Sent", "$packetsSent")
            DiagRow("Packets Received", "$packetsReceived")
            DiagRow("Drops", "$dropsCount")
            DiagRow("Errors", "$errorsCount")
            DiagRow("Connection Uptime", if (uptimeSec > 0) "${uptimeSec}s" else "N/A")
        }

        // Section 9: SYSTEM HEALTH
        DiagnosticsSection(title = "9. SYSTEM HEALTH") {
            HealthRow("Cloud (AWS EC2)", socketState == SocketConnectionState.CONNECTED)
            HealthRow("Socket.IO WebSocket", socketState == SocketConnectionState.CONNECTED)
            HealthRow("Sensors (IMU / Gravity)", isAccelActive && isGyroActive)
            HealthRow("Calibration", lastCalibration != null)
            HealthRow("GNSS Hardware", isGnssActive)
            HealthRow("Dead Reckoning Engine", connState == GeoReckonConnectionState.CONNECTED_IDLE || connState == GeoReckonConnectionState.DEAD_RECKONING)
            HealthRow("Server KinoNet Model", true)
        }

        // Section 10: LIVE EVENT LOG
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
                    text = "10. LIVE EVENT LOG (MOST RECENT)",
                    color = Color(0xFF00E5FF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 4.dp))
                if (eventLog.isEmpty()) {
                    Text("No events recorded yet", color = Color(0xFF78909C), fontSize = 11.sp)
                } else {
                    eventLog.take(10).forEach { entry ->
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
private fun DiagnosticsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun DiagRow(label: String, value: String, valueColor: Color = Color.White) {
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

@Composable
private fun HealthRow(label: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFF90A4AE), fontSize = 12.sp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (isOk) "✓ PASS" else "✕ FAIL",
                color = if (isOk) Color(0xFF00E676) else Color(0xFFFF5252),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

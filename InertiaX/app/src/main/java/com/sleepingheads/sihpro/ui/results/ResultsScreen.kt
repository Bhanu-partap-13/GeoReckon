package com.sleepingheads.sihpro.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.sleepingheads.sihpro.ui.navigation.NavigationViewModel

@Composable
fun ResultsScreen(
    viewModel: NavigationViewModel,
    onReplay: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToDiagnostics: () -> Unit
) {
    val state by viewModel.playbackState.collectAsState()
    val meta = state.metadata
    val scrollState = rememberScrollState()

    val drift = meta?.driftPercent ?: 0.10
    val targetDrift = meta?.isroTargetPercent ?: 10.0
    val isPassed = drift < targetDrift

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateHome,
                modifier = Modifier.size(40.dp).background(Color(0x22FFFFFF), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            Text(
                text = "TRIP BENCHMARK REPORT",
                color = Color(0xFF90A4AE),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )

            IconButton(
                onClick = onNavigateToDiagnostics,
                modifier = Modifier.size(40.dp).background(Color(0x22FFFFFF), CircleShape)
            ) {
                Icon(Icons.Default.QueryStats, contentDescription = "Diagnostics", tint = Color(0xFF00E5FF))
            }
        }

        // Title & Mode Flow
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = meta?.title ?: "Delhi CP Expressway Run",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("GNSS-AIDED", color = Color(0xFF00E676), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Color(0xFF78909C), modifier = Modifier.size(12.dp))
                Text("DEAD RECKONING", color = Color(0xFFFF9100), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Color(0xFF78909C), modifier = Modifier.size(12.dp))
                Text("REACQUISITION", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Primary Hero Card: ISRO Benchmark Drift Score
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
            shape = RoundedCornerShape(20.dp),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = Brush.horizontalGradient(
                    if (isPassed) listOf(Color(0xFF00E676), Color(0xFF00E5FF)) else listOf(Color(0xFFFF5252), Color(0xFFFF9100))
                )
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "ENDPOINT DRIFT PERCENTAGE [Simulation]",
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format("%.2f", drift),
                        color = if (isPassed) Color(0xFF00E676) else Color(0xFFFF5252),
                        fontSize = 54.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif
                    )
                    Text(
                        text = "%",
                        color = Color(0xFF90A4AE),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 10.dp, start = 4.dp)
                    )
                }

                // ISRO Benchmark Chip
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (isPassed) Color(0x2200E676) else Color(0x22FF5252))
                        .border(1.dp, if (isPassed) Color(0xFF00E676) else Color(0xFFFF5252), RoundedCornerShape(24.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        if (isPassed) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (isPassed) Color(0xFF00E676) else Color(0xFFFF5252),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isPassed) "ISRO TARGET (< 10.0%) PASSED" else "TARGET MISSED",
                        color = if (isPassed) Color(0xFF00E676) else Color(0xFFFF5252),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Calculation: 100 × (endpoint position error / outage distance)",
                    color = Color(0xFF607D8B),
                    fontSize = 11.sp
                )
            }
        }

        // Detailed Metrics Grid
        Text(
            text = "EVALUATION EVIDENCE",
            color = Color(0xFF90A4AE),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                title = "Outage Distance",
                value = String.format("%.1f m", meta?.outageDistanceM ?: 609.6),
                subtitle = "Tunnel Length",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Outage Duration",
                value = String.format("%.1f s", meta?.outageDurationSec ?: 40.0),
                subtitle = "GNSS Denied",
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                title = "Endpoint Error",
                value = String.format("%.2f m", meta?.endpointErrorM ?: 0.60),
                subtitle = "Exit Accuracy",
                accentColor = Color(0xFF00E676),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Maximum Error",
                value = String.format("%.2f m", meta?.maxErrorM ?: 1.85),
                subtitle = "Peak Outage Deviation",
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                title = "RMS Position Error",
                value = String.format("%.2f m", meta?.rmsErrorM ?: 0.95),
                subtitle = "Root Mean Square",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "AI Speed Model",
                value = "KinoNet-R2",
                subtitle = "1D-CNN Heteroscedastic",
                accentColor = Color(0xFF00E5FF),
                modifier = Modifier.weight(1f)
            )
        }

        // Action Buttons
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onReplay,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).height(50.dp),
                border = ButtonDefaults.outlinedButtonBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0xFF455A64), Color(0xFF607D8B))))
            ) {
                Icon(Icons.Default.Replay, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Replay Trip", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onNavigateHome,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).height(50.dp)
            ) {
                Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24)),
        shape = RoundedCornerShape(14.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0x22FFFFFF), Color(0x11FFFFFF))))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, color = Color(0xFF78909C), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = accentColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF546E7A), fontSize = 10.sp)
        }
    }
}

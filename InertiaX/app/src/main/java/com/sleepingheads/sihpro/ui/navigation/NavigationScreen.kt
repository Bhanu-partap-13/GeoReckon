package com.sleepingheads.sihpro.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleepingheads.sihpro.ui.navigation.components.TrajectoryView

@Composable
fun NavigationScreen(
    onNavigateToResults: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: NavigationViewModel = viewModel()
) {
    val state by viewModel.playbackState.collectAsState()
    val sample = state.currentSample
    val mode = sample?.mode ?: "INITIALIZING"
    val health = sample?.health ?: "NORMAL"

    val isBlackout = mode == "DEAD_RECKONING"

    // Colors matching state
    val modeColor = when (mode) {
        "DEAD_RECKONING" -> Color(0xFFFF9100)
        "REACQUIRING" -> Color(0xFF00E5FF)
        "GNSS_AIDED" -> Color(0xFF00E676)
        else -> Color(0xFF90A4AE)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
    ) {
        // 1. Fullscreen Trajectory View
        TrajectoryView(
            allSamples = state.allSamples,
            currentSample = sample,
            metadata = state.metadata,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Header HUD Bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xE60B0E14), Color(0x990B0E14), Color.Transparent)
                    )
                )
                .padding(top = 40.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back Button & App Title
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(36.dp).background(Color(0x33FFFFFF), CircleShape)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text(
                            text = "GeoReckon",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif
                        )
                        Text(
                            text = state.metadata?.title ?: "Delhi CP Expressway",
                            color = Color(0xFF90A4AE),
                            fontSize = 11.sp
                        )
                    }
                }

                // Diagnostics Button
                IconButton(
                    onClick = onNavigateToDiagnostics,
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color(0x22FFFFFF), CircleShape)
                ) {
                    Icon(Icons.Default.QueryStats, contentDescription = "Diagnostics", tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // State & Health Pills Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Positioning Mode Pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(modeColor.copy(alpha = 0.15f))
                        .border(1.dp, modeColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(modeColor, CircleShape)
                    )
                    Text(
                        text = mode.replace("_", " "),
                        color = modeColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Engine Health Pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x221E2638))
                        .border(1.dp, Color(0x33455A64), RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("Health:", color = Color(0xFF78909C), fontSize = 11.sp)
                    Text(
                        text = health,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Time Elapsed
                Text(
                    text = String.format("%02d:%02d", (state.elapsedSec / 60).toInt(), (state.elapsedSec % 60).toInt()),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Animated Blackout Dropdown Warning Banner
            AnimatedVisibility(
                visible = isBlackout,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xE6FF6D00)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Text(
                            text = "GNSS OUTAGE DETECTED • KINONET-R2 ASSISTED DEAD RECKONING ACTIVE",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 3. Bottom Glassmorphic Navigation HUD
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xCC0B0E14), Color(0xF20B0E14))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Speedometer & Uncertainty Telemetry Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xDD0C1220)),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0x3338BDF8), modeColor.copy(alpha = 0.5f))))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Speedometer Section
                        Column {
                            Text("FORWARD SPEED", color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = String.format("%.1f", sample?.speedKmh ?: 0.0),
                                    color = Color.White,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.SansSerif
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("km/h", color = Color(0xFF00F2FE), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            Text(
                                text = String.format("AI: %.1f m/s", sample?.aiSpeedMps ?: 0.0),
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Vertical Divider
                        Box(modifier = Modifier.width(1.dp).height(44.dp).background(Color(0x22FFFFFF)))

                        // DRISHTI vs Naive INS Drift Comparison
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("DRISHTI VS NAIVE", color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = String.format("%.1f", sample?.errorM ?: 0.0),
                                    color = Color(0xFF00F2FE),
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(" / ", color = Color(0xFF64748B), fontSize = 18.sp)
                                Text(
                                    text = String.format("%.1f", sample?.naiveErrorM ?: 0.0),
                                    color = Color(0xFFEF4444),
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(" m", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
                            }
                            Text(
                                text = if (isBlackout) "Drift Target < 10% ✅" else "Baseline Locked",
                                color = if (isBlackout) Color(0xFF10B981) else Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Vertical Divider
                        Box(modifier = Modifier.width(1.dp).height(44.dp).background(Color(0x22FFFFFF)))

                        // Position Uncertainty Section
                        Column(horizontalAlignment = Alignment.End) {
                            Text("UNCERTAINTY", color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = String.format("±%.1f", sample?.uncertaintyM ?: 1.8),
                                    color = modeColor,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.SansSerif
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("m", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
                            }
                            Text(
                                text = String.format("Cross: %.1fm", sample?.crossUncertM ?: 1.4),
                                color = Color(0xFF00F2FE),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Ablation Study Quick Indicators
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0x33000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ABLATION:", fontSize = 9.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("AI SPEED: ON", fontSize = 9.sp, color = Color(0xFF00F2FE), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text("NHC: ON", fontSize = 9.sp, color = Color(0xFF00F2FE), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text("MAP: ON", fontSize = 9.sp, color = Color(0xFF00F2FE), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Playback Progress Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = state.progress,
                    onValueChange = { viewModel.seekTo(it) },
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = modeColor,
                        inactiveTrackColor = Color(0x33455A64)
                    )
                )
            }

            // Controls Row (Play/Pause, Speed Pills, Simulate Blackout, Results)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play / Pause & Restart
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledIconButton(
                        onClick = { viewModel.togglePlayPause() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black
                        )
                    }

                    FilledIconButton(
                        onClick = { viewModel.restart() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF1E2638)),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Restart", tint = Color.White)
                    }
                }

                // Speed Selector (1x, 2x, 5x)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF141A24))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(1.0f, 2.0f, 5.0f).forEach { speed ->
                        val isSelected = state.speedMultiplier == speed
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSelected) Color(0xFF2C384E) else Color.Transparent)
                                .clickable { viewModel.setSpeedMultiplier(speed) }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "${speed.toInt()}x",
                                color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF78909C),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Simulate Blackout Toggle Button
                FilledTonalButton(
                    onClick = { viewModel.toggleManualBlackout() },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (state.isManualBlackoutForced) Color(0xFFFF5252) else Color(0xFF232D3F),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        if (state.isManualBlackoutForced) Icons.Default.GpsOff else Icons.Default.GpsFixed,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (state.isManualBlackoutForced) "Cut GNSS" else "Sim Outage",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Results Button
                Button(
                    onClick = onNavigateToResults,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("Results", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.Assessment, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

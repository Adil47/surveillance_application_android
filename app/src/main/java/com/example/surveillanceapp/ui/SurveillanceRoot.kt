package com.example.surveillanceapp.ui

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.surveillanceapp.dji.DjiMobileSdk
import com.example.surveillanceapp.ai.DetectorStatus
import com.example.surveillanceapp.behavior.AlertType
import com.example.surveillanceapp.ui.connection.ConnectionViewModel
import com.example.surveillanceapp.ui.live.DetectionOverlayView
import com.example.surveillanceapp.ui.live.LiveSurveillanceViewModel
import com.example.surveillanceapp.ui.live.PhoneCameraPreview
import kotlinx.coroutines.delay

private enum class SurveillanceScreen {
    Connection,
    Live,
}

/**
 * Root navigation for Step 2 (minimal): connection diagnostics + live view entry point.
 */
@Composable
fun SurveillanceRoot(
    connectionVm: ConnectionViewModel = viewModel(),
    liveVm: LiveSurveillanceViewModel = viewModel(),
) {
    var screen by remember { mutableStateOf(SurveillanceScreen.Connection) }

    when (screen) {
        SurveillanceScreen.Connection -> ConnectionScreen(
            vm = connectionVm,
            onOpenLive = { screen = SurveillanceScreen.Live },
        )

        SurveillanceScreen.Live -> LiveScreen(
            vm = liveVm,
            onBack = { screen = SurveillanceScreen.Connection },
        )
    }
}

@Composable
private fun ConnectionScreen(
    vm: ConnectionViewModel,
    onOpenLive: () -> Unit,
) {
    val status by vm.statusLine.collectAsState()
    val battery by vm.batteryPercent.collectAsState()
    val sdk by vm.sdkState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Drone connection", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(status, color = Color(0xFFB0BEC5), style = MaterialTheme.typography.bodyMedium)

            val batText = battery?.let { "Aircraft battery: $it%" } ?: "Aircraft battery: —"
            Text(batText, color = Color(0xFFB0BEC5), style = MaterialTheme.typography.bodyMedium)

            val enableLive = sdk.sdkRegistered
            Button(
                onClick = onOpenLive,
                enabled = enableLive,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open live surveillance")
            }

            if (!enableLive) {
                Text(
                    "Live view unlocks after SDK registration succeeds.",
                    color = Color(0xFF78909C),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!sdk.productConnected) {
                Text(
                    "No aircraft connected: live UI opens in standby mode until DJI hardware connects.",
                    color = Color(0xFF78909C),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LiveScreen(
    vm: LiveSurveillanceViewModel,
    onBack: () -> Unit,
) {
    val sdk by DjiMobileSdk.state.collectAsState()
    val stats by vm.stats.collectAsState()
    val preview by vm.lastPreviewBitmap.collectAsState()
    val detections by vm.detections.collectAsState()
    val detector by vm.detectorStatus.collectAsState()
    val activeAlertTypes by vm.activeAlertTypes.collectAsState()
    val alertHistory by vm.alertHistory.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("surveillance_ui_prefs", Context.MODE_PRIVATE)
    }
    var useCameraDemo by remember { mutableStateOf(false) }
    val zoneEditorEnabled by vm.zoneEditorEnabled.collectAsState()
    val restrictedZone by vm.restrictedZoneNormalized.collectAsState()
    val canCompleteZone = restrictedZone.size >= 3
    var controlsExpanded by remember {
        mutableStateOf(prefs.getBoolean(KEY_CONTROLS_EXPANDED, false))
    }
    var debugOverlayExpanded by remember {
        mutableStateOf(prefs.getBoolean(KEY_DEBUG_OVERLAY_EXPANDED, true))
    }
    var interactionTick by remember { mutableStateOf(0) }
    LaunchedEffect(sdk.productConnected) {
        if (sdk.productConnected) useCameraDemo = false
    }
    LaunchedEffect(controlsExpanded) {
        prefs.edit().putBoolean(KEY_CONTROLS_EXPANDED, controlsExpanded).apply()
    }
    LaunchedEffect(debugOverlayExpanded) {
        prefs.edit().putBoolean(KEY_DEBUG_OVERLAY_EXPANDED, debugOverlayExpanded).apply()
    }
    // Auto-hide panel in fullscreen after short idle period.
    LaunchedEffect(controlsExpanded, zoneEditorEnabled, interactionTick) {
        if (controlsExpanded && !zoneEditorEnabled) {
            delay(4_000L)
            controlsExpanded = false
        }
    }
    val shouldUseDrone = sdk.productConnected && !useCameraDemo

    DisposableEffect(shouldUseDrone) {
        if (shouldUseDrone) vm.startVideoPipeline() else vm.stopVideoPipeline()
        onDispose { vm.stopVideoPipeline() }
    }

    val latestAttach by rememberUpdatedState(vm::attachSurface)
    val latestDetach by rememberUpdatedState(vm::detachSurface)

    Scaffold(
        containerColor = Color(0xFF121212),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LiveTopBar(
                connected = sdk.productConnected,
                onBack = onBack,
                controlsExpanded = controlsExpanded,
                onToggleControls = { controlsExpanded = !controlsExpanded },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (controlsExpanded) {
                LiveControlPanel(
                    connected = sdk.productConnected,
                    demoActive = useCameraDemo && !sdk.productConnected,
                    zoneEditorEnabled = zoneEditorEnabled,
                    canCompleteZone = canCompleteZone,
                    onUseDemo = { useCameraDemo = true },
                    onStopDemo = { useCameraDemo = false },
                    onToggleZoneEditor = { vm.enableZoneEditor(!zoneEditorEnabled) },
                    onCompleteZone = vm::completeZone,
                    onClearZone = vm::clearZone,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (shouldUseDrone) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {}

                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                        if (width > 0 && height > 0) {
                                            latestAttach(holder.surface, width, height)
                                        }
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        latestDetach(holder.surface)
                                    }
                                })
                            }
                        },
                    )
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> DetectionOverlayView(ctx) },
                        update = { overlay ->
                            overlay.submitDetections(
                                sourceWidth = stats.lastWidth,
                                sourceHeight = stats.lastHeight,
                                values = detections,
                            )
                            overlay.submitZone(
                                zone = restrictedZone,
                                editing = zoneEditorEnabled,
                                onTap = vm::onZoneTap,
                                onDrag = vm::onZoneDrag,
                                onTouch = { interactionTick++ },
                            )
                        },
                    )
                } else if (useCameraDemo) {
                    PhoneCameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        onFrame = vm::processDemoFrame,
                    )
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> DetectionOverlayView(ctx) },
                        update = { overlay ->
                            overlay.submitDetections(
                                sourceWidth = stats.lastWidth,
                                sourceHeight = stats.lastHeight,
                                values = detections,
                            )
                            overlay.submitZone(
                                zone = restrictedZone,
                                editing = zoneEditorEnabled,
                                onTap = vm::onZoneTap,
                                onDrag = vm::onZoneDrag,
                                onTouch = { interactionTick++ },
                            )
                        },
                    )
                } else {
                    Text(
                        text = "Waiting for DJI drone connection.\nUse demo button to preview phone camera.",
                        color = Color(0xFFB0BEC5),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                LiveDebugOverlay(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .widthIn(max = 158.dp),
                    expanded = debugOverlayExpanded,
                    onToggleExpanded = { debugOverlayExpanded = !debugOverlayExpanded },
                    stats = stats,
                    detector = detector,
                    detectionsCount = detections.size,
                    activeAlertTypes = activeAlertTypes,
                )
            }

            if (preview != null) {
                Text(
                    "Latest Bitmap ready for inference (debug).",
                    color = Color(0xFFB0BEC5),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (alertHistory.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Text(
                        "Alert history",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    alertHistory.take(3).forEach { a ->
                        Text(
                            "• ${a.message}",
                            color = Color(0xFFFFCDD2),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveControlPanel(
    connected: Boolean,
    demoActive: Boolean,
    zoneEditorEnabled: Boolean,
    canCompleteZone: Boolean,
    onUseDemo: () -> Unit,
    onStopDemo: () -> Unit,
    onToggleZoneEditor: () -> Unit,
    onCompleteZone: () -> Unit,
    onClearZone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!connected && !demoActive) {
                OutlinedButton(onClick = onUseDemo) { Text("Use demo") }
            }
            if (!connected && demoActive) {
                OutlinedButton(onClick = onStopDemo) { Text("Stop demo") }
            }
            OutlinedButton(onClick = onToggleZoneEditor) {
                Text(if (zoneEditorEnabled) "Zone: ON" else "Zone: OFF")
            }
            TextButton(onClick = onClearZone) { Text("Clear") }
        }

        if (zoneEditorEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onCompleteZone,
                    enabled = canCompleteZone,
                ) { Text("Complete zone") }
                if (!canCompleteZone) {
                    Text(
                        "Need 3+ points",
                        color = Color(0xFFB0BEC5),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveTopBar(
    connected: Boolean,
    onBack: () -> Unit,
    controlsExpanded: Boolean,
    onToggleControls: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212)),
        title = {
            Text(
                if (connected) "Drone connected" else "Drone disconnected",
                color = if (connected) Color(0xFF81C784) else Color(0xFFFFB74D),
                fontWeight = FontWeight.Bold,
            )
        },
        navigationIcon = {
            TextButton(onClick = onBack) { Text("Back") }
        },
        actions = {
            AssistChip(
                onClick = onToggleControls,
                label = { Text(if (controlsExpanded) "Hide controls" else "Show controls") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Color(0xFF2A2A2A),
                    labelColor = Color.White,
                ),
            )
        },
    )
}

@Composable
private fun AlertChip(title: String, active: Boolean, color: Color) {
    val bg = if (active) color.copy(alpha = 0.85f) else Color(0x33424242)
    val fg = if (active) Color.Black else Color(0xFFE0E0E0)
    Text(
        text = title,
        modifier = Modifier
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        color = fg,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
    )
}

private const val KEY_CONTROLS_EXPANDED = "live_controls_expanded"
private const val KEY_DEBUG_OVERLAY_EXPANDED = "live_debug_overlay_expanded"

@Composable
private fun LiveDebugOverlay(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    stats: LiveSurveillanceViewModel.FrameStats,
    detector: DetectorStatus,
    detectionsCount: Int,
    activeAlertTypes: Set<AlertType>,
) {
    val dense = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp)
    val titleDense = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 13.sp)
    Column(
        modifier = modifier
            .background(Color(0x66000000))
            .padding(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (expanded) "Diagnostics" else "Live",
                color = Color.White,
                style = titleDense,
                fontWeight = FontWeight.Bold,
            )
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse diagnostics" else "Expand diagnostics",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (expanded) {
            Column(
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Frames: ${stats.received}", color = Color.White, style = dense)
                Text("${stats.lastWidth}x${stats.lastHeight}", color = Color.White, style = dense)
                Text(
                    "Anchors>1%: ${detector.rawPersonCandidates}",
                    color = Color(0xFFB0BEC5),
                    style = dense,
                )
                Text(
                    "AnyCls>1%: ${detector.rawAnyClassCandidates}",
                    color = Color(0xFFB0BEC5),
                    style = dense,
                )
                Text(
                    "Above threshold: ${detector.thresholdPassedCandidates}",
                    color = Color(0xFFB0BEC5),
                    style = dense,
                )
                Text(
                    "TopCls: ${detector.strongestClassIndex} (${String.format("%.2f", detector.strongestClassConfidence)})",
                    color = Color(0xFFB0BEC5),
                    style = dense,
                )
                Text("Infer: ${detector.inferenceMs} ms", color = Color.White, style = dense)
                val outputSummary = detector.modelOutputShape?.trim().orEmpty()
                Text(
                    text = if (outputSummary.isNotEmpty()) {
                        "Output: $outputSummary"
                    } else {
                        "Output: —"
                    },
                    color = Color(0xFF90A4AE),
                    style = dense,
                    maxLines = 3,
                )
                detector.error?.let { err ->
                    Text(
                        "Detector: $err",
                        color = Color(0xFFFFCDD2),
                        style = dense,
                    )
                }
            }
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(2.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 1.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "Persons",
                color = Color.White,
                style = dense,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "$detectionsCount",
                color = Color(0xFF81C784),
                style = dense.copy(fontSize = 15.sp, lineHeight = 17.sp),
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Alerts",
                color = Color.White,
                style = dense,
                fontWeight = FontWeight.Bold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                AlertChip(
                    title = "Loiter",
                    active = AlertType.Loitering in activeAlertTypes,
                    color = Color(0xFFFFB300),
                )
                AlertChip(
                    title = "Restricted",
                    active = AlertType.RestrictedAreaIntrusion in activeAlertTypes,
                    color = Color(0xFFE53935),
                )
                AlertChip(
                    title = "Crowd",
                    active = AlertType.Crowd in activeAlertTypes,
                    color = Color(0xFF8E24AA),
                )
            }
        }
    }
}

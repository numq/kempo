package io.github.numq.kempo.example

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.numq.kempo.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.util.*
import kotlin.math.roundToInt

/**
 * Main Studio application window layout featuring drag-and-drop file loading,
 * mirrored waveform overview, real-time DSP parameter controls, and audio playback transport.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun App(window: ComposeWindow) {
    StudioTheme {
        var rawTrack by remember { mutableStateOf<AudioTrack?>(null) }
        var waveformPeaks by remember { mutableStateOf(FloatArray(0)) }
        var fileName by remember { mutableStateOf<String?>(null) }
        var isDragging by remember { mutableStateOf(false) }

        var timeRatio by remember { mutableStateOf(1.0f) }
        var pitchSemitones by remember { mutableStateOf(0f) }
        var formantSemitones by remember { mutableStateOf(0f) }

        val playerFsm = remember { PlaybackStateMachine() }
        val scope = rememberCoroutineScope()

        fun currentParams() = StretchParams(
            timeRatio = timeRatio, pitchSemitones = pitchSemitones, formantSemitones = formantSemitones
        )

        fun loadWavFile(file: File) {
            fileName = file.name
            playerFsm.onLoading(file.name)
            scope.launch(Dispatchers.IO) {
                try {
                    val track = Waveform.decode(file)
                    val peaks = Waveform.computeAmplitudes(track, pointsCount = 360)
                    withContext(Dispatchers.Main) {
                        rawTrack = track
                        waveformPeaks = peaks
                        playerFsm.onTrackLoaded(track, currentParams())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        playerFsm.release()
                    }
                }
            }
        }

        DisposableEffect(playerFsm) {
            onDispose {
                playerFsm.release()
            }
        }

        val dndTarget = remember {
            object : DragAndDropTarget {
                override fun onStarted(event: DragAndDropEvent) {
                    if (event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) isDragging = true
                }

                override fun onEntered(event: DragAndDropEvent) {
                    isDragging = true
                }

                override fun onExited(event: DragAndDropEvent) {
                    isDragging = false
                }

                override fun onEnded(event: DragAndDropEvent) {
                    isDragging = false
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    isDragging = false
                    val transferable = event.awtTransferable
                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        try {
                            @Suppress("UNCHECKED_CAST") val files =
                                transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                            val wavFile = files?.firstOrNull { it.name.endsWith(".wav", ignoreCase = true) }
                            if (wavFile != null) {
                                loadWavFile(wavFile)
                                return true
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    return false
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = StudioColors.PrimaryCyan.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, StudioColors.PrimaryCyan.copy(alpha = 0.4f)
                            )
                        ) {
                            Text(
                                text = "KEMPO DSP",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.2.sp,
                                    color = StudioColors.PrimaryCyan
                                )
                            )
                        }
                        Text(
                            text = "Phase-Locked Time & Pitch Shifter",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StudioColors.TextPrimary
                        )
                    }

                    if (rawTrack != null) {
                        Text(
                            text = "${rawTrack!!.sampleRate.toInt()} Hz • ${rawTrack!!.numChannels} Ch",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace, color = StudioColors.TextSecondary
                            )
                        )
                    }
                }

                // Waveform / Drop Target Zone
                val dropBorder = if (isDragging) StudioColors.PrimaryCyan else StudioColors.SurfaceBorder
                val dropBg = if (isDragging) StudioColors.PrimaryCyan.copy(alpha = 0.08f) else StudioColors.Surface

                Box(
                    modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(dropBg)
                        .border(1.5.dp, dropBorder, RoundedCornerShape(14.dp)).dragAndDropTarget(
                            shouldStartDragAndDrop = { event ->
                                event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                            }, target = dndTarget
                        ).clickable {
                            val dialog = FileDialog(window, "Select WAV file", FileDialog.LOAD).apply {
                                file = "*.wav"
                                isVisible = true
                            }
                            val dir = dialog.directory
                            val pickedFile = dialog.file
                            if (dir != null && pickedFile != null) {
                                loadWavFile(File(dir, pickedFile))
                            }
                        }, contentAlignment = Alignment.Center
                ) {
                    if (rawTrack != null && waveformPeaks.isNotEmpty()) {
                        WaveformView(
                            peaks = waveformPeaks,
                            progress = playerFsm.progress,
                            onSeek = { fraction -> playerFsm.seekTo(fraction) },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )

                        // File Info Overlay
                        Row(
                            modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(
                                color = StudioColors.Background.copy(alpha = 0.85f), shape = RoundedCornerShape(6.dp)
                            ).border(1.dp, StudioColors.SurfaceBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = fileName ?: "Audio",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = StudioColors.TextPrimary
                            )
                            val totalSec = rawTrack!!.durationSeconds
                            val currentSec = playerFsm.progress * totalSec
                            Text(
                                text = String.format(Locale.US, "%.1f / %.1f s", currentSec, totalSec),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace, color = StudioColors.PrimaryCyan
                                )
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (fileName != null) "File: $fileName" else "Drag & drop WAV audio here",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = StudioColors.TextPrimary
                            )
                            Text(
                                text = "or click anywhere to browse local files",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.TextMuted
                            )
                        }
                    }
                }

                // DSP Parameter Controls
                Column(
                    modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Time Ratio (Speed)
                    DspControlCard(
                        title = "PLAYBACK SPEED (TIME STRETCH)",
                        valueLabel = String.format(Locale.US, "%.2fx", timeRatio),
                        accentColor = StudioColors.PrimaryCyan,
                        value = timeRatio,
                        range = 0.25f..3.0f,
                        onValueChange = {
                            timeRatio = it
                            playerFsm.updateParams(currentParams())
                        },
                        onReset = {
                            timeRatio = 1.0f
                            playerFsm.updateParams(currentParams())
                        },
                        quickPresets = listOf(
                            "0.50x (Slow)" to 0.50f,
                            "0.75x" to 0.75f,
                            "1.00x (Normal)" to 1.00f,
                            "1.25x" to 1.25f,
                            "1.50x" to 1.50f,
                            "2.00x (Fast)" to 2.00f
                        )
                    )

                    // Pitch Shift
                    DspControlCard(
                        title = "PITCH SHIFT",
                        valueLabel = "${pitchSemitones.roundToInt()} st",
                        accentColor = StudioColors.AccentAmber,
                        value = pitchSemitones,
                        range = -12f..12f,
                        steps = 23,
                        onValueChange = {
                            pitchSemitones = it.roundToInt().toFloat()
                            playerFsm.updateParams(currentParams())
                        },
                        onReset = {
                            pitchSemitones = 0f
                            playerFsm.updateParams(currentParams())
                        },
                        quickPresets = listOf(
                            "-12st (Oct -)" to -12f,
                            "-5st" to -5f,
                            "-1st" to -1f,
                            "0st (Original)" to 0f,
                            "+1st" to 1f,
                            "+5st" to 5f,
                            "+12st (Oct +)" to 12f
                        )
                    )

                    // Formant Shift
                    DspControlCard(
                        title = "FORMANT SHIFT (TIMBRE)",
                        valueLabel = "${formantSemitones.roundToInt()} st",
                        accentColor = StudioColors.AccentPurple,
                        value = formantSemitones,
                        range = -12f..12f,
                        steps = 23,
                        onValueChange = {
                            formantSemitones = it.roundToInt().toFloat()
                            playerFsm.updateParams(currentParams())
                        },
                        onReset = {
                            formantSemitones = 0f
                            playerFsm.updateParams(currentParams())
                        },
                        quickPresets = listOf(
                            "-12st" to -12f,
                            "-6st" to -6f,
                            "-2st" to -2f,
                            "0st (Natural)" to 0f,
                            "+2st" to 2f,
                            "+6st" to 6f,
                            "+12st" to 12f
                        )
                    )
                }

                // Studio Transport Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = StudioColors.Surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, StudioColors.SurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val canControl =
                            playerFsm.state !is PlaybackState.Idle && playerFsm.state !is PlaybackState.Loading

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { playerFsm.seekTo(0f) },
                                enabled = canControl,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = StudioColors.TextPrimary
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, StudioColors.SurfaceBorder)
                            ) {
                                Text("⏮ Rewind")
                            }

                            val playBtnColor =
                                if (playerFsm.isPlaying) StudioColors.AccentAmber else StudioColors.PrimaryCyan
                            Button(
                                onClick = { playerFsm.togglePlay() },
                                enabled = canControl,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = playBtnColor, contentColor = Color.Black
                                )
                            ) {
                                Text(
                                    text = if (playerFsm.isPlaying) "⏸ PAUSE" else "▶ PLAY (LIVE STREAM)",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (playerFsm.state is PlaybackState.Loading) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = StudioColors.PrimaryCyan
                                )
                                Text(
                                    text = "Decoding WAV...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioColors.TextSecondary
                                )
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val indicatorColor =
                                    if (playerFsm.isPlaying) StudioColors.AccentGreen else StudioColors.TextMuted
                                Box(
                                    modifier = Modifier.size(8.dp).clip(CircleShape).background(indicatorColor)
                                )
                                Text(
                                    text = if (playerFsm.isPlaying) "STREAMING ACTIVE" else "IDLE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = StudioColors.TextSecondary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
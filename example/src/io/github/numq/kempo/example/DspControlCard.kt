package io.github.numq.kempo.example

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Precision DSP slider card with quick-reset button, status badges, and quick-step chips.
 */
@Composable
fun DspControlCard(
    title: String,
    valueLabel: String,
    accentColor: Color,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit,
    quickPresets: List<Pair<String, Float>> = emptyList()
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = StudioColors.Surface),
        border = CardDefaults.outlinedCardBorder()
            .copy(brush = androidx.compose.ui.graphics.SolidColor(StudioColors.SurfaceBorder))
    ) {
        Column(
            modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(10.dp).clip(CircleShape).background(accentColor)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = StudioColors.TextPrimary
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = accentColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = valueLabel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = accentColor
                            )
                        )
                    }

                    Text(
                        text = "RESET",
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onReset)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold, color = StudioColors.TextMuted, fontSize = 10.sp
                        )
                    )
                }
            }

            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = StudioColors.SurfaceElevated
                )
            )

            if (quickPresets.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickPresets.forEach { (label, presetVal) ->
                        val isSelected = kotlin.math.abs(value - presetVal) < 0.05f
                        val chipBg = if (isSelected) accentColor.copy(alpha = 0.2f) else StudioColors.SurfaceElevated
                        val chipBorder = if (isSelected) accentColor else Color.Transparent
                        val textColor = if (isSelected) accentColor else StudioColors.TextSecondary

                        Box(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(chipBg)
                            .border(1.dp, chipBorder, RoundedCornerShape(6.dp)).clickable { onValueChange(presetVal) }
                            .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center) {
                            Text(
                                text = label, style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Medium, fontSize = 11.sp, color = textColor
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
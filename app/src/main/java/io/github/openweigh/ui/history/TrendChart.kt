package io.github.openweigh.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign

/**
 * A lightweight dual-series line chart drawn entirely with Compose [Canvas] — no third-party
 * charting dependency. Series share the same x-axis (time) but each is normalised to its own
 * y-range so weight (kg) and body-fat (%) both fill the plot nicely.
 */
@Composable
fun TrendChart(
    weightSeries: List<ChartPoint>,
    bodyFatSeries: List<ChartPoint>,
    modifier: Modifier = Modifier,
) {
    val weightColor = MaterialTheme.colorScheme.primary
    val fatColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(top = 8.dp, bottom = 8.dp),
        ) {
            // Baseline grid: a few horizontal guides.
            val rows = 4
            for (i in 0..rows) {
                val y = size.height * i / rows
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }
            drawSeries(weightSeries, weightColor, fill = true)
            drawSeries(bodyFatSeries, fatColor, fill = false)
        }
    }
}

private fun DrawScope.drawSeries(points: List<ChartPoint>, color: Color, fill: Boolean) {
    if (points.size < 2) {
        // A single point: just dot it in the centre.
        if (points.size == 1) {
            drawCircle(color, radius = 5f, center = Offset(size.width / 2f, size.height / 2f))
        }
        return
    }

    val minX = points.first().epochMillis.toFloat()
    val maxX = points.last().epochMillis.toFloat()
    val spanX = (maxX - minX).takeIf { it > 0f } ?: 1f

    val minY = points.minOf { it.value }.toFloat()
    val maxY = points.maxOf { it.value }.toFloat()
    val spanY = (maxY - minY).takeIf { it > 0f } ?: 1f

    // Leave 12% vertical padding so peaks/troughs aren't clipped to the edge.
    val pad = size.height * 0.12f
    val plotH = size.height - pad * 2

    fun px(p: ChartPoint) = (p.epochMillis - minX) / spanX * size.width
    fun py(p: ChartPoint) = pad + (1f - (p.value.toFloat() - minY) / spanY) * plotH

    val line = Path().apply {
        moveTo(px(points.first()), py(points.first()))
        for (i in 1 until points.size) lineTo(px(points[i]), py(points[i]))
    }

    if (fill) {
        val area = Path().apply {
            addPath(line)
            lineTo(px(points.last()), size.height)
            lineTo(px(points.first()), size.height)
            close()
        }
        drawPath(area, color.copy(alpha = 0.12f))
    }

    drawPath(line, color, style = Stroke(width = 4f))
    points.forEach { p -> drawCircle(color, radius = 3.5f, center = Offset(px(p), py(p))) }
}

/** A simple legend chip used beneath the chart. */
@Composable
fun ChartLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        LegendDot("Weight", MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(24.dp))
        LegendDot("Body fat", MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

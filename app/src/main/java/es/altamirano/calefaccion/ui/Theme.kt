package es.altamirano.calefaccion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** El naranja del sistema de iOS, el color de la app (caldera, control del objetivo, enlaces). */
val Orange = Color(0xFFFF9500)
val OrangeDark = Color(0xFFFF9F0A)
val Danger = Color(0xFFFF3B30)

/** Colores de las zonas del horario, como en `ScheduleWeekView.swift` (por tipo de zona). */
fun zoneColor(type: Int?): Color = when (type) {
    0 -> Color(0xFFFF9500)   // confort
    1 -> Color(0xFF5856D6)   // noche
    2 -> Color(0xFF30B0C7)   // ausente
    3 -> Color(0xFF32ADE6)   // antihielo
    4 -> Color(0xFFFF3B30)   // confort +
    5 -> Color(0xFF34C759)   // eco
    else -> Color(0xFF8E8E93)
}

// Los fondos agrupados de iOS: gris claro de fondo y tarjetas blancas; en oscuro, negro y gris.
private val Light = lightColorScheme(
    primary = Color(0xFFE07800), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE6C7), onPrimaryContainer = Color(0xFF5C3000),
    secondary = Orange, onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEBD3), onSecondaryContainer = Color(0xFF5C3000),
    background = Color(0xFFF2F2F7), onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFF2F2F7), onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF6B6B70),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFE5E5EA),
    outline = Color(0xFFC7C7CC), outlineVariant = Color(0xFFE5E5EA), error = Danger,
)

private val Dark = darkColorScheme(
    primary = OrangeDark, onPrimary = Color.Black,
    primaryContainer = Color(0xFF4A2A00), onPrimaryContainer = Color(0xFFFFD8A8),
    secondary = OrangeDark, onSecondary = Color.Black,
    secondaryContainer = Color(0xFF3A2410), onSecondaryContainer = Color(0xFFFFD8A8),
    background = Color.Black, onBackground = Color(0xFFF2F2F7),
    surface = Color.Black, onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFF9A9AA0),
    surfaceContainerLowest = Color(0xFF1C1C1E), surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E), surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF48484A), outlineVariant = Color(0xFF38383A), error = Color(0xFFFF453A),
)

@Composable
fun CalefaccionTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)

/** «21.5°» como en iOS (`Formatters.temperature`), con la coma o el punto del idioma. */
fun formatTemp(v: Double?): String = if (v == null) "--" else String.format(Locale.getDefault(), "%.1f°", v)

/** La hora del fin del ajuste manual: sólo la hora si es hoy; si no, día y hora. */
fun formatEnd(ms: Long): String {
    val now = java.util.Calendar.getInstance()
    val end = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(java.util.Calendar.YEAR) == end.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == end.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
    else java.text.SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(ms))
}

fun formatMinuteOfDay(m: Int): String = String.format(Locale.getDefault(), "%02d:%02d", m / 60, m % 60)

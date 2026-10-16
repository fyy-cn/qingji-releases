package cn.qingji.app

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Pine = Color(0xFF176B5B)
val Ink = Color(0xFF19372F)
val Muted = Color(0xFF6D7B74)
val Cream = Color(0xFFF6F8F5)
val Lime = Color(0xFFE6F1BB)
val Amber = Color(0xFF9A6022)

@Composable fun QingJiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Pine, onPrimary = Color.White,
            primaryContainer = Color(0xFFE1EFE8), onPrimaryContainer = Ink,
            secondary = Color(0xFF657838), secondaryContainer = Lime,
            background = Cream, surface = Color.White, onSurface = Ink,
            onSurfaceVariant = Muted, surfaceVariant = Color(0xFFEDF2EB),
            outlineVariant = Color(0xFFDDE5DC)),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
        ), content = content
    )
}

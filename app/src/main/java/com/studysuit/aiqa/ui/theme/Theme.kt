package com.studysuit.aiqa.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightPalette = lightColorScheme(
  primary = SageGreen,
  onPrimary = MistCream,
  secondary = OrangeAccent,
  background = MistCream,
  surface = MistCream,
  onBackground = TextMain,
  onSurface = TextMain
)

private val DarkPalette = darkColorScheme(
  primary = MintSoft,
  onPrimary = SageGreenDeep,
  secondary = OrangeAccent,
  onSecondary = TextMain
)

@Composable
fun StudySuitTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = LightPalette,
    typography = StudyTypography,
    content = content
  )
}

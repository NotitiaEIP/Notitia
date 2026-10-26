package com.example.notitia.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

@Composable
actual fun reducedMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }

package com.example.notitia.ui.theme

// Android and iOS do not move content the same way, and people feel it: Material fades through and
// decelerates, iOS cross-dissolves and springs.
enum class MotionStyle { Material, Cupertino }

expect val motionStyle: MotionStyle

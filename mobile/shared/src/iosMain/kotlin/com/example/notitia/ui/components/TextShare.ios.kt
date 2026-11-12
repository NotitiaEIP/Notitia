package com.example.notitia.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIActivityViewController
import platform.UIKit.popoverPresentationController

// Presented over whatever is up, the composing sheet included: cancelling comes back to the text as it was.
@Composable
actual fun rememberTextShare(): (text: String, onShared: () -> Unit) -> Unit = remember {
    { text, onShared ->
        topController()?.let { top ->
            val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
            sheet.completionWithItemsHandler = { _, completed, _, _ -> if (completed) onShared() }
            // An iPad shows it as a popover, which needs an anchor or it crashes.
            sheet.popoverPresentationController?.sourceView = top.view
            top.presentViewController(sheet, animated = true, completion = null)
        }
    }
}

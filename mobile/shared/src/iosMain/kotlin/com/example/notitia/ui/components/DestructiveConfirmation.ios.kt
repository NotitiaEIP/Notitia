package com.example.notitia.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import kotlinx.cinterop.ExperimentalForeignApi
import notitia.shared.generated.resources.Res
import notitia.shared.generated.resources.cancel
import org.jetbrains.compose.resources.stringResource
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDestructive
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleActionSheet
import platform.UIKit.popoverPresentationController

// UIKit's own action sheet, as in Photos: the red action on top, « Annuler » apart below it. Since iOS 26 it grows
// out of the button that asked, and a touch beside it cancels; before, it rises from the bottom whatever the anchor.
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun DestructiveConfirmation(
    destruction: Destruction?,
    anchor: Rect?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    destruction ?: return
    val cancel = stringResource(Res.string.cancel)
    // Compose counts in pixels, UIKit in points.
    val density = LocalDensity.current.density.toDouble()
    val confirmed by rememberUpdatedState(onConfirm)
    val dismissed by rememberUpdatedState(onDismiss)
    // Read when the sheet opens: a button that moves later does not open it again.
    val from by rememberUpdatedState(anchor)
    DisposableEffect(destruction) {
        // UIKit closes the sheet itself before calling an action: only a sheet left unanswered is closed here.
        var answered = false
        val sheet = UIAlertController.alertControllerWithTitle(
            title = destruction.title,
            message = destruction.message,
            preferredStyle = UIAlertControllerStyleActionSheet,
        )
        sheet.addAction(
            UIAlertAction.actionWithTitle(destruction.confirm, UIAlertActionStyleDestructive) { _ ->
                answered = true
                confirmed()
            },
        )
        // Also run by a touch beside the popover, on iPad and on iPhone since iOS 26.
        sheet.addAction(
            UIAlertAction.actionWithTitle(cancel, UIAlertActionStyleCancel) { _ ->
                answered = true
                dismissed()
            },
        )
        val top = topController()
        if (top == null) {
            dismissed()
        } else {
            // A popover needs a view to point from, or UIKit crashes; the button's frame narrows it to the button.
            sheet.popoverPresentationController?.let { popover ->
                popover.sourceView = top.view
                from?.let {
                    popover.sourceRect = CGRectMake(
                        it.left / density,
                        it.top / density,
                        it.width / density,
                        it.height / density,
                    )
                }
            }
            top.presentViewController(sheet, animated = true, completion = null)
        }
        onDispose {
            if (!answered) sheet.dismissViewControllerAnimated(true, completion = null)
        }
    }
}

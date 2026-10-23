package com.abrah.nightmare.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * ⭐⭐⭐ **THE buttons** (the review, 2026-10-22; `docs/UI.md` §8.9a). Every screen calls these,
 * never Material's `Button` / `OutlinedButton` / `TextButton` / `FilledTonalButton` directly.
 *
 * ⚠⚠ Why wrappers and not the theme: in material3 1.3.1 a button's shape is the FULL-corner
 * token, which `MaterialTheme.shapes` cannot reach, so ~145 buttons were pills beside 15
 * hand-set 12dp rectangles on the same screens. And Material colours a text or outlined
 * button's LABEL with `primary`, a FILL colour that is 3.41:1 on a dialog.
 *
 * ⇒ Same parameters as Material's, so a call site changes only its name; different
 * DEFAULTS: [Radius.control] corners, a 48dp filled/outlined height (Violet Studio's main
 * control; a caller's own `height` still wins, it is applied first), and
 * [StudioColors.accentText] for violet labels. ⚠ Destructive is RED, never violet:
 * [StudioDangerButton], or an outlined one with `colors = dangerOutlinedColors()`.
 */
private val ControlShape = RoundedCornerShape(Radius.control)
private val MainControlHeight = 48.dp

/** ⭐ The primary action: a violet fill, white label. One per surface. */
@Composable
fun StudioButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ControlShape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = Button(
    onClick, modifier.heightIn(min = MainControlHeight), enabled, shape, colors, elevation, border,
    contentPadding, interactionSource, content,
)

/** ⭐ The secondary action: an edge with 3:1 contrast and a violet label. */
@Composable
fun StudioOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ControlShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.studio.accentText),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(
        1.dp,
        MaterialTheme.studio.controlBorder.let { if (enabled) it else it.copy(alpha = 0.38f) },
    ),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(
    onClick, modifier.heightIn(min = MainControlHeight), enabled, shape, colors, elevation, border,
    contentPadding, interactionSource, content,
)

/**
 * ⭐ The compact action: a button inside a row of controls (Change, Files, Reveal, Stop). ⚠ Material's
 * 40dp visual height (its touch target is still 48): these sit in rows, and node sheets already take
 * too much height (the user, 2026-10-17).
 *
 * ⚠⚠ CONTAINED: the same 3:1 edge as [StudioOutlinedButton]. Every button in the app has a container
 * (the user's call, 2026-10-23); a bare violet word beside a filled button read as a missed migration.
 * Changed HERE, not at the call sites, so no new one can be bare.
 */
@Composable
fun StudioTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ControlShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.studio.accentText),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(
        1.dp,
        MaterialTheme.studio.controlBorder.let { if (enabled) it else it.copy(alpha = 0.38f) },
    ),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(
    onClick, modifier, enabled, shape, colors, elevation, border,
    contentPadding, interactionSource, content,
)

/** ⭐ A tonal fill (`secondaryContainer`): a selected or emphasised secondary. */
@Composable
fun StudioTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ControlShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = FilledTonalButton(
    onClick, modifier.heightIn(min = MainControlHeight), enabled, shape, colors, elevation, border,
    contentPadding, interactionSource, content,
)

/** ⭐ A destructive confirm: the error fill with its own `onError` label (6.9:1, not white's 2.5). */
@Composable
fun StudioDangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = StudioButton(
    onClick, modifier, enabled,
    colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
    ),
    content = content,
)

/** ⭐ A red TEXT action (Delete in a row or menu). */
@Composable
fun dangerTextColors(): ButtonColors = dangerOutlinedColors()

/** ⭐ A red OUTLINED action: a dialog's destructive button that is not its confirm ("Open anyway" over unsaved edits). */
@Composable
fun dangerOutlinedColors(): ButtonColors =
    ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)

/**
 * ⭐⭐ A dialog with THREE or more buttons: all of them in the confirm slot, stacked full width,
 * the primary on top and Cancel / Close at the bottom. Each button passes `Modifier.fillMaxWidth()`.
 *
 * ⚠⚠ Never a `Row` of them inside one slot: `AlertDialog` stacks its two SLOTS when they do not
 * fit, not what is inside one, and three contained buttons do not fit a phone dialog. The last one
 * was crushed into an empty violet sliver (Open flow, reported from the phone 2026-10-23).
 */
@Composable
fun DialogButtonStack(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s), content = content)

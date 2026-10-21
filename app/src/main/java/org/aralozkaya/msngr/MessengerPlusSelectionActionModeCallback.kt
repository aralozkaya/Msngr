package org.aralozkaya.msngr

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem

/** Adds editor actions to the platform toolbar while retaining its normal editing actions. */
internal class MessengerPlusSelectionActionModeCallback(
    private val controller: MessengerPlusEditorController,
    private val isEnabled: () -> Boolean,
    private val onColors: () -> (() -> Unit)? = { null },
    private val onTextStyle: () -> (() -> Unit)? = { null },
) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        updateMenu(menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = updateMenu(menu)

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.groupId != R.id.messenger_plus_selection_actions) return false
        val openSheet = when (item.itemId) {
            R.id.format_plus -> if (isEnabled()) onColors() else null
            R.id.format_native -> onTextStyle()
            else -> null
        } ?: return false
        val selection = controller.snapshot().selection
        openSheet()
        mode.finish()
        controller.restoreSelection(selection)
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit

    private fun updateMenu(menu: Menu): Boolean = synchronizeMessengerPlusSelectionMenu(
        menu = menu,
        showFormatting = isEnabled() && controller.hasSelection,
        showColors = onColors() != null,
        showTextStyle = onTextStyle() != null,
    )
}

internal fun synchronizeMessengerPlusSelectionMenu(
    menu: Menu,
    showFormatting: Boolean,
    showColors: Boolean = false,
    showTextStyle: Boolean = false,
): Boolean {
    val actions = listOf(
        R.id.format_plus to R.string.format_plus,
        R.id.format_native to R.string.format_native,
    )
    var changed = false
    actions.forEachIndexed { index, (id, title) ->
        val visible = if (id == R.id.format_native) showTextStyle else showFormatting && showColors
        val count = (0 until menu.size()).count { menu.getItem(it).itemId == id }
        // removeGroup only removes adjacent entries on Android. System actions can be
        // interleaved with ours, so reconcile each ID and keep existing items on refresh.
        repeat((count - if (visible) 1 else 0).coerceAtLeast(0)) {
            menu.removeItem(id)
            changed = true
        }
        if (visible && count == 0) {
            menu.add(R.id.messenger_plus_selection_actions, id, 10 + index, title)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            changed = true
        }
    }
    return changed
}

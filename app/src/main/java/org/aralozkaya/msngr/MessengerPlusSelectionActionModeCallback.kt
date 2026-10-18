package org.aralozkaya.msngr

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem

/** Adds editor actions to the platform toolbar while retaining its normal editing actions. */
internal class MessengerPlusSelectionActionModeCallback(
    private val controller: MessengerPlusEditorController,
    private val isEnabled: () -> Boolean,
    private val onColors: () -> (() -> Unit)? = { null },
) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        updateMenu(menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = updateMenu(menu)

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.groupId != R.id.messenger_plus_selection_actions || !isEnabled()) return false
        when (item.itemId) {
            R.id.format_bold -> controller.toggle(MessengerPlusTextCodec.BOLD_CONTROL)
            R.id.format_italic -> controller.toggle(MessengerPlusTextCodec.ITALIC_CONTROL)
            R.id.format_strikethrough -> controller.toggle(MessengerPlusTextCodec.STRIKEOUT_CONTROL)
            R.id.format_underline -> controller.toggle(MessengerPlusTextCodec.UNDERLINE_CONTROL)
            R.id.format_clear -> controller.removeAllFormatting()
            R.id.format_colors -> {
                val openColors = onColors() ?: return false
                // Finishing the toolbar collapses the selection; keep the text highlighted.
                val selection = controller.snapshot().selection
                openColors()
                mode.finish()
                controller.restoreSelection(selection)
                return true
            }
            else -> return false
        }
        mode.invalidate()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit

    private fun updateMenu(menu: Menu): Boolean = synchronizeMessengerPlusSelectionMenu(
        menu = menu,
        showFormatting = isEnabled() && controller.hasSelection,
        showColors = onColors() != null,
    )
}

internal fun synchronizeMessengerPlusSelectionMenu(
    menu: Menu,
    showFormatting: Boolean,
    showColors: Boolean = false,
): Boolean {
    val actions = listOf(
        R.id.format_bold to R.string.format_bold,
        R.id.format_italic to R.string.format_italic,
        R.id.format_strikethrough to R.string.format_strikethrough,
        R.id.format_underline to R.string.format_underline,
        R.id.format_clear to R.string.format_clear,
        R.id.format_colors to R.string.format_colors,
    )
    var changed = false
    actions.forEachIndexed { index, (id, title) ->
        val visible = showFormatting && (id != R.id.format_colors || showColors)
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

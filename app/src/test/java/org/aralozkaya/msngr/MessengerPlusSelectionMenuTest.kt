package org.aralozkaya.msngr

import android.view.Menu
import android.view.MenuItem
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessengerPlusSelectionMenuTest {
    private val formattingIds = listOf(
        R.id.format_plus,
    )

    @Test
    fun repeatedRefreshesKeepOneOfEachActionWithSystemItemsInterleaved() {
        val fixture = MenuFixture()
        assertTrue(synchronizeMessengerPlusSelectionMenu(fixture.menu, true, showColors = true))
        fixture.addSystemActions()
        val originalItems = fixture.items.toList()

        repeat(20) {
            assertFalse(synchronizeMessengerPlusSelectionMenu(fixture.menu, true, showColors = true))
            formattingIds.forEach { id ->
                assertEquals(1, fixture.items.count { it.itemId == id })
            }
            assertEquals(originalItems.size, fixture.items.size)
            originalItems.zip(fixture.items).forEach { (before, after) ->
                assertSame(before, after)
            }
        }
    }

    @Test
    fun refreshRepairsExistingDuplicatesWithoutRemovingSystemActions() {
        val fixture = MenuFixture()
        fixture.addSystemActions()
        repeat(4) {
            formattingIds.forEachIndexed { index, id ->
                fixture.menu.add(R.id.messenger_plus_selection_actions, id, 10 + index, 0)
            }
        }

        assertTrue(synchronizeMessengerPlusSelectionMenu(fixture.menu, true, showColors = true))
        formattingIds.forEach { id -> assertEquals(1, fixture.items.count { it.itemId == id }) }
        assertEquals(3, fixture.items.size)
        assertTrue(fixture.menu.findItem(android.R.id.copy) != null)
        assertTrue(fixture.menu.findItem(android.R.id.paste) != null)
        assertFalse(synchronizeMessengerPlusSelectionMenu(fixture.menu, true, showColors = true))
    }

    @Test
    fun disablingFormattingRemovesEveryCustomActionAndCanBeReenabled() {
        val fixture = MenuFixture()
        fixture.addSystemActions()
        synchronizeMessengerPlusSelectionMenu(fixture.menu, true, showColors = true)

        assertTrue(synchronizeMessengerPlusSelectionMenu(fixture.menu, false))
        assertEquals(listOf(android.R.id.copy, android.R.id.paste), fixture.items.map { it.itemId })
        assertFalse(synchronizeMessengerPlusSelectionMenu(fixture.menu, false))
        assertTrue(synchronizeMessengerPlusSelectionMenu(fixture.menu, true, showColors = true))
        assertEquals(3, fixture.items.size)
    }

    @Test
    fun selectionAndCursorMenusNeverOfferColorOrEmoticonPickers() {
        for (showFormatting in listOf(true, false)) {
            val fixture = MenuFixture()
            fixture.addSystemActions()
            synchronizeMessengerPlusSelectionMenu(fixture.menu, showFormatting)
            assertTrue(fixture.menu.findItem(R.id.format_colors) == null)
            assertTrue(fixture.menu.findItem(R.id.insert_emoticon) == null)
            assertTrue(fixture.menu.findItem(android.R.id.copy) != null)
            assertTrue(fixture.menu.findItem(android.R.id.paste) != null)
        }
    }

    @Test fun nativeStyleIsIndependentOfPlusAndAvailableAtTheCursorOnlyWhenRequested() {
        val fixture = MenuFixture()
        fixture.addSystemActions()
        synchronizeMessengerPlusSelectionMenu(fixture.menu, false, showTextStyle = true)
        assertTrue(fixture.menu.findItem(R.id.format_native) != null)
        assertTrue(fixture.menu.findItem(R.id.format_plus) == null)
        assertFalse(synchronizeMessengerPlusSelectionMenu(fixture.menu, false, showTextStyle = true))
        synchronizeMessengerPlusSelectionMenu(fixture.menu, true, showColors = true)
        assertTrue(fixture.menu.findItem(R.id.format_native) == null)
        assertTrue(fixture.menu.findItem(R.id.format_plus) != null)
    }

    /** Platform menu interfaces can be exercised on the JVM without a device or Android UI. */
    private class MenuFixture {
        val items = mutableListOf<MenuItem>()
        val menu: Menu = Proxy.newProxyInstance(
            Menu::class.java.classLoader,
            arrayOf(Menu::class.java),
        ) { _, method, arguments ->
            val args = arguments.orEmpty()
            when (method.name) {
                "size" -> items.size
                "getItem" -> items[args[0] as Int]
                "findItem" -> items.firstOrNull { it.itemId == args[0] }
                "add" -> {
                    val item = createItem(args[0] as Int, args[1] as Int, args[2] as Int)
                    val index = items.indexOfFirst { it.order > item.order }
                    items.add(if (index < 0) items.size else index, item)
                    item
                }
                "removeItem" -> {
                    val index = items.indexOfFirst { it.itemId == args[0] }
                    if (index >= 0) items.removeAt(index)
                    null
                }
                "removeGroup" -> {
                    // Android's implementation stops at the first intervening group.
                    val index = items.indexOfFirst { it.groupId == args[0] }
                    while (index >= 0 && index < items.size && items[index].groupId == args[0]) {
                        items.removeAt(index)
                    }
                    null
                }
                else -> error("Unexpected menu method: ${method.name}")
            }
        } as Menu

        fun addSystemActions() {
            menu.add(0, android.R.id.copy, 11, 0)
            menu.add(0, android.R.id.paste, 13, 0)
        }

        private fun createItem(group: Int, id: Int, order: Int): MenuItem =
            Proxy.newProxyInstance(
                MenuItem::class.java.classLoader,
                arrayOf(MenuItem::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getItemId" -> id
                    "getGroupId" -> group
                    "getOrder" -> order
                    "setShowAsAction" -> null
                    "equals" -> proxy === arguments?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "MenuItem($id)"
                    else -> error("Unexpected menu item method: ${method.name}")
                }
            } as MenuItem
    }
}

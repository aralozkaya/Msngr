package org.aralozkaya.msngr.data

import java.util.Locale

internal const val FAVORITES_CATEGORY_ID = "__favorites__"
internal const val GROUPS_CATEGORY_ID = "__groups__"
internal const val OTHER_CONTACTS_CATEGORY_ID = "__other__"

private const val MAX_CATEGORY_NAME_LENGTH = 128

data class ContactCategory(
    val id: String,
    val name: String,
    val isFavorite: Boolean = false,
    val isDefault: Boolean = false,
    val isGroupsSection: Boolean = false,
    val serverGuid: String? = null,
) {
    val isBuiltIn: Boolean
        get() = isFavorite || isDefault || isGroupsSection ||
            id == FAVORITES_CATEGORY_ID || id == GROUPS_CATEGORY_ID || id == OTHER_CONTACTS_CATEGORY_ID

    val isEditable: Boolean
        get() = !isBuiltIn

    val isDeletable: Boolean
        get() = !isBuiltIn
}

val Contact.isFavorite: Boolean
    get() = FAVORITES_CATEGORY_ID in categoryIds

fun Contact.belongsToCategory(categoryId: String): Boolean = when (categoryId) {
    FAVORITES_CATEGORY_ID -> FAVORITES_CATEGORY_ID in categoryIds
    GROUPS_CATEGORY_ID -> isGroup
    OTHER_CONTACTS_CATEGORY_ID -> !isGroup && categoryIds.none {
        it != FAVORITES_CATEGORY_ID && it != OTHER_CONTACTS_CATEGORY_ID
    }
    else -> categoryId in categoryIds
}

fun validateCategoryName(
    rawName: String,
    existingCategories: List<ContactCategory> = emptyList(),
    excludeCategoryId: String? = null,
): String {
    val normalized = rawName.trim()
    require(normalized.isNotEmpty()) { "Enter a category name." }
    require(normalized.length <= MAX_CATEGORY_NAME_LENGTH) {
        "Category names must be 128 characters or fewer."
    }
    val lower = normalized.lowercase(Locale.ROOT)
    require(
        existingCategories.none {
            it.id != excludeCategoryId && it.name.trim().lowercase(Locale.ROOT) == lower
        },
    ) { "A category with this name already exists." }
    return normalized
}

/**
 * Merges a persisted visual order with the current set of built-in and custom categories.
 * Newly discovered custom categories are inserted immediately before "Other contacts".
 */
fun resolveOrderedCategoryIds(
    savedOrder: List<String>,
    customCategories: List<ContactCategory>,
): List<String> {
    val customIds = customCategories.filterNot(ContactCategory::isBuiltIn).map(ContactCategory::id).distinct()
    val validIds = buildSet {
        add(FAVORITES_CATEGORY_ID)
        add(GROUPS_CATEGORY_ID)
        addAll(customIds)
        add(OTHER_CONTACTS_CATEGORY_ID)
    }
    val defaultOrder = buildList {
        add(FAVORITES_CATEGORY_ID)
        add(GROUPS_CATEGORY_ID)
        addAll(customIds)
        add(OTHER_CONTACTS_CATEGORY_ID)
    }
    if (savedOrder.isEmpty()) return defaultOrder

    val ordered = savedOrder.filter { it in validIds }.distinct().toMutableList()
    if (FAVORITES_CATEGORY_ID !in ordered) ordered.add(0, FAVORITES_CATEGORY_ID)
    if (GROUPS_CATEGORY_ID !in ordered) {
        val favIndex = ordered.indexOf(FAVORITES_CATEGORY_ID)
        ordered.add(if (favIndex >= 0) favIndex + 1 else 0, GROUPS_CATEGORY_ID)
    }
    if (OTHER_CONTACTS_CATEGORY_ID !in ordered) ordered.add(OTHER_CONTACTS_CATEGORY_ID)
    val missingCustom = customIds.filterNot { it in ordered }
    if (missingCustom.isNotEmpty()) {
        val otherIndex = ordered.indexOf(OTHER_CONTACTS_CATEGORY_ID)
        if (otherIndex >= 0) ordered.addAll(otherIndex, missingCustom) else ordered.addAll(missingCustom)
    }
    return ordered
}

/**
 * Per-account local fallback for category memberships that a server cannot persist
 * (e.g., chat group / circle memberships or Favorites on servers without IsFavorite groups).
 */
interface CategoryMembershipOverlayStore {
    fun load(accountId: String): Map<String, Set<String>>
    fun save(accountId: String, memberships: Map<String, Set<String>>)
}

object InMemoryCategoryMembershipOverlayStore : CategoryMembershipOverlayStore {
    private val map = mutableMapOf<String, Map<String, Set<String>>>()
    override fun load(accountId: String): Map<String, Set<String>> = synchronized(map) {
        map[accountId].orEmpty()
    }
    override fun save(accountId: String, memberships: Map<String, Set<String>>) = synchronized(map) {
        map[accountId] = memberships.filterValues { it.isNotEmpty() }
    }
}

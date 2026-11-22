package org.aralozkaya.msngr

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aralozkaya.msngr.data.CategoryMembershipOverlayStore
import org.json.JSONArray
import org.json.JSONObject

object CategoryPrefs {
    private const val PREFS_NAME = "msngr_categories"
    private const val KEY_CATEGORY_ORDER = "category_order"
    private const val KEY_HIDDEN_CATEGORIES = "hidden_categories"
    private const val KEY_LOCAL_MEMBERSHIPS = "local_memberships"
    private val mutableOrder = MutableStateFlow<List<String>>(emptyList())
    private val mutableHidden = MutableStateFlow<Set<String>>(emptySet())

    fun orderState(context: Context): StateFlow<List<String>> {
        reload(context)
        return mutableOrder.asStateFlow()
    }

    fun hiddenState(context: Context): StateFlow<Set<String>> {
        reload(context)
        return mutableHidden.asStateFlow()
    }

    fun setOrder(context: Context, order: List<String>) {
        reload(context)
        val normalized = order.distinct()
        mutableOrder.value = normalized
        AccountPreferences.preferences(context, PREFS_NAME).edit {
            putString(KEY_CATEGORY_ORDER, JSONArray(normalized).toString())
        }
    }

    fun setHiddenCategories(context: Context, hiddenIds: Set<String>) {
        reload(context)
        val normalized = hiddenIds.filter(String::isNotBlank).toSet()
        mutableHidden.value = normalized
        AccountPreferences.preferences(context, PREFS_NAME).edit {
            if (normalized.isEmpty()) remove(KEY_HIDDEN_CATEGORIES)
            else putString(KEY_HIDDEN_CATEGORIES, JSONArray(normalized.toList()).toString())
        }
    }

    fun resetOrder(context: Context) {
        reload(context)
        mutableOrder.value = emptyList()
        mutableHidden.value = emptySet()
        AccountPreferences.preferences(context, PREFS_NAME).edit {
            remove(KEY_CATEGORY_ORDER)
            remove(KEY_HIDDEN_CATEGORIES)
        }
    }

    fun removeFromOrder(context: Context, categoryId: String) {
        reload(context)
        val current = mutableOrder.value
        if (categoryId in current) {
            setOrder(context, current.filterNot { it == categoryId })
        }
        val currentHidden = mutableHidden.value
        if (categoryId in currentHidden) {
            setHiddenCategories(context, currentHidden - categoryId)
        }
    }

    internal fun reload(context: Context) {
        val prefs = AccountPreferences.preferences(context, PREFS_NAME)
        val rawOrder = prefs.getString(KEY_CATEGORY_ORDER, null).orEmpty()
        mutableOrder.value = parseOrderJson(rawOrder)
        val rawHidden = prefs.getString(KEY_HIDDEN_CATEGORIES, null).orEmpty()
        mutableHidden.value = parseOrderJson(rawHidden).toSet()
    }

    fun overlayStore(context: Context): CategoryMembershipOverlayStore {
        val appContext = context.applicationContext
        return object : CategoryMembershipOverlayStore {
            override fun load(accountId: String): Map<String, Set<String>> {
                val prefs = appContext.getSharedPreferences(
                    "${PREFS_NAME}_overlay_${accountId.hashCode().toUInt()}",
                    Context.MODE_PRIVATE,
                )
                val raw = prefs.getString(KEY_LOCAL_MEMBERSHIPS, null).orEmpty()
                if (raw.isBlank()) return emptyMap()
                return runCatching {
                    val obj = JSONObject(raw)
                    buildMap {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val contactId = keys.next()
                            val arr = obj.optJSONArray(contactId) ?: continue
                            val set = buildSet {
                                for (i in 0 until arr.length()) {
                                    val id = arr.optString(i).trim()
                                    if (id.isNotEmpty()) add(id)
                                }
                            }
                            if (set.isNotEmpty()) put(contactId, set)
                        }
                    }
                }.getOrDefault(emptyMap())
            }

            override fun save(accountId: String, memberships: Map<String, Set<String>>) {
                val prefs = appContext.getSharedPreferences(
                    "${PREFS_NAME}_overlay_${accountId.hashCode().toUInt()}",
                    Context.MODE_PRIVATE,
                )
                val obj = JSONObject()
                memberships.forEach { (contactId, categories) ->
                    if (categories.isNotEmpty()) {
                        obj.put(contactId, JSONArray(categories.toList()))
                    }
                }
                prefs.edit {
                    if (obj.length() == 0) remove(KEY_LOCAL_MEMBERSHIPS)
                    else putString(KEY_LOCAL_MEMBERSHIPS, obj.toString())
                }
            }
        }
    }

    private fun parseOrderJson(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val id = array.optString(i).trim()
                    if (id.isNotEmpty()) add(id)
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }
}

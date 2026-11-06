package io.github.codex07.iou.ui.navigation

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * Gives every route of the custom navigation stack its own [ViewModelStore], cleared when the route leaves the stack,
 * so a popped form does not keep its ViewModel, draft or payment fields alive. The holder itself is activity-scoped,
 * so open routes survive rotation; each [owner] call points the route at the current activity, and [detach] drops a
 * destroyed one so the holder never keeps it alive.
 */
class RouteStores : ViewModel() {
    private class Entry(private val store: ViewModelStore) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        var parent: HasDefaultViewModelProviderFactory? = null
        override val viewModelStore: ViewModelStore get() = store
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory get() = attached().defaultViewModelProviderFactory
        override val defaultViewModelCreationExtras: CreationExtras get() = attached().defaultViewModelCreationExtras
        private fun attached() = checkNotNull(parent) { "Route used after its activity was destroyed" }
        fun clear() { parent = null; store.clear() }
    }

    private val entries = mutableMapOf<String, Entry>()

    fun <P> owner(route: String, parent: P): ViewModelStoreOwner where P : ViewModelStoreOwner, P : HasDefaultViewModelProviderFactory =
        entries.getOrPut(route) { Entry(ViewModelStore()) }.also { it.parent = parent }

    /** Forgets [parent] (a destroyed activity) wherever a route still delegates to it; the stores themselves stay. */
    fun detach(parent: Any) {
        entries.values.forEach { if (it.parent === parent) it.parent = null }
    }

    fun retainOnly(routes: Set<String>) {
        val gone = entries.keys.filter { it !in routes }
        gone.forEach { entries.remove(it)?.clear() }
    }

    /** Public because Kotlin cannot narrow the inherited protected `onCleared` to internal; tests call it directly. */
    public override fun onCleared() {
        entries.values.forEach { it.clear() }
        entries.clear()
    }
}

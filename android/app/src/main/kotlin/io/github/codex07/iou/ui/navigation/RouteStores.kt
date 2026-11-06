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
 * so open routes survive rotation.
 */
class RouteStores : ViewModel() {
    private class Entry(val store: ViewModelStore, val owner: ViewModelStoreOwner)

    private val entries = mutableMapOf<String, Entry>()

    fun <P> owner(route: String, parent: P): ViewModelStoreOwner where P : ViewModelStoreOwner, P : HasDefaultViewModelProviderFactory =
        entries.getOrPut(route) {
            val store = ViewModelStore()
            Entry(store, object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                override val viewModelStore: ViewModelStore get() = store
                override val defaultViewModelProviderFactory: ViewModelProvider.Factory get() = parent.defaultViewModelProviderFactory
                override val defaultViewModelCreationExtras: CreationExtras get() = parent.defaultViewModelCreationExtras
            })
        }.owner

    fun retainOnly(routes: Set<String>) {
        val gone = entries.keys.filter { it !in routes }
        gone.forEach { entries.remove(it)?.store?.clear() }
    }

    /** Public because Kotlin cannot narrow the inherited protected `onCleared` to internal; tests call it directly. */
    public override fun onCleared() {
        entries.values.forEach { it.store.clear() }
        entries.clear()
    }
}

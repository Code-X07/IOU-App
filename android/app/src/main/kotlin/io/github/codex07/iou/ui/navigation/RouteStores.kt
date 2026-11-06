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
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun <P> owner(route: String, parent: P): ViewModelStoreOwner where P : ViewModelStoreOwner, P : HasDefaultViewModelProviderFactory {
        val store = stores.getOrPut(route) { ViewModelStore() }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore get() = store
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory get() = parent.defaultViewModelProviderFactory
            override val defaultViewModelCreationExtras: CreationExtras get() = parent.defaultViewModelCreationExtras
        }
    }

    fun retainOnly(routes: Set<String>) {
        val gone = stores.keys.filter { it !in routes }
        gone.forEach { stores.remove(it)?.clear() }
    }

    public override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

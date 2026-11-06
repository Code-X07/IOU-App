package io.github.codex07.iou.ui.navigation

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RouteStoresTest {
    class Probe : ViewModel() {
        var cleared = false
        public override fun onCleared() { cleared = true }
    }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = Probe() as T
    }
    private val activity = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore = ViewModelStore()
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        override val defaultViewModelCreationExtras: CreationExtras = CreationExtras.Empty
    }

    private fun RouteStores.probe(route: String): Probe {
        val owner = owner(route, activity)
        return ViewModelProvider.create(owner as ViewModelStoreOwner, (owner as HasDefaultViewModelProviderFactory).defaultViewModelProviderFactory)[Probe::class.java]
    }

    @Test fun poppedRoutesAreClearedAndKeptRoutesSurvive() {
        val stores = RouteStores()
        val a = stores.probe("form-entry:a:expense")
        val b = stores.probe("form-group:b:")
        stores.retainOnly(setOf("form-entry:a:expense"))
        assertTrue(b.cleared)
        assertFalse(a.cleared)
        assertSame(a, stores.probe("form-entry:a:expense"))
        assertSame(stores.owner("form-entry:a:expense", activity), stores.owner("form-entry:a:expense", activity))
    }

    @Test fun reopenedRouteGetsAFreshViewModel() {
        val stores = RouteStores()
        val first = stores.probe("r")
        stores.retainOnly(emptySet())
        assertTrue(first.cleared)
        assertNotSame(first, stores.probe("r"))
    }

    @Test fun ownerDelegatesFactoryToParent() {
        val owner = RouteStores().owner("r", activity) as HasDefaultViewModelProviderFactory
        assertSame(factory, owner.defaultViewModelProviderFactory)
        assertEquals(CreationExtras.Empty, owner.defaultViewModelCreationExtras)
    }

    @Test fun clearingTheHolderClearsEveryRoute() {
        val stores = RouteStores()
        val a = stores.probe("a")
        val b = stores.probe("b")
        stores.onCleared()
        assertTrue(a.cleared)
        assertTrue(b.cleared)
    }
}

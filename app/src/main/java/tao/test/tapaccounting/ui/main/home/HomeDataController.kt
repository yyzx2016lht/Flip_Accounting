package tao.test.tapaccounting.ui.main.home

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.tapaccounting.data.local.AppDatabase

internal class HomeDataController(
    private val fragment: Fragment,
    private val accountCurrencyById: MutableMap<Long, String>,
    private val accountCurrencyByName: MutableMap<String, String>,
    private val getHomeAdapter: () -> HomeAdapter,
) {
    fun refreshAccountCurrencyCache() {
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val assets = AppDatabase.getDatabase(fragment.requireContext()).assetDao().getAllAssetsList()
            val idMap = assets.filter { it.currency.isNotBlank() }.associate { it.id to it.currency }
            val nameMap = assets
                .filter { it.name.isNotBlank() && it.currency.isNotBlank() }
                .associate { it.name to it.currency }
            withContext(Dispatchers.Main) {
                accountCurrencyById.clear()
                accountCurrencyById.putAll(idMap)
                accountCurrencyByName.clear()
                accountCurrencyByName.putAll(nameMap)
                val adapter = getHomeAdapter()
                if (adapter.itemCount > 0) {
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
            }
        }
    }
}

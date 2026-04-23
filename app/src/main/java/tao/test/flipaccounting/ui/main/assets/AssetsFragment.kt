package tao.test.flipaccounting.ui.main.assets

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tao.test.flipaccounting.AddAssetActivity
import tao.test.flipaccounting.AssetIconDefaults
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.logic.CurrencyManager
import tao.test.flipaccounting.logic.CurrencyUtils

class AssetsFragment : Fragment() {

    private lateinit var tvNetAsset: TextView
    private lateinit var layoutAssetsSummaryRow: View
    private lateinit var tvRateStatus: TextView
    private lateinit var tvTotalAsset: TextView
    private lateinit var tvTotalDebt: TextView
    private lateinit var containerCategoryCards: LinearLayout
    private lateinit var nsvAssets: NestedScrollView
    private lateinit var fabAddAsset: FloatingActionButton

    private val db by lazy { AppDatabase.getDatabase(requireContext()) }
    private var hasTriggeredInitialRateRefresh = false
    private var fabHiddenByScroll = false
    private var fabScrollAccumulator = 0
    private var dragAutoScrollActive = false
    private var dragAutoScrollDirection = 0
    private var dragAutoScrollSpeedPx = 0
    private val dragAutoScrollRunner = object : Runnable {
        override fun run() {
            if (!dragAutoScrollActive || !isAdded || !::nsvAssets.isInitialized) return
            if (dragAutoScrollDirection != 0 && dragAutoScrollSpeedPx > 0) {
                nsvAssets.scrollBy(0, dragAutoScrollDirection * dragAutoScrollSpeedPx)
            }
            nsvAssets.postOnAnimation(this)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_assets, container, false)
        initViews(view)
        observeData()
        return view
    }

    private fun initViews(view: View) {
        tvNetAsset = view.findViewById(R.id.tv_net_asset)
        layoutAssetsSummaryRow = view.findViewById(R.id.layout_assets_summary_row)
        tvRateStatus = view.findViewById(R.id.tv_rate_status)
        tvTotalAsset = view.findViewById(R.id.tv_total_asset)
        tvTotalDebt = view.findViewById(R.id.tv_total_debt)
        containerCategoryCards = view.findViewById(R.id.container_category_cards)
        nsvAssets = view.findViewById(R.id.nsv_assets)

        fabAddAsset = view.findViewById(R.id.fab_add_asset)
        fabAddAsset.setOnClickListener {
            startActivity(Intent(requireContext(), AddAssetActivity::class.java))
        }
        fabAddAsset.post { showAssetFab() }

        // 上滑隐藏 FAB，下滑显示 FAB
        nsvAssets.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            applyFabScrollBehavior(dy, scrollY)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!::fabAddAsset.isInitialized) return
        if (hidden) {
            hideAssetFab()
        } else {
            fabAddAsset.post { showAssetFab() }
        }
    }

    fun showAssetFab() {
        if (!isAdded || !::fabAddAsset.isInitialized) return
        fabHiddenByScroll = false
        fabScrollAccumulator = 0
        fabAddAsset.show()
    }

    fun hideAssetFab() {
        if (!::fabAddAsset.isInitialized) return
        fabHiddenByScroll = true
        fabScrollAccumulator = 0
        fabAddAsset.hide()
    }

    private fun applyFabScrollBehavior(dy: Int, scrollY: Int) {
        if (dy == 0) return
        if (scrollY <= 0) {
            showAssetFab()
            return
        }

        fabScrollAccumulator += dy
        if (!fabHiddenByScroll && fabScrollAccumulator > 20) {
            hideAssetFab()
            return
        }
        if (fabHiddenByScroll && fabScrollAccumulator < -8) {
            showAssetFab()
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            db.assetDao().getAllAssets().collectLatest { assets ->
                updateHeader(assets)
                buildCategoryCards(assets)
            }
        }
    }

    // ──────────────────────────────────────────────
    // 顶部统计
    // ──────────────────────────────────────────────
    private fun updateHeader(assets: List<Asset>) {
        val hasForeignIncludedAsset = assets.any {
            it.includeInNetAsset && !it.currency.equals("CNY", ignoreCase = true)
        }
        val includedForeignCurrencies = assets
            .asSequence()
            .filter { it.includeInNetAsset }
            .map { it.currency.trim().uppercase() }
            .filter { it.isNotEmpty() && it != "CNY" }
            .toSet()
        val hasSyncedRates = CurrencyManager.getLastUpdateTime(requireContext()) > 0L
        val hasMissingIncludedRates = includedForeignCurrencies.any {
            CurrencyManager.getMissingRateCurrencies().contains(it)
        }

        if (hasForeignIncludedAsset && !hasSyncedRates) {
            if (!hasTriggeredInitialRateRefresh) {
                hasTriggeredInitialRateRefresh = true
                CurrencyManager.updateRates(requireContext()) { success ->
                    if (!isAdded) return@updateRates
                    if (success) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val latestAssets = db.assetDao().getAllAssetsList()
                            updateHeader(latestAssets)
                        }
                    }
                }
            }
        }

        var netAssetCny = 0.0
        var totalAssetCny = 0.0
        var creditCardDebtCny = 0.0

        assets.forEach {
            if (it.includeInNetAsset) {
                val cnyBalance = CurrencyManager.convertToCny(it.balance, it.currency)
                netAssetCny += cnyBalance
                if (cnyBalance >= 0) totalAssetCny += cnyBalance
            }
            if (it.assetCategory == Asset.CATEGORY_CREDIT_CARD && it.includeInNetAsset) {
                val cnyBalance = CurrencyManager.convertToCny(it.balance, it.currency)
                if (cnyBalance < 0) creditCardDebtCny += cnyBalance
            }
        }

        val netText = CurrencyUtils.formatAmount(netAssetCny, "CNY")
        val totalText = CurrencyUtils.formatAmount(totalAssetCny, "CNY")
        val shouldMarkEstimated = (hasForeignIncludedAsset && !hasSyncedRates) || hasMissingIncludedRates
        tvNetAsset.text = if (shouldMarkEstimated) "${netText}（估算）" else netText
        tvTotalAsset.text = if (shouldMarkEstimated) "${totalText}（估算）" else totalText
        tvTotalDebt.text = if (creditCardDebtCny == 0.0) "暂无"
        else CurrencyUtils.formatAmount(creditCardDebtCny, "CNY")
    }

    // ──────────────────────────────────────────────
    // 动态生成各类别卡片
    // ──────────────────────────────────────────────
    private fun buildCategoryCards(assets: List<Asset>) {
        containerCategoryCards.removeAllViews()

        // 按固定顺序遍历类别，有资产才生成卡片
        Asset.CATEGORY_ORDER.forEach { category ->
            val group = assets.filter { it.assetCategory == category }
            if (group.isEmpty()) return@forEach

            val cardView = buildCategoryCard(category, group)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * resources.displayMetrics.density).toInt()
            }
            containerCategoryCards.addView(cardView, lp)
        }
    }

    /**
     * 构建单个类别卡片
     * @param category  类别常量
     * @param group     该类别下的资产列表
     */
    private fun buildCategoryCard(
        category: String,
        group: List<Asset>
    ): CardView {
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        // ── 计算该类别合计金额 ──
        val total = group
            .filter { it.includeInNetAsset }
            .sumOf { CurrencyManager.convertToCny(it.balance, it.currency) }
        val excludedTotal = group
            .filterNot { it.includeInNetAsset }
            .sumOf { CurrencyManager.convertToCny(it.balance, it.currency) }

        // ── CardView ──
        val card = CardView(ctx).apply {
            radius = 16 * density
            cardElevation = 0f
            setCardBackgroundColor(android.graphics.Color.WHITE)
        }

        val cardContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ── 标题行 ──
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            minimumHeight = (52 * density).toInt()
        }
        val tvTitle = TextView(ctx).apply {
            text = Asset.categoryLabel(category)
            setTextColor(android.graphics.Color.parseColor("#333333"))
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTotal = TextView(ctx).apply {
            text = CurrencyUtils.formatAmount(total, "CNY")
            setTextColor(android.graphics.Color.parseColor("#333333"))
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        headerRow.addView(tvTitle)
        headerRow.addView(tvTotal)

        // ── 分割线 ──
        val divider = View(ctx).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#F0F0F0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }

        // ── 资产列表（支持长按拖拽） ──
        val assetList = group.toMutableList()
        val adapter = AssetRowAdapter(assetList) { asset ->
            val intent = Intent(requireContext(), AssetDetailActivity::class.java)
            intent.putExtra("ASSET_ID", asset.id)
            startActivity(intent)
        }

        val assetsRecycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
        }

        var hasMoved = false
        val touchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun isLongPressDragEnabled(): Boolean = true

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val from = viewHolder.adapterPosition
                    val to = target.adapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                        return false
                    }

                    val moved = assetList.removeAt(from)
                    assetList.add(to, moved)
                    adapter.notifyItemMoved(from, to)
                    hasMoved = true
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.apply {
                            alpha = 0.85f
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    } else {
                        stopDragAutoScroll()
                    }
                }

                override fun onChildDraw(
                    c: android.graphics.Canvas,
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    dX: Float,
                    dY: Float,
                    actionState: Int,
                    isCurrentlyActive: Boolean
                ) {
                    val itemTop = viewHolder.itemView.top
                    val itemBottom = viewHolder.itemView.bottom
                    val minDy = (recyclerView.paddingTop - itemTop).toFloat()
                    val maxDy = (recyclerView.height - recyclerView.paddingBottom - itemBottom).toFloat()
                    val clampedDy = dY.coerceIn(minDy, maxDy)

                    super.onChildDraw(c, recyclerView, viewHolder, dX, clampedDy, actionState, isCurrentlyActive)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                        updateDragAutoScroll(recyclerView, viewHolder, clampedDy)
                    } else {
                        stopDragAutoScroll()
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    stopDragAutoScroll()
                    viewHolder.itemView.apply {
                        alpha = 1f
                        scaleX = 1f
                        scaleY = 1f
                    }
                    if (hasMoved) {
                        hasMoved = false
                        persistCategoryOrder(category, assetList)
                    }
                }
            }
        )
        touchHelper.attachToRecyclerView(assetsRecycler)

        cardContent.addView(headerRow)
        cardContent.addView(divider)
        cardContent.addView(assetsRecycler)
        val tvExcludedSummary = TextView(ctx).apply {
            text = "不计入总资产：${CurrencyUtils.formatAmount(excludedTotal, "CNY")}"
            setTextColor(android.graphics.Color.parseColor("#8A94A6"))
            textSize = 12f
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        }
        cardContent.addView(tvExcludedSummary)
        card.addView(cardContent)
        return card
    }

    private fun updateDragAutoScroll(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dY: Float
    ) {
        if (!::nsvAssets.isInitialized) return
        val rvLoc = IntArray(2)
        val svLoc = IntArray(2)
        recyclerView.getLocationOnScreen(rvLoc)
        nsvAssets.getLocationOnScreen(svLoc)

        val dragCenterYOnScreen = rvLoc[1] + viewHolder.itemView.top + dY + viewHolder.itemView.height / 2f
        val visibleTop = svLoc[1].toFloat()
        val visibleBottom = svLoc[1] + nsvAssets.height.toFloat()
        val edgeThreshold = resources.displayMetrics.density * 84f
        val maxSpeed = (resources.displayMetrics.density * 24f).toInt().coerceAtLeast(8)

        val newDirection: Int
        val newSpeed: Int
        if (dragCenterYOnScreen > visibleBottom - edgeThreshold) {
            val ratio = ((dragCenterYOnScreen - (visibleBottom - edgeThreshold)) / edgeThreshold).coerceIn(0f, 1f)
            newDirection = 1
            newSpeed = (maxSpeed * ratio).toInt().coerceAtLeast(2)
        } else if (dragCenterYOnScreen < visibleTop + edgeThreshold) {
            val ratio = (((visibleTop + edgeThreshold) - dragCenterYOnScreen) / edgeThreshold).coerceIn(0f, 1f)
            newDirection = -1
            newSpeed = (maxSpeed * ratio).toInt().coerceAtLeast(2)
        } else {
            newDirection = 0
            newSpeed = 0
        }

        dragAutoScrollDirection = newDirection
        dragAutoScrollSpeedPx = newSpeed
        if (newDirection != 0) {
            startDragAutoScroll()
        } else {
            stopDragAutoScroll()
        }
    }

    private fun startDragAutoScroll() {
        if (dragAutoScrollActive) return
        dragAutoScrollActive = true
        nsvAssets.removeCallbacks(dragAutoScrollRunner)
        nsvAssets.postOnAnimation(dragAutoScrollRunner)
    }

    private fun stopDragAutoScroll() {
        dragAutoScrollActive = false
        dragAutoScrollDirection = 0
        dragAutoScrollSpeedPx = 0
        if (::nsvAssets.isInitialized) {
            nsvAssets.removeCallbacks(dragAutoScrollRunner)
        }
    }

    private fun persistCategoryOrder(changedCategory: String, reorderedList: List<Asset>) {
        viewLifecycleOwner.lifecycleScope.launch {
            // 取出全部资产，替换掉被拖拽类别的顺序，按 CATEGORY_ORDER 顺序重新分配全局 sortOrder
            val allAssets = db.assetDao().getAllAssetsList()
            val otherAssets = allAssets.filter { it.assetCategory != changedCategory }

            // 按固定类别顺序重建完整的全局排列
            val globalList = mutableListOf<Asset>()
            Asset.CATEGORY_ORDER.forEach { cat ->
                if (cat == changedCategory) {
                    globalList.addAll(reorderedList)
                } else {
                    globalList.addAll(otherAssets.filter { it.assetCategory == cat }
                        .sortedBy { it.sortOrder })
                }
            }

            val orders = globalList.mapIndexed { idx, asset ->
                asset.id to (idx + 1) * 10
            }.toMap()
            db.assetDao().reorderAssets(orders)
        }
    }

    private class AssetRowAdapter(
        private val assets: List<Asset>,
        private val onClick: (Asset) -> Unit
    ) : RecyclerView.Adapter<AssetRowAdapter.AssetVH>() {

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = assets[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_asset_row, parent, false)
            return AssetVH(view)
        }

        override fun onBindViewHolder(holder: AssetVH, position: Int) {
            val asset = assets[position]
            holder.tvName.text = asset.name
            holder.tvBalance.text = CurrencyUtils.formatAmount(asset.balance, asset.currency)

            val remarkTexts = mutableListOf<String>()
            if (!asset.includeInNetAsset) remarkTexts.add("不计入总资产")
            if (asset.remark.isNotBlank()) remarkTexts.add(asset.remark.trim())
            if (remarkTexts.isNotEmpty()) {
                holder.tvRemark.visibility = View.VISIBLE
                holder.tvRemark.text = remarkTexts.joinToString(" · ")
            } else {
                holder.tvRemark.visibility = View.GONE
            }

            Glide.with(holder.itemView)
                .load(AssetIconDefaults.withDefault(asset.icon))
                .transform(CircleCrop())
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_placeholder)
                .error(R.drawable.ic_placeholder)
                .into(holder.ivIcon)

            holder.itemView.setOnClickListener { onClick(asset) }
        }

        override fun getItemCount(): Int = assets.size

        class AssetVH(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView = v.findViewById(R.id.iv_asset_icon)
            val tvName: TextView = v.findViewById(R.id.tv_asset_name)
            val tvRemark: TextView = v.findViewById(R.id.tv_asset_remark)
            val tvBalance: TextView = v.findViewById(R.id.tv_asset_balance)
        }
    }
}

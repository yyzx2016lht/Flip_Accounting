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
import tao.test.flipaccounting.ui.common.UiMotion
import tao.test.flipaccounting.ui.common.UiMotion.fadeIn
import tao.test.flipaccounting.ui.common.UiMotion.applyItemPressFeedback
import tao.test.flipaccounting.ui.common.UiMotion.crossfadeText

class AssetsFragment : Fragment() {

    private lateinit var tvNetAsset: TextView
    private lateinit var layoutAssetsSummaryRow: View
    private lateinit var tvRateStatus: TextView
    private lateinit var tvTotalAsset: TextView
    private lateinit var tvTotalDebt: TextView
    private lateinit var containerCategoryCards: LinearLayout
    private lateinit var nsvAssets: NestedScrollView
    private lateinit var fabAddAsset: FloatingActionButton
    private lateinit var layoutEmptyState: View

    private val db by lazy { AppDatabase.getDatabase(requireContext()) }
    private var hasTriggeredInitialRateRefresh = false
    private var fabHiddenByScroll = false
    private var fabScrollAccumulator = 0
    private var dragAutoScrollActive = false
    private var dragAutoScrollDirection = 0
    private var dragAutoScrollSpeedPx = 0
    private val collapsedCategories = mutableSetOf<String>()
    private var isFirstLoad = true
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
        layoutEmptyState = view.findViewById(R.id.layout_empty_state)

        fabAddAsset = view.findViewById(R.id.fab_add_asset)
        fabAddAsset.setOnClickListener {
            startActivity(Intent(requireContext(), AddAssetActivity::class.java))
        }
        fabAddAsset.post { showAssetFab() }

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
        fabAddAsset.animate().cancel()
        if (fabAddAsset.visibility != View.VISIBLE) {
            fabAddAsset.alpha = 0f
            fabAddAsset.scaleX = 0.5f
            fabAddAsset.scaleY = 0.5f
            fabAddAsset.show()
        }
        fabAddAsset.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(UiMotion.NORMAL)
            .setInterpolator(UiMotion.STANDARD_EASING)
            .withLayer()
            .start()
    }

    fun hideAssetFab() {
        if (!::fabAddAsset.isInitialized) return
        fabHiddenByScroll = true
        fabScrollAccumulator = 0
        fabAddAsset.animate().cancel()
        fabAddAsset.animate()
            .alpha(0f)
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(UiMotion.NORMAL)
            .setInterpolator(UiMotion.EXIT_EASING)
            .withLayer()
            .withEndAction {
                if (isAdded) {
                    fabAddAsset.hide()
                    fabAddAsset.alpha = 1f
                    fabAddAsset.scaleX = 1f
                    fabAddAsset.scaleY = 1f
                }
            }
            .start()
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
        val netDisplay = if (shouldMarkEstimated) "${netText}（估算）" else netText
        val totalDisplay = if (shouldMarkEstimated) "${totalText}（估算）" else totalText
        val debtDisplay = if (creditCardDebtCny == 0.0) "暂无"
        else CurrencyUtils.formatAmount(creditCardDebtCny, "CNY")

        tvNetAsset.crossfadeText(netDisplay)
        tvTotalAsset.crossfadeText(totalDisplay)
        tvTotalDebt.crossfadeText(debtDisplay)

        // Rate status chip
        if (hasForeignIncludedAsset) {
            if (hasSyncedRates && !hasMissingIncludedRates) {
                tvRateStatus.text = "汇率已同步"
            } else {
                tvRateStatus.text = "部分汇率缺失（估算中）"
            }
            tvRateStatus.visibility = View.VISIBLE
        } else {
            tvRateStatus.visibility = View.GONE
        }
    }

    // ──────────────────────────────────────────────
    // 动态生成各类别卡片
    // ──────────────────────────────────────────────
    private fun buildCategoryCards(assets: List<Asset>) {
        containerCategoryCards.removeAllViews()

        // Empty state
        if (assets.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            containerCategoryCards.visibility = View.GONE
            return
        } else {
            layoutEmptyState.visibility = View.GONE
            containerCategoryCards.visibility = View.VISIBLE
        }

        val categoryViews = mutableListOf<View>()

        Asset.CATEGORY_ORDER.forEach { category ->
            val group = assets.filter { it.assetCategory == category }
            if (group.isEmpty()) return@forEach

            val cardView = buildCategoryCard(category, group)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.space_16)
            }
            containerCategoryCards.addView(cardView, lp)
            categoryViews.add(cardView)
        }

        // Stagger first-load animation
        if (isFirstLoad && categoryViews.isNotEmpty()) {
            isFirstLoad = false
            categoryViews.forEachIndexed { index, view ->
                view.alpha = 0f
                view.translationY = 20f
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(UiMotion.NORMAL)
                    .setInterpolator(UiMotion.STANDARD_EASING)
                    .setStartDelay(index * 40L)
                    .withLayer()
                    .start()
            }
        }
    }

    /**
     * 构建单个类别卡片
     */
    private fun buildCategoryCard(
        category: String,
        group: List<Asset>
    ): CardView {
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        val total = group
            .filter { it.includeInNetAsset }
            .sumOf { CurrencyManager.convertToCny(it.balance, it.currency) }
        val excludedTotal = group
            .filterNot { it.includeInNetAsset }
            .sumOf { CurrencyManager.convertToCny(it.balance, it.currency) }

        val card = CardView(ctx).apply {
            radius = resources.getDimension(R.dimen.asset_category_card_radius)
            cardElevation = 0f
            setCardBackgroundColor(ctx.getColor(R.color.surface_card))
        }

        val cardContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ── 标题行（with ripple press feedback） ──
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(
                resources.getDimensionPixelSize(R.dimen.card_padding),
                0,
                resources.getDimensionPixelSize(R.dimen.card_padding),
                0
            )
            minimumHeight = resources.getDimensionPixelSize(R.dimen.asset_category_header_height)
            background = ctx.getDrawable(R.drawable.bg_asset_category_header)
            isClickable = true
            isFocusable = true
        }

        val tvTitle = TextView(ctx).apply {
            text = Asset.categoryLabel(category)
            setTextColor(ctx.getColor(R.color.text_primary))
            textSize = resources.getDimension(R.dimen.asset_category_title_size) / density
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTotal = TextView(ctx).apply {
            text = CurrencyUtils.formatAmount(total, "CNY")
            setTextColor(ctx.getColor(R.color.asset_category_header_total))
            textSize = resources.getDimension(R.dimen.asset_category_total_size) / density
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val ivExpand = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ctx.getColor(R.color.text_tertiary)
            )
            rotation = 90f
            val iconSize = resources.getDimensionPixelSize(R.dimen.icon_size_16)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.space_4)
            }
        }

        headerRow.addView(tvTitle)
        headerRow.addView(tvTotal)
        headerRow.addView(ivExpand)

        // ── 分割线 ──
        val divider = View(ctx).apply {
            setBackgroundColor(ctx.getColor(R.color.asset_divider))
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
            setTextColor(ctx.getColor(R.color.asset_excluded_text))
            textSize = resources.getDimension(R.dimen.text_size_12) / density
            setPadding(
                resources.getDimensionPixelSize(R.dimen.card_padding),
                resources.getDimensionPixelSize(R.dimen.space_8),
                resources.getDimensionPixelSize(R.dimen.card_padding),
                resources.getDimensionPixelSize(R.dimen.space_12)
            )
        }
        cardContent.addView(tvExcludedSummary)

        fun applyCollapsedState(collapsed: Boolean, withAnimation: Boolean) {
            val targetVisibility = if (collapsed) View.GONE else View.VISIBLE
            val duration = if (withAnimation) UiMotion.FAST else 0L
            ivExpand.animate()
                .rotation(if (collapsed) 0f else 90f)
                .setDuration(duration)
                .setInterpolator(if (withAnimation) UiMotion.STANDARD_EASING else null)
                .start()
            if (withAnimation && !collapsed) {
                divider.alpha = 0f
                divider.visibility = View.VISIBLE
                divider.animate().alpha(1f).setDuration(duration).start()
                assetsRecycler.alpha = 0f
                assetsRecycler.translationY = 12f
                assetsRecycler.visibility = View.VISIBLE
                assetsRecycler.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(UiMotion.NORMAL)
                    .setInterpolator(UiMotion.STANDARD_EASING)
                    .start()
                tvExcludedSummary.alpha = 0f
                tvExcludedSummary.visibility = View.VISIBLE
                tvExcludedSummary.animate().alpha(1f).setDuration(duration).start()
            } else if (withAnimation && collapsed) {
                divider.animate().alpha(0f).setDuration(duration).withEndAction {
                    divider.visibility = View.GONE
                }.start()
                assetsRecycler.animate()
                    .alpha(0f)
                    .translationY(8f)
                    .setDuration(duration)
                    .setInterpolator(UiMotion.EXIT_EASING)
                    .withEndAction {
                        assetsRecycler.visibility = View.GONE
                        assetsRecycler.translationY = 0f
                    }.start()
                tvExcludedSummary.animate().alpha(0f).setDuration(duration).withEndAction {
                    tvExcludedSummary.visibility = View.GONE
                }.start()
            } else {
                divider.visibility = targetVisibility
                assetsRecycler.visibility = targetVisibility
                tvExcludedSummary.visibility = targetVisibility
            }
        }

        val initiallyCollapsed = collapsedCategories.contains(category)
        applyCollapsedState(initiallyCollapsed, withAnimation = false)
        headerRow.setOnClickListener {
            val collapsed = !collapsedCategories.contains(category)
            if (collapsed) collapsedCategories.add(category) else collapsedCategories.remove(category)
            applyCollapsedState(collapsed, withAnimation = true)
        }

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
            val allAssets = db.assetDao().getAllAssetsList()
            val otherAssets = allAssets.filter { it.assetCategory != changedCategory }

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

        override fun onViewAttachedToWindow(holder: AssetVH) {
            super.onViewAttachedToWindow(holder)
            holder.itemView.applyItemPressFeedback()
        }

        override fun onBindViewHolder(holder: AssetVH, position: Int) {
            val asset = assets[position]
            holder.tvName.text = asset.name
            holder.tvBalance.text = CurrencyUtils.formatAmount(asset.balance, asset.currency)

            val remarkTexts = mutableListOf<String>()
            if (!asset.includeInNetAsset) remarkTexts.add("不计入")
            if (asset.remark.isNotBlank()) remarkTexts.add(asset.remark.trim())
            if (remarkTexts.isNotEmpty()) {
                holder.tvRemark.visibility = View.VISIBLE
                holder.tvRemark.text = remarkTexts.joinToString(" | ")
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

package tao.test.flipaccounting.ui.main.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import tao.test.flipaccounting.R

class ChartSettingsBottomSheetFragment : BottomSheetDialogFragment() {

    private var currentRange: Int = 0 // 0: 7days, 1: 15days, 2: week, 3: month
    private var currentType: Int = 0 // 0: expense, 1: income, 2: both
    private var listener: ((Int, Int) -> Unit)? = null

    companion object {
        fun newInstance(range: Int, type: Int): ChartSettingsBottomSheetFragment {
            val frag = ChartSettingsBottomSheetFragment()
            frag.currentRange = range
            frag.currentType = type
            return frag
        }
    }

    fun setOnSettingsChangedListener(l: (Int, Int) -> Unit) {
        listener = l
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_chart_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val groupTimeRange = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupTimeRange)
        val groupType = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupType)

        // Restore selected state
        when (currentRange) {
            0 -> groupTimeRange.check(R.id.btnRange7Days)
            1 -> groupTimeRange.check(R.id.btnRange15Days)
            2 -> groupTimeRange.check(R.id.btnRangeWeek)
            3 -> groupTimeRange.check(R.id.btnRangeMonth)
        }

        when (currentType) {
            0 -> groupType.check(R.id.btnTypeExpense)
            1 -> groupType.check(R.id.btnTypeIncome)
            2 -> groupType.check(R.id.btnTypeBoth)
        }

        groupTimeRange.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                currentRange = when (checkedId) {
                    R.id.btnRange7Days -> 0
                    R.id.btnRange15Days -> 1
                    R.id.btnRangeWeek -> 2
                    R.id.btnRangeMonth -> 3
                    else -> 0
                }
                listener?.invoke(currentRange, currentType)
            }
        }

        groupType.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                currentType = when (checkedId) {
                    R.id.btnTypeExpense -> 0
                    R.id.btnTypeIncome -> 1
                    R.id.btnTypeBoth -> 2
                    else -> 0
                }
                listener?.invoke(currentRange, currentType)
            }
        }
    }
}

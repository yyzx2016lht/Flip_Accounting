package tao.test.tapaccounting.ui.main.home

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Path
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet
import com.github.mikephil.charting.renderer.BarChartRenderer
import com.github.mikephil.charting.utils.Utils
import com.github.mikephil.charting.utils.ViewPortHandler
import kotlin.math.min

class RoundedBarChartRenderer(
    chart: BarChart,
    animator: ChartAnimator,
    viewPortHandler: ViewPortHandler
) : BarChartRenderer(chart, animator, viewPortHandler) {

    /** If true, draw fully rounded bars (top+bottom). If false, only top corners rounded. */
    var fullRound: Boolean = false

    private val barRect = RectF()
    private val cornerRadiusPx = Utils.convertDpToPixel(6f)
    private val minBarHeightPx = Utils.convertDpToPixel(3f)

    override fun drawDataSet(c: Canvas, dataSet: IBarDataSet, index: Int) {
        val trans = mChart.getTransformer(dataSet.axisDependency)
        val buffer = mBarBuffers[index]
        mBarBorderPaint.color = dataSet.barBorderColor
        mBarBorderPaint.strokeWidth = Utils.convertDpToPixel(dataSet.barBorderWidth)

        buffer.setPhases(mAnimator.phaseX, mAnimator.phaseY)
        buffer.setDataSet(index)
        buffer.setInverted(mChart.isInverted(dataSet.axisDependency))
        buffer.setBarWidth(mChart.barData.barWidth)
        buffer.feed(dataSet)
        trans.pointValuesToPixel(buffer.buffer)

        val isSingleColor = dataSet.colors.size == 1
        if (isSingleColor) {
            mRenderPaint.color = dataSet.color
        }

        var j = 0
        while (j < buffer.size()) {
            val left = buffer.buffer[j]
            val top = buffer.buffer[j + 1]
            val right = buffer.buffer[j + 2]
            val bottom = buffer.buffer[j + 3]

            if (!mViewPortHandler.isInBoundsLeft(right)) {
                j += 4
                continue
            }
            if (!mViewPortHandler.isInBoundsRight(left)) break

            if (!isSingleColor) {
                mRenderPaint.color = dataSet.getColor(j / 4)
            }

            val entry = dataSet.getEntryForIndex(j / 4) as? BarEntry
            if (entry == null || entry.y <= 0f) {
                j += 4
                continue
            }

            val rawHeight = bottom - top
            if (rawHeight <= 0f) {
                j += 4
                continue
            }

            barRect.set(left, top, right, bottom)
            if (barRect.height() < minBarHeightPx) {
                barRect.top = barRect.bottom - minBarHeightPx
            }

            val radius = min(cornerRadiusPx, min(barRect.width() / 2f, barRect.height() / 2f))

            // Create per-corner radii depending on fullRound flag.
            val radii = if (fullRound) {
                floatArrayOf(
                    radius, radius, // Top-left
                    radius, radius, // Top-right
                    radius, radius, // Bottom-right
                    radius, radius  // Bottom-left
                )
            } else {
                floatArrayOf(
                    radius, radius, // Top-left
                    radius, radius, // Top-right
                    0f, 0f,         // Bottom-right
                    0f, 0f          // Bottom-left
                )
            }

            val path = Path().apply {
                reset()
                addRoundRect(barRect, radii, Path.Direction.CW)
            }

            // Draw filled bar
            c.drawPath(path, mRenderPaint)

            // Draw border if needed
            if (dataSet.barBorderWidth > 0f) {
                c.drawPath(path, mBarBorderPaint)
            }

            j += 4
        }
    }
}

package tao.test.flipaccounting.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import tao.test.flipaccounting.OverlayService
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.R
import tao.test.flipaccounting.tap.TapActionRegistry
import tao.test.flipaccounting.tap.TapModel
import java.util.Locale

class SensitivityActivity : AppCompatActivity() {

    private lateinit var cardFlipSensitivity: View
    private lateinit var cardTapSensitivity: View
    private lateinit var cardTapOptions: View

    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentValue: TextView
    private lateinit var tvParamG: TextView
    private lateinit var tvParamTime: TextView
    private lateinit var btnReset: MaterialButton
    private lateinit var switchAdvanced: SwitchMaterial
    private lateinit var layoutStandard: LinearLayout
    private lateinit var layoutAdvanced: LinearLayout
    private lateinit var etCustomG: EditText
    private lateinit var etCustomTime: EditText
    private lateinit var btnSaveCustom: MaterialButton

    private lateinit var seekBarTap: SeekBar
    private lateinit var tvTapCurrentValue: TextView
    private lateinit var tvTapModelName: TextView
    private lateinit var tvTapActionDoubleName: TextView
    private lateinit var tvTapActionTripleName: TextView
    private lateinit var switchTapNnapi: SwitchMaterial
    private lateinit var switchTapTriple: SwitchMaterial
    private lateinit var btnTapActionTriple: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensitivity)

        initViews()
        loadData()
    }

    private fun initViews() {
        findViewById<ImageView>(R.id.toolbar).setOnClickListener { finish() }

        cardFlipSensitivity = findViewById(R.id.card_flip_sensitivity)
        cardTapSensitivity = findViewById(R.id.card_tap_sensitivity)
        cardTapOptions = findViewById(R.id.card_tap_options)

        val tapEnabled = Prefs.isDoubleTapEnabled(this)
        cardFlipSensitivity.visibility = if (Prefs.isFlipEnabled(this)) View.VISIBLE else View.GONE
        cardTapSensitivity.visibility = if (tapEnabled) View.VISIBLE else View.GONE
        cardTapOptions.visibility = if (tapEnabled) View.VISIBLE else View.GONE

        initFlipViews()
        initTapViews()
        initTapOptions()
    }

    private fun initFlipViews() {
        seekBar = findViewById(R.id.seekBarSensitivity)
        tvCurrentValue = findViewById(R.id.tvCurrentValue)
        tvParamG = findViewById(R.id.tvParamG)
        tvParamTime = findViewById(R.id.tvParamTime)
        btnReset = findViewById(R.id.btnResetDefault)
        switchAdvanced = findViewById(R.id.switchAdvancedMode)
        layoutStandard = findViewById(R.id.layoutStandardMode)
        layoutAdvanced = findViewById(R.id.layoutAdvancedInputs)
        etCustomG = findViewById(R.id.etCustomG)
        etCustomTime = findViewById(R.id.etCustomTime)
        btnSaveCustom = findViewById(R.id.btnSaveCustom)

        switchAdvanced.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setUseCustomSensitivity(this, isChecked)
            updateModeVisibility(isChecked)
            updateFlipUI(if (isChecked) -1 else seekBar.progress)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!switchAdvanced.isChecked) {
                    updateFlipUI(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!switchAdvanced.isChecked) {
                    seekBar?.progress?.let {
                        Prefs.setFlipSensitivity(this@SensitivityActivity, it)
                    }
                }
            }
        })

        btnReset.setOnClickListener {
            if (switchAdvanced.isChecked) {
                switchAdvanced.isChecked = false
            }
            seekBar.progress = 50
            Prefs.setFlipSensitivity(this, 50)
            updateFlipUI(50)
            Toast.makeText(this, "已恢复默认灵敏度", Toast.LENGTH_SHORT).show()
        }

        btnSaveCustom.setOnClickListener {
            saveCustomParams()
        }
    }

    private fun initTapViews() {
        seekBarTap = findViewById(R.id.seekBarTapSensitivity)
        tvTapCurrentValue = findViewById(R.id.tvTapCurrentValue)

        seekBarTap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTapUI(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { level ->
                    Prefs.setTapSensitivityLevel(this@SensitivityActivity, level)
                    restartTapDetection()
                }
            }
        })
    }

    private fun initTapOptions() {
        tvTapModelName = findViewById(R.id.tv_tap_model_name)
        tvTapActionDoubleName = findViewById(R.id.tv_tap_action_double_name)
        tvTapActionTripleName = findViewById(R.id.tv_tap_action_triple_name)
        switchTapNnapi = findViewById(R.id.switch_tap_nnapi)
        switchTapTriple = findViewById(R.id.switch_tap_triple)
        btnTapActionTriple = findViewById(R.id.btn_tap_action_triple)
        val layoutTapNnapi = findViewById<View>(R.id.layout_tap_nnapi)
        val layoutTapTriple = findViewById<View>(R.id.layout_tap_triple)

        layoutTapNnapi.setOnClickListener { switchTapNnapi.performClick() }
        layoutTapTriple.setOnClickListener { switchTapTriple.performClick() }

        // 模型选择
        val currentModel = TapModel.resolve(this)
        tvTapModelName.text = currentModel.displayName
        findViewById<View>(R.id.btn_tap_model).setOnClickListener {
            val models = TapModel.values()
            val names = models.map { it.displayName }.toTypedArray()
            val currentIdx = models.indexOf(currentModel)
            AlertDialog.Builder(this)
                .setTitle("选择模型（设备尺寸）")
                .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                    Prefs.setTapModel(this, models[which].path)
                    tvTapModelName.text = models[which].displayName
                    restartTapDetection()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // NNAPI 低功耗
        switchTapNnapi.isChecked = Prefs.isTapNnapiLowPower(this)
        switchTapNnapi.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setTapNnapiLowPower(this, isChecked)
            restartTapDetection()
        }

        // 三击模式
        switchTapTriple.isChecked = Prefs.isTapTripleEnabled(this)
        btnTapActionTriple.visibility = if (Prefs.isTapTripleEnabled(this)) View.VISIBLE else View.GONE
        switchTapTriple.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setTapTripleEnabled(this, isChecked)
            btnTapActionTriple.visibility = if (isChecked) View.VISIBLE else View.GONE
            restartTapDetection()
        }

        // 双击动作
        val doubleActionId = Prefs.getTapActionDouble(this)
        tvTapActionDoubleName.text = TapActionRegistry.findById(doubleActionId)?.displayName ?: "未设置"
        findViewById<View>(R.id.btn_tap_action_double).setOnClickListener {
            val ids = TapActionRegistry.getIds()
            val names = TapActionRegistry.getDisplayNames()
            val currentIdx = ids.indexOf(Prefs.getTapActionDouble(this))
            AlertDialog.Builder(this)
                .setTitle("双击动作")
                .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                    Prefs.setTapActionDouble(this, ids[which])
                    tvTapActionDoubleName.text = names[which]
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 三击动作
        val tripleActionId = Prefs.getTapActionTriple(this)
        tvTapActionTripleName.text = TapActionRegistry.findById(tripleActionId)?.displayName ?: "未设置"
        findViewById<View>(R.id.btn_tap_action_triple).setOnClickListener {
            val ids = TapActionRegistry.getIds()
            val names = TapActionRegistry.getDisplayNames()
            val currentIdx = ids.indexOf(Prefs.getTapActionTriple(this))
            AlertDialog.Builder(this)
                .setTitle("三击动作")
                .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                    Prefs.setTapActionTriple(this, ids[which])
                    tvTapActionTripleName.text = names[which]
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun loadData() {
        if (Prefs.isFlipEnabled(this)) {
            val isCustom = Prefs.isUseCustomSensitivity(this)
            switchAdvanced.isChecked = isCustom
            updateModeVisibility(isCustom)

            val currentProgress = Prefs.getFlipSensitivity(this)
            seekBar.progress = currentProgress

            etCustomG.setText(Prefs.getCustomGThreshold(this).toString())
            etCustomTime.setText(Prefs.getCustomMaxDuration(this).toString())

            updateFlipUI(if (isCustom) -1 else currentProgress)
        }

        if (Prefs.isDoubleTapEnabled(this)) {
            val tapLevel = Prefs.getTapSensitivityLevel(this)
            seekBarTap.progress = tapLevel
            updateTapUI(tapLevel)
        }
    }

    private fun updateModeVisibility(isCustom: Boolean) {
        layoutStandard.visibility = if (isCustom) View.GONE else View.VISIBLE
        layoutAdvanced.visibility = if (isCustom) View.VISIBLE else View.GONE
    }

    private fun saveCustomParams() {
        try {
            val g = etCustomG.text.toString().toFloat()
            val time = etCustomTime.text.toString().toLong()

            if (g < 1.0f || g > 20.0f) {
                Toast.makeText(this, "重力阈值超出合理范围(1-20)", Toast.LENGTH_SHORT).show()
                return
            }
            if (time < 50 || time > 5000) {
                Toast.makeText(this, "耗时参数超出合理范围(50-5000ms)", Toast.LENGTH_SHORT).show()
                return
            }

            Prefs.setCustomGThreshold(this, g)
            Prefs.setCustomMaxDuration(this, time)
            updateFlipUI(-1)
            Toast.makeText(this, "自定义参数已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "请输入正确的数值", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFlipUI(progress: Int) {
        if (progress == -1) {
            tvCurrentValue.text = "当前设定：进阶自定义"
            val g = Prefs.getCustomGThreshold(this)
            val time = Prefs.getCustomMaxDuration(this)
            tvParamG.text = String.format(Locale.US, "重力阈值：%.2fg (自定义)", g)
            tvParamTime.text = "最大耗时：${time}ms (自定义)"
            return
        }

        val label = when {
            progress < 20 -> "非常灵敏 ($progress)"
            progress < 40 -> "较灵敏 ($progress)"
            progress < 60 -> "标准 ($progress)"
            progress < 80 -> "较严格 ($progress)"
            else -> "非常严格 ($progress)"
        }
        tvCurrentValue.text = "当前设定：$label"

        val gThreshold = 5.5f + (progress / 100f) * 3.5f
        val maxDuration = 800L - (progress * 5L)

        tvParamG.text = String.format(Locale.US, "重力阈值：%.2fg", gThreshold)
        tvParamTime.text = "最大耗时：${maxDuration}ms"
    }

    private fun updateTapUI(level: Int) {
        val label = when (level) {
            in 0..1 -> "非常低"
            in 2..3 -> "低"
            in 4..5 -> "中等"
            in 6..7 -> "高"
            in 8..10 -> "非常高"
            else -> "中等"
        }
        tvTapCurrentValue.text = "当前等级：$label"
    }

    private fun restartTapDetection() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_RESTART_DOUBLE_TAP
        }
        startService(intent)
    }
}

package tao.test.flipaccounting.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.R
import java.util.Locale

class FlipSensitivityActivity : AppCompatActivity() {

    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentValue: TextView
    private lateinit var tvParamG: TextView
    private lateinit var tvParamTime: TextView
    private lateinit var btnReset: MaterialButton
    
    // 进阶模式 UI
    private lateinit var switchAdvanced: SwitchMaterial
    private lateinit var layoutStandard: LinearLayout
    private lateinit var layoutAdvanced: LinearLayout
    private lateinit var etCustomG: EditText
    private lateinit var etCustomTime: EditText
    private lateinit var btnSaveCustom: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flip_sensitivity)

        initViews()
        loadData()
    }

    private fun initViews() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

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
            updateUI(if (isChecked) -1 else seekBar.progress)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!switchAdvanced.isChecked) {
                    updateUI(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!switchAdvanced.isChecked) {
                    seekBar?.progress?.let {
                        Prefs.setFlipSensitivity(this@FlipSensitivityActivity, it)
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
            updateUI(50)
            Toast.makeText(this, "已恢复默认灵敏度", Toast.LENGTH_SHORT).show()
        }

        btnSaveCustom.setOnClickListener {
            saveCustomParams()
        }
    }

    private fun loadData() {
        val isCustom = Prefs.isUseCustomSensitivity(this)
        switchAdvanced.isChecked = isCustom
        updateModeVisibility(isCustom)

        val currentProgress = Prefs.getFlipSensitivity(this)
        seekBar.progress = currentProgress
        
        etCustomG.setText(Prefs.getCustomGThreshold(this).toString())
        etCustomTime.setText(Prefs.getCustomMaxDuration(this).toString())

        updateUI(if (isCustom) -1 else currentProgress)
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
            updateUI(-1)
            Toast.makeText(this, "自定义参数已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "请输入正确的数值", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI(progress: Int) {
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
}

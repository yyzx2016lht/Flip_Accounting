package tao.test.flipaccounting

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File

class StorageImageViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATHS = "extra_image_paths"
        const val EXTRA_INDEX = "extra_index"
    }

    private lateinit var ivImage: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton

    private var imagePaths: List<String> = emptyList()
    private var index: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_image_viewer)

        ivImage = findViewById(R.id.iv_viewer_image)
        tvTitle = findViewById(R.id.tv_viewer_title)
        btnPrev = findViewById(R.id.btn_viewer_prev)
        btnNext = findViewById(R.id.btn_viewer_next)
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        imagePaths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS).orEmpty()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (imagePaths.size - 1).coerceAtLeast(0))

        btnPrev.setOnClickListener {
            if (imagePaths.isEmpty()) return@setOnClickListener
            index = if (index - 1 < 0) imagePaths.lastIndex else index - 1
            bind()
        }
        btnNext.setOnClickListener {
            if (imagePaths.isEmpty()) return@setOnClickListener
            index = if (index + 1 > imagePaths.lastIndex) 0 else index + 1
            bind()
        }

        bind()
    }

    private fun bind() {
        if (imagePaths.isEmpty()) {
            tvTitle.text = "暂无图片"
            ivImage.setImageDrawable(null)
            btnPrev.isEnabled = false
            btnNext.isEnabled = false
            return
        }
        val path = imagePaths[index]
        val file = File(path)
        tvTitle.text = "${index + 1}/${imagePaths.size} · ${file.name}"
        ivImage.setImageBitmap(BitmapFactory.decodeFile(path))
        btnPrev.isEnabled = true
        btnNext.isEnabled = true
    }
}

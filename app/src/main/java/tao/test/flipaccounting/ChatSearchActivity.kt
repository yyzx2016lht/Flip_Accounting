package tao.test.flipaccounting

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatSearchActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var rvResults: RecyclerView
    private lateinit var tvEmpty: TextView

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val adapter = SearchResultAdapter()
    private var searchJob: Job? = null

    private val sourceBookName by lazy {
        intent.getStringExtra(ChatActivity.EXTRA_SOURCE_BOOK)?.trim().orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_search)

        etSearch = findViewById(R.id.et_chat_search)
        rvResults = findViewById(R.id.rv_search_results)
        tvEmpty = findViewById(R.id.tv_search_empty)

        findViewById<View>(R.id.btn_search_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_search_confirm).setOnClickListener {
            doSearch(etSearch.text.toString().trim())
        }

        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val kw = s?.toString()?.trim().orEmpty()
                searchJob?.cancel()
                if (kw.isEmpty()) {
                    adapter.submitList(emptyList())
                    tvEmpty.visibility = View.GONE
                    return
                }
                searchJob = lifecycleScope.launch {
                    delay(300)
                    doSearch(kw)
                }
            }
        })

        etSearch.requestFocus()
    }

    private fun doSearch(keyword: String) {
        if (keyword.isEmpty()) return
        lifecycleScope.launch {
            val results = if (sourceBookName.isNotEmpty()) {
                db.chatMessageDao().searchByBook(sourceBookName, "%$keyword%")
            } else {
                db.chatMessageDao().search("%$keyword%")
            }
            tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            rvResults.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
            adapter.submitList(results)
        }
    }

    inner class SearchResultAdapter : RecyclerView.Adapter<SearchResultAdapter.VH>() {
        private var list: List<ChatMessage> = emptyList()

        fun submitList(newList: List<ChatMessage>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_search_result, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = list.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(list[position])
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvSender: TextView = v.findViewById(R.id.tv_search_sender)
            private val tvTime: TextView = v.findViewById(R.id.tv_search_time)
            private val tvContent: TextView = v.findViewById(R.id.tv_search_content)

            fun bind(msg: ChatMessage) {
                val isUser = msg.msgType in listOf(
                    ChatActivity.MSG_TYPE_USER_TEXT,
                    ChatActivity.MSG_TYPE_USER_IMAGE,
                    ChatActivity.MSG_TYPE_USER_VOICE
                )
                tvSender.text = if (isUser) "我" else Prefs.getAiChatName(this@ChatSearchActivity)
                tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
                tvContent.text = when (msg.msgType) {
                    ChatActivity.MSG_TYPE_USER_IMAGE -> "[图片]"
                    ChatActivity.MSG_TYPE_USER_VOICE -> "[语音]"
                    ChatActivity.MSG_TYPE_AI_BILL -> "[账单记录]"
                    else -> msg.content.trim().ifBlank { "(空内容)" }.take(100)
                }

                itemView.setOnClickListener { navigateToChat(msg) }
            }
        }
    }

    private fun navigateToChat(msg: ChatMessage) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("scroll_to_msg_id", msg.id)
            val targetBook = if (msg.bookName.isNotBlank()) msg.bookName else sourceBookName
            if (targetBook.isNotBlank()) putExtra(ChatActivity.EXTRA_SOURCE_BOOK, targetBook)
            if (msg.conversationId.isNotBlank()) putExtra(ChatActivity.EXTRA_CONVERSATION_ID, msg.conversationId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }
}

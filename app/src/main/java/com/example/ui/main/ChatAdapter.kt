package com.example.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_MYRA = 2
    }

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(message: ChatMessage) {
        // Deduplicate MYRA messages if identical to last one
        if (!message.isUser && messages.isNotEmpty()) {
            val last = messages.last()
            if (!last.isUser && last.text.trim() == message.text.trim()) {
                return
            }
        }
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun lastMyraText(): String? {
        return messages.lastOrNull { !it.isUser }?.text
    }

    fun clearMessages() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_MYRA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = inflater.inflate(R.layout.item_chat_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_chat_myra, parent, false)
            MyraViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is UserViewHolder) {
            holder.text.text = msg.text
        } else if (holder is MyraViewHolder) {
            holder.text.text = msg.text
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.chatUserText)
    }

    class MyraViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.chatMyraText)
    }
}

package com.example.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.model.PrimeContact

class PrimeContactAdapter(
    private val contacts: MutableList<PrimeContact>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<PrimeContactAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_prime_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]
        holder.name.text = contact.name
        holder.number.text = contact.number
        holder.deleteBtn.setOnClickListener {
            onDelete(position)
        }
    }

    override fun getItemCount(): Int = contacts.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.primeItemName)
        val number: TextView = view.findViewById(R.id.primeItemNumber)
        val deleteBtn: ImageButton = view.findViewById(R.id.primeItemDelete)
    }
}

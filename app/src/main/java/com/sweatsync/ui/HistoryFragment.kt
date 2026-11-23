package com.sweatsync.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sweatsync.R
import com.sweatsync.data.ActivityStore

/** The Activities tab: previous workouts, newest first. */
class HistoryFragment : Fragment(R.layout.fragment_history) {

    private lateinit var adapter: ActivityAdapter
    private lateinit var list: View
    private lateinit var tvEmpty: View

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = ActivityAdapter { item ->
            startActivity(Intent(requireContext(), ActivityDetailActivity::class.java).putExtra(ActivityDetailActivity.EXTRA_ID, item.id))
        }
        list = view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@HistoryFragment.adapter
        }
        tvEmpty = view.findViewById(R.id.tvEmpty)
    }

    override fun onResume() {
        super.onResume()
        val items = ActivityStore(requireContext()).all()
        adapter.submitList(items)
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }
}

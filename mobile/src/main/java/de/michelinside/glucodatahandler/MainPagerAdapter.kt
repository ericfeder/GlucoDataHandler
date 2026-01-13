package de.michelinside.glucodatahandler

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class MainPagerAdapter : RecyclerView.Adapter<MainPagerAdapter.PageViewHolder>() {

    companion object {
        const val PAGE_MAIN = 0
        const val PAGE_STATISTICS = 1
        const val PAGE_COUNT = 2
    }

    private var mainPageView: View? = null
    private var statisticsPageView: View? = null

    class PageViewHolder(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val layoutId = when (viewType) {
            PAGE_MAIN -> R.layout.page_main
            PAGE_STATISTICS -> R.layout.page_statistics
            else -> R.layout.page_main
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        when (position) {
            PAGE_MAIN -> mainPageView = holder.view
            PAGE_STATISTICS -> statisticsPageView = holder.view
        }
    }

    override fun getItemCount(): Int = PAGE_COUNT

    override fun getItemViewType(position: Int): Int = position

    fun getMainPageView(): View? = mainPageView
    fun getStatisticsPageView(): View? = statisticsPageView
}











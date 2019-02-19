package com.woocommerce.android.ui.dashboard

import android.content.Context
import android.os.Handler
import android.support.annotation.StringRes
import android.support.design.widget.TabLayout
import android.support.v4.content.ContextCompat
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis.XAxisPosition
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.woocommerce.android.R
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.analytics.AnalyticsTracker.Stat
import com.woocommerce.android.tools.SelectedSite
import com.woocommerce.android.ui.dashboard.DashboardFragment.Companion.DEFAULT_STATS_GRANULARITY
import com.woocommerce.android.ui.dashboard.DashboardStatsMarkerView.RequestMarkerCaptionListener
import com.woocommerce.android.util.DateUtils
import com.woocommerce.android.util.FormatCurrencyRounded
import com.woocommerce.android.util.WooAnimUtils
import com.woocommerce.android.util.WooAnimUtils.Duration
import com.woocommerce.android.widgets.SkeletonView
import kotlinx.android.synthetic.main.dashboard_stats.view.*
import org.wordpress.android.fluxc.model.WCOrderStatsModel
import org.wordpress.android.fluxc.network.rest.wpcom.wc.orderstats.OrderStatsRestClient.OrderStatsApiUnit
import org.wordpress.android.fluxc.store.WCStatsStore.StatsGranularity
import org.wordpress.android.fluxc.store.WCStatsStore.StatsGranularity.YEARS
import org.wordpress.android.fluxc.utils.SiteUtils
import org.wordpress.android.util.DateTimeUtils
import java.util.ArrayList
import java.util.Date

class DashboardStatsView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null)
    : LinearLayout(ctx, attrs), RequestMarkerCaptionListener {
    init {
        View.inflate(context, R.layout.dashboard_stats, this)
    }

    companion object {
        private const val UPDATE_DELAY_TIME_MS = 60 * 1000L
    }

    /**
     * This variable is used to get the granularity for the currently
     * selected TAB.
     *
     * If the currently selected TAB is a custom tab, then the [StatsGranularity]
     * from the [WCOrderStatsModel] is returned.
     *
     * If the currently selected TAB, is default tab, then the [StatsGranularity]
     * from the tag of the [TabLayout] is returned.
     * */
    var activeGranularity: StatsGranularity = DEFAULT_STATS_GRANULARITY
        get() {
            return if (isCustomTab()) {
                wcOrderStatsModel?.let {
                    wcOrderStatsModel?.unit?.let { StatsGranularity.fromString(it) }
                } ?: DEFAULT_STATS_GRANULARITY
            } else {
                tab_layout.getTabAt(tab_layout.selectedTabPosition)?.let {
                    it.tag as StatsGranularity
                } ?: DEFAULT_STATS_GRANULARITY
            }
        }

    private lateinit var selectedSite: SelectedSite

    private lateinit var formatCurrencyForDisplay: FormatCurrencyRounded

    private var chartRevenueStats = mapOf<String, Double>()
    private var chartCurrencyCode: String? = null
    var wcOrderStatsModel: WCOrderStatsModel? = null

    private var skeletonView = SkeletonView()

    private lateinit var lastUpdatedRunnable: Runnable
    private var lastUpdatedHandler: Handler? = null
    private var lastUpdated: Date? = null

    private var isRequestingStats = false
        set(value) {
            // if we're requesting chart data we clear the existing data so it doesn't continue
            // to appear, and we remove the chart's empty string so it doesn't briefly show
            // up before the chart data is added once the request completes
            if (value) {
                clearLabelValues()
                chart.setNoDataText(null)
                chart.clear()
            } else {
                // TODO: add a custom empty view
                chart.setNoDataText(context.getString(R.string.dashboard_state_no_data))
            }
        }

    private val fadeHandler = Handler()

    fun initView(
        period: StatsGranularity = DEFAULT_STATS_GRANULARITY,
        listener: DashboardStatsListener,
        selectedSite: SelectedSite,
        formatCurrencyForDisplay: FormatCurrencyRounded,
        customOrderStatsModel: WCOrderStatsModel?
    ) {
        this.selectedSite = selectedSite
        this.wcOrderStatsModel = customOrderStatsModel
        this.formatCurrencyForDisplay = formatCurrencyForDisplay

        StatsGranularity.values().forEach { granularity ->
            val tab = addTab(getStringForGranularity(granularity), granularity)

            // Start with the given time period selected
            if (granularity == period) {
                tab.select()
            }
        }

        /* add a new tab called CUSTOM */
        addTab(R.string.dashboard_custom_stats_tab, customOrderStatsModel)

        tab_layout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showOrHideDateRangeView()

                // Track range change
                AnalyticsTracker.track(
                        Stat.DASHBOARD_MAIN_STATS_DATE,
                        mapOf(AnalyticsTracker.KEY_RANGE to tab.tag.toString().toLowerCase()))

                isRequestingStats = true
                if (isCustomTab()) {
                    button_date_range.setOnClickListener { listener.onRequestDateRangeSelector(wcOrderStatsModel) }
                    listener.onRequestLoadCustomStats(wcOrderStatsModel)
                } else {
                    listener.onRequestLoadStats(tab.tag as StatsGranularity)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        initChart()

        lastUpdatedHandler = Handler()
        lastUpdatedRunnable = Runnable {
            updateRecencyMessage()
            lastUpdatedHandler?.postDelayed(lastUpdatedRunnable, UPDATE_DELAY_TIME_MS)
        }
    }

    override fun onVisibilityChanged(changedView: View?, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            updateRecencyMessage()
        } else {
            lastUpdatedHandler?.removeCallbacks(lastUpdatedRunnable)
        }
    }

    /**
     * Returns a Boolean flag after checking if the [StatsGranularity]
     * passed to the method is from the currently selected tab
     *
     * The assumption is, if the current tab is custom tab, OR
     * if the [StatsGranularity] passed to the method matches the
     * currently selected Tab's tag, then returns true
     */
    fun isActiveTab(granularity: StatsGranularity) = isCustomTab(granularity) || (activeGranularity == granularity)

    /**
     * Returns a Boolean flag after checking if the current tab is is a custom tab and
     * if the currently selected position's granularity == incoming granularity
     */
    private fun isCustomTab(granularity: StatsGranularity) = (isCustomTab() &&
            wcOrderStatsModel?.unit?.let { StatsGranularity.fromString(it) == granularity } ?: true)

    /**
     * Returns a Boolean flag after checking if the currently selected position
     * is a custom tab.
     *
     * The assumption is that the last tab in the [TabLayout]
     * is for CUSTOM tabs.
     */
    fun isCustomTab() = (tab_layout.selectedTabPosition == tab_layout.tabCount - 1)

    /**
     * Adds a new [TabLayout.Tab] to [TabLayout].
     *
     * The title text of the tab is [StatsGranularity] for default tabs
     *
     * The tag of the tab is also set to [StatsGranularity] for default tabs
     * and [WCOrderStatsModel] for custom tabs.
     */
    private fun addTab(text: Int, tagObject: Any?): TabLayout.Tab {
        val tab = tab_layout.newTab().apply {
            setText(text)
            tag = tagObject
        }
        tab_layout.addTab(tab)
        return tab
    }

    fun showSkeleton(show: Boolean) {
        if (show) {
            // inflate the skeleton view and adjust the bar widths based on the granularity
            val inflater = LayoutInflater.from(context)
            val skeleton = inflater.inflate(R.layout.skeleton_dashboard_stats, chart_container, false) as ViewGroup
            val barWidth = getSkeletonBarWidth()
            for (i in 0 until skeleton.childCount) {
                skeleton.getChildAt(i).layoutParams.width = barWidth
            }

            skeletonView.show(chart_container, skeleton, delayed = true)
            dashboard_recency_text.text = null
        } else {
            skeletonView.hide()
        }
    }

    private fun getSkeletonBarWidth(): Int {
        val resId = when (activeGranularity) {
            StatsGranularity.DAYS -> R.dimen.skeleton_bar_chart_bar_width_days
            StatsGranularity.WEEKS -> R.dimen.skeleton_bar_chart_bar_width_weeks
            StatsGranularity.MONTHS -> R.dimen.skeleton_bar_chart_bar_width_months
            StatsGranularity.YEARS -> R.dimen.skeleton_bar_chart_bar_width_years
        }
        return context.resources.getDimensionPixelSize(resId)
    }

    /**
     * One-time chart initialization with settings common to all granularities.
     */
    private fun initChart() {
        with(chart) {
            with(xAxis) {
                position = XAxisPosition.BOTTOM
                setDrawGridLines(false)
                setDrawAxisLine(false)
                granularity = 1f // Don't break x axis values down further than 1 unit of time

                setLabelCount(2, true) // Only show first and last date

                valueFormatter = StartEndDateAxisFormatter()
            }

            axisLeft.isEnabled = false

            with(axisRight) {
                setDrawZeroLine(false)
                setDrawAxisLine(false)
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(context, R.color.wc_border_color)
                setLabelCount(3, true)

                axisMinimum = 0F

                valueFormatter = IAxisValueFormatter { value, _ ->
                    // Only use non-zero values for the axis
                    value.toDouble().takeIf { it > 0 }?.let {
                        formatCurrencyForDisplay(it, chartCurrencyCode.orEmpty())
                    }.orEmpty()
                }
            }

            description.isEnabled = false
            legend.isEnabled = false

            // touch has to be enabled in order to show a marker when a bar is tapped, but we don't want
            // pinch/zoom, drag, or scaling to be enabled
            setTouchEnabled(true)
            setPinchZoom(false)
            isScaleXEnabled = false
            isScaleYEnabled = false
            isDragEnabled = false

            setNoDataTextColor(ContextCompat.getColor(context, R.color.graph_no_data_text_color))
        }

        val markerView = DashboardStatsMarkerView(context, R.layout.dashboard_stats_marker_view)
        markerView.chartView = chart
        markerView.captionListener = this
        chart.marker = markerView
    }

    /**
     * the chart MarkerView relies on this to know what to display when the user taps a chart bar
     */
    override fun onRequestMarkerCaption(entry: Entry): String? {
        val barEntry = entry as BarEntry

        // get the date for this entry
        val dateindex = barEntry.x.toInt()
        val date = if (activeGranularity == YEARS) dateindex.toString() else
            chartRevenueStats.keys.elementAt(dateindex - 1)
        val formattedDate = when (activeGranularity) {
            StatsGranularity.DAYS -> DateUtils.getShortMonthDayString(date)
            StatsGranularity.WEEKS -> DateUtils.getShortMonthDayStringForWeek(date)
            StatsGranularity.MONTHS -> DateUtils.getShortMonthString(date)
            StatsGranularity.YEARS -> date
        }

        // get the revenue for this entry
        val revenue = barEntry.y.toDouble()
        val formattedRevenue = formatCurrencyForDisplay(revenue, chartCurrencyCode.orEmpty())

        // show the date and revenue on separate lines
        return formattedDate + "\n" + formattedRevenue
    }

    /**
     * removes the highlighted value, which in turn removes the marker view
     */
    private fun hideMarker() {
        chart.highlightValue(null)
    }

    fun updateView(
        revenueStats: Map<String, Double>,
        orderStats: Map<String, Int>,
        currencyCode: String?,
        wcOrderStatsModel: WCOrderStatsModel?
    ) {
        chartCurrencyCode = currencyCode
        this.wcOrderStatsModel = wcOrderStatsModel

        val revenue = formatCurrencyForDisplay(revenueStats.values.sum(), currencyCode.orEmpty())
        val orders = orderStats.values.sum().toString()
        fadeInLabelValue(revenue_value, revenue)
        fadeInLabelValue(orders_value, orders)

        if (revenueStats.isEmpty()) {
            clearLastUpdated()
            isRequestingStats = false
            return
        }

        showOrHideDateRangeView()

        val barColors = ArrayList<Int>()
        val normalColor = ContextCompat.getColor(context, R.color.graph_data_color)
        val weekendColor = ContextCompat.getColor(context, R.color.graph_data_color_weekend)
        for (entry in revenueStats) {
            if (activeGranularity == StatsGranularity.DAYS && DateUtils.isWeekend(entry.key)) {
                barColors.add(weekendColor)
            } else {
                barColors.add(normalColor)
            }
        }

        val dataSet = generateBarDataSet(revenueStats).apply {
            colors = barColors
            setDrawValues(false)
            isHighlightEnabled = true
            highLightColor = ContextCompat.getColor(context, R.color.graph_highlight_color)
        }

        val duration = context.resources.getInteger(android.R.integer.config_shortAnimTime)

        with(chart) {
            data = BarData(dataSet)
            animateY(duration)
        }

        hideMarker()
        resetLastUpdated()
        isRequestingStats = false
    }

    fun showErrorView(show: Boolean) {
        isRequestingStats = false
        dashboard_stats_error.visibility = if (show) View.VISIBLE else View.GONE
        chart.visibility = if (show) View.GONE else View.VISIBLE
    }

    fun showVisitorStats(visits: Int) {
        fadeInLabelValue(visitors_value, visits.toString())
    }

    fun showVisitorStatsError() {
        fadeInLabelValue(visitors_value, "?")
    }

    fun clearLabelValues() {
        val color = ContextCompat.getColor(context, R.color.skeleton_color)
        visitors_value.setTextColor(color)
        revenue_value.setTextColor(color)
        orders_value.setTextColor(color)

        visitors_value.setText(R.string.emdash)
        revenue_value.setText(R.string.emdash)
        orders_value.setText(R.string.emdash)
    }

    fun updateDateRangeView(
        startDate: String,
        endDate: String,
        granularity: StatsGranularity
    ) {
        wcOrderStatsModel?.let {
            it.startDate = startDate
            it.endDate = endDate
            it.unit = OrderStatsApiUnit.fromStatsGranularity(granularity).toString()
            showOrHideDateRangeView()
        }
    }

    private fun showOrHideDateRangeView() {
        /**
         * Logic to display date range selected by user for custom tabs.
         *
         * View will only be displayed for custom stats at the moment,
         * can modify later to include default stats as well
         */
        wcOrderStatsModel?.let {
            if (isCustomTab()) {
                button_date_range.visibility = View.VISIBLE
                tab_layout_divider_bottom.visibility = View.VISIBLE
                button_date_range.text = String.format("%s  –  %s",
                        DateUtils.getShortDisplayDateString(wcOrderStatsModel?.startDate),
                        DateUtils.getShortDisplayDateString(wcOrderStatsModel?.endDate))
            } else {
                button_date_range.visibility = View.GONE
                tab_layout_divider_bottom.visibility = View.GONE
            }
        }
    }

    private fun fadeInLabelValue(view: TextView, value: String) {
        // fade out the current value
        val duration = Duration.SHORT
        WooAnimUtils.fadeOut(view, duration, View.INVISIBLE)

        // fade in the new value after fade out finishes
        val delay = duration.toMillis(context) + 100
        fadeHandler.postDelayed({
            val color = ContextCompat.getColor(context, R.color.default_text_title)
            view.setTextColor(color)
            view.text = value
            WooAnimUtils.fadeIn(view, duration)
        }, delay)
    }

    private fun generateBarDataSet(revenueStats: Map<String, Double>): BarDataSet {
        val barEntries = when (activeGranularity) {
            StatsGranularity.DAYS,
            StatsGranularity.WEEKS,
            StatsGranularity.MONTHS -> {
                chartRevenueStats = revenueStats
                chartRevenueStats.values.mapIndexed { index, value ->
                    BarEntry((index + 1).toFloat(), value.toFloat())
                }
            }
            StatsGranularity.YEARS -> {
                // Clean up leading empty years and start from first year with non-zero sales
                // (but always include current year)
                val modifiedRevenueStats = revenueStats.toMutableMap()
                for (entry in revenueStats) {
                    if (entry.value != 0.0 || entry.key == revenueStats.keys.last()) {
                        break
                    }
                    modifiedRevenueStats.remove(entry.key)
                }
                chartRevenueStats = modifiedRevenueStats
                chartRevenueStats.map { BarEntry(it.key.toFloat(), it.value.toFloat()) }
            }
        }

        return BarDataSet(barEntries, "")
    }

    @StringRes
    fun getStringForGranularity(timeframe: StatsGranularity): Int {
        return when (timeframe) {
            StatsGranularity.DAYS -> R.string.dashboard_stats_granularity_days
            StatsGranularity.WEEKS -> R.string.dashboard_stats_granularity_weeks
            StatsGranularity.MONTHS -> R.string.dashboard_stats_granularity_months
            StatsGranularity.YEARS -> R.string.dashboard_stats_granularity_years
        }
    }

    private fun clearLastUpdated() {
        lastUpdated = null
        updateRecencyMessage()
    }

    private fun resetLastUpdated() {
        lastUpdated = Date()
        updateRecencyMessage()
    }

    private fun updateRecencyMessage() {
        dashboard_recency_text.text = getRecencyMessage()
        lastUpdatedHandler?.removeCallbacks(lastUpdatedRunnable)

        if (lastUpdated != null) {
            lastUpdatedHandler?.postDelayed(lastUpdatedRunnable, UPDATE_DELAY_TIME_MS)
        }
    }

    /**
     * Returns the text to use for the "recency message" which tells the user when stats were last updated
     */
    private fun getRecencyMessage(): String? {
        if (lastUpdated == null) {
            return null
        }

        val now = Date()

        // up to 2 minutes -> "Updated moments ago"
        val minutes = DateTimeUtils.minutesBetween(now, lastUpdated)
        if (minutes <= 2) {
            return context.getString(R.string.dashboard_stats_updated_now)
        }

        // up to 59 minutes -> "Updated 5 minutes ago"
        if (minutes <= 59) {
            return String.format(context.getString(R.string.dashboard_stats_updated_minutes), minutes)
        }

        // 1 hour -> "Updated 1 hour ago"
        val hours = DateTimeUtils.hoursBetween(now, lastUpdated)
        if (hours == 1) {
            return context.getString(R.string.dashboard_stats_updated_one_hour)
        }

        // up to 23 hours -> "Updated 5 hours ago"
        if (hours <= 23) {
            return String.format(context.getString(R.string.dashboard_stats_updated_hours), hours)
        }

        // up to 47 hours -> "Updated 1 day ago"
        if (hours <= 47) {
            return context.getString(R.string.dashboard_stats_updated_one_day)
        }

        // otherwise date & time
        val dateStr = DateFormat.getDateFormat(context).format(lastUpdated)
        val timeStr = DateFormat.getTimeFormat(context).format(lastUpdated)
        return String.format(context.getString(R.string.dashboard_stats_updated_date_time), "$dateStr $timeStr")
    }

    private inner class StartEndDateAxisFormatter : IAxisValueFormatter {
        override fun getFormattedValue(value: Float, axis: AxisBase): String {
            return when (value) {
                axis.mEntries.first() -> getStartValue()
                axis.mEntries.max() -> getEndValue()
                else -> ""
            }
        }

        fun getStartValue(): String {
            val dateString = chartRevenueStats.keys.first()
            return when (activeGranularity) {
                StatsGranularity.DAYS -> DateUtils.getShortMonthDayString(dateString)
                StatsGranularity.WEEKS -> DateUtils.getShortMonthDayStringForWeek(dateString)
                StatsGranularity.MONTHS -> DateUtils.getShortMonthString(dateString)
                StatsGranularity.YEARS -> dateString
            }
        }

        fun getEndValue(): String {
            val dateString = chartRevenueStats.keys.last()
            return when (activeGranularity) {
                StatsGranularity.DAYS -> DateUtils.getShortMonthDayString(dateString)
                StatsGranularity.WEEKS ->
                    SiteUtils.getCurrentDateTimeForSite(selectedSite.get(), DateUtils.friendlyMonthDayFormat)
                StatsGranularity.MONTHS -> DateUtils.getShortMonthString(dateString)
                StatsGranularity.YEARS -> dateString
            }
        }
    }
}

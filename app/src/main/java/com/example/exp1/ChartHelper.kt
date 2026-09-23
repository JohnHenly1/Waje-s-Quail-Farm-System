package com.example.exp1

import android.graphics.Color
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter

data class ChartSlice(val label: String, val value: Int, val colorHex: String)

object ChartHelper {
    fun configure(chart: PieChart) {
        chart.setUsePercentValues(true)
        chart.description.isEnabled = false
        chart.setExtraOffsets(12f, 12f, 12f, 12f)
        chart.setDrawHoleEnabled(true)
        chart.setHoleColor(Color.WHITE)
        chart.transparentCircleRadius = 45f
        chart.legend.isEnabled = true
        chart.legend.textColor = Color.BLACK
        chart.setDrawEntryLabels(false)
        chart.setTouchEnabled(false)
    }

    fun populate(chart: PieChart, slices: List<ChartSlice>) {
        val entries = slices.map { PieEntry(it.value.toFloat(), it.label) }
        val dataSet = PieDataSet(entries, "")
        dataSet.colors = slices.map { Color.parseColor(it.colorHex) }
        dataSet.sliceSpace = 3f
        dataSet.valueTextColor = Color.BLACK
        dataSet.valueTextSize = 12f
        val data = PieData(dataSet)
        data.setValueFormatter(PercentFormatter(chart))
        chart.data = data
        chart.invalidate()
        chart.animateY(700)
    }
}
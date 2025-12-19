package com.roubao.autopilot.controller

import android.util.Log

/**
 * 手势导航控制器
 * 负责全屏手势导航的实现（Home、Back、Recents）
 */
class GestureController(
    private val execCommand: (String) -> String,
    private val getScreenSize: () -> Pair<Int, Int>
) {
    companion object {
        private const val TAG = "GestureController"
    }

    /**
     * Home 手势 - 从底部中间往上快速滑动
     */
    fun homeGesture() {
        val (width, height) = getScreenSize()
        val startX = width / 2
        val startY = height - 50  // 底部白条位置
        val endX = width / 2
        val endY = height / 2     // 滑到屏幕中间
        execCommand("input swipe $startX $startY $endX $endY 150")  // 150ms 快速滑动
        Log.d(TAG, "home() via gesture ($startX,$startY -> $endX,$endY)")
    }

    /**
     * Back 手势 - 从左侧边缘往右滑动
     */
    fun backGesture() {
        val (_, height) = getScreenSize()
        val startX = 10           // 左侧边缘
        val startY = height / 2   // 屏幕中间高度
        val endX = 300            // 往右滑动
        val endY = height / 2
        execCommand("input swipe $startX $startY $endX $endY 150")  // 150ms
        Log.d(TAG, "back() via gesture ($startX,$startY -> $endX,$endY)")
    }

    /**
     * 最近任务手势 - 从底部往上滑动并停顿
     */
    fun recentsGesture() {
        val (width, height) = getScreenSize()
        val startX = width / 2
        val startY = height - 50
        val endX = width / 2
        val endY = height / 3     // 滑到屏幕上方 1/3 处
        execCommand("input swipe $startX $startY $endX $endY 500")  // 500ms 慢速滑动
        Log.d(TAG, "recents() via gesture ($startX,$startY -> $endX,$endY)")
    }
}

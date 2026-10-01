package acr.browser.lightning.extensions

import android.app.ActivityManager

/**
 * The device's total RAM in Gigabytes.
 */
fun ActivityManager.totalMemory(): Long = ActivityManager.MemoryInfo().let { memoryInfo ->
    getMemoryInfo(memoryInfo)
    (memoryInfo.totalMem / 1000000000)
}

/**
 * Defaults to one tab per GB of total RAM, with a floor of at least 4 active tabs.
 * 低配机（总内存 ≤ 2GB）上限压到 2：并发 WebView 越多，内存/渲染压力越大，
 * 巨型页面在弱机上最容易把渲染进程烧爆 → 拖死显示管线。功能机场景 2 个窗口足够。
 */
fun ActivityManager.activeTabLimit(): Int =
    totalMemory().let { gb -> if (gb <= 2) 2 else gb.coerceAtLeast(4).toInt() }

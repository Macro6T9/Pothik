package com.pothik.browser

import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.webkit.WebView

/**
 * পথিকের UI (ট্যাব, ঠিকানা বার, বাটন) এই স্বচ্ছ WebView-তে আঁকা হয়।
 * এর নিচে আসল সাইটের WebView থাকে। "holes" এলাকায় ছোঁয়া দিলে ছোঁয়াটা নিচের
 * সাইট WebView-তে চলে যায়; কোনো মেনু/শিট খোলা থাকলে সব ছোঁয়া UI নিজেই নেয়।
 */
class PassThroughWebView(context: Context) : WebView(context) {

    @Volatile var holes: List<RectF> = emptyList()
    @Volatile var overlayOpen: Boolean = false
    private var passing = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x
            val y = event.y
            passing = !overlayOpen && holes.any { it.contains(x, y) }
        }
        if (passing) return false
        return super.dispatchTouchEvent(event)
    }
}

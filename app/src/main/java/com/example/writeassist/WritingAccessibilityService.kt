package com.example.writeassist

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

class WritingAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: WritingAccessibilityService? = null
        @Volatile var capturedText: String? = null
        @Volatile var capturedPackage: String? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var bubble: LinearLayout? = null
    private var title: TextView? = null
    private var suggestion: TextView? = null
    private var lastText = ""
    private var lastPackage = ""
    private var lastNode: AccessibilityNodeInfo? = null
    private val refresh = Runnable { updateFromFocus() }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    override fun onServiceConnected() {
        instance = this
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) {
            hideBubble()
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 450)
        }
    }

    private fun editableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused?.isEditable == true) return focused
        focused?.recycle()
        return null
    }

    private fun isSensitive(node: AccessibilityNodeInfo): Boolean {
        val input = node.inputType
        val variation = input and InputType.TYPE_MASK_VARIATION
        val klass = input and InputType.TYPE_MASK_CLASS
        return node.isPassword ||
            (klass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) ||
            (klass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun updateFromFocus() {
        val node = editableNode()
        if (node == null) { hideBubble(); return }
        val pkg = node.packageName?.toString() ?: ""
        val value = node.text?.toString() ?: ""
        val sensitive = isSensitive(node)
        node.recycle()
        if (pkg.isBlank() || pkg == packageName || sensitive ||
            value.isBlank() || value.length > 2500) {
            hideBubble()
            return
        }
        lastText = value
        lastPackage = pkg
        showBubble()
        val corrected = offlineSuggestion(value)
        title?.text = if (corrected != null) "✦  Writing suggestion" else "✦  WriteAssist"
        suggestion?.apply {
            visibility = if (corrected == null) View.GONE else View.VISIBLE
            text = if (corrected == null) "" else "Fix: " + corrected.take(100)
        }
    }

    private fun offlineSuggestion(text: String): String? {
        var revised = text
        val fixes = listOf(
            "teh" to "the", "adn" to "and", "recieve" to "receive",
            "recieved" to "received", "definately" to "definitely",
            "seperate" to "separate", "occured" to "occurred",
            "grammer" to "grammar"
        )
        for ((wrong, right) in fixes) {
            revised = revised.replace(Regex("\\b" + wrong + "\\b", RegexOption.IGNORE_CASE)) {
                if (it.value.firstOrNull()?.isUpperCase() == true) right.replaceFirstChar { ch -> ch.uppercase() }
                else right
            }
        }
        revised = revised.replace(Regex(" {2,}"), " ")
        revised = revised.replace(Regex("\\b([A-Za-z]{2,})\\s+\\1\\b", RegexOption.IGNORE_CASE), "$1")
        revised = revised.replace(Regex("\\bi\\b"), "I")
        return revised.takeIf { it != text }
    }

    private fun showBubble() {
        if (bubble != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(255, 255, 255))
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        val heading = TextView(this).apply {
            text = "✦  WriteAssist"
            textSize = 15f
            setTextColor(Color.rgb(21, 148, 96))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        box.addView(heading)
        title = heading
        val fix = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(31, 41, 55))
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
            setOnClickListener {
                val corrected = offlineSuggestion(lastText) ?: return@setOnClickListener
                val node = editableNode() ?: return@setOnClickListener
                try {
                    if (node.isEditable && !isSensitive(node) &&
                        node.packageName?.toString() == lastPackage &&
                        node.text?.toString() == lastText) {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, corrected)
                        }
                        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                            lastText = corrected
                            updateFromFocus()
                        }
                    }
                } finally { node.recycle() }
            }
        }
        box.addView(fix)
        suggestion = fix
        box.addView(TextView(this).apply {
            text = "Check with AI  ›"
            textSize = 13f
            setTextColor(Color.rgb(21, 148, 96))
            setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                val node = editableNode() ?: return@setOnClickListener
                val safe = node.isEditable && !isSensitive(node) &&
                    node.packageName?.toString() == lastPackage &&
                    node.text?.toString() == lastText
                node.recycle()
                if (!safe) { hideBubble(); return@setOnClickListener }
                capturedText = lastText
                capturedPackage = lastPackage
                hideBubble()
                startActivity(Intent(this@WritingAccessibilityService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("accessibility_text", lastText))
            }
        })
        val params = WindowManager.LayoutParams(
            dp(245), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(115)
        }
        try {
            manager?.addView(box, params)
            bubble = box
        } catch (_: Exception) {
            bubble = null
            title = null
            suggestion = null
        }
    }

    private fun hideBubble() {
        bubble?.let { try { manager?.removeView(it) } catch (_: Exception) {} }
        bubble = null
        title = null
        suggestion = null
    }

    fun applyReplacement(original: String, replacement: String, originalPackage: String): Boolean {
        val node = editableNode() ?: return false
        return try {
            if (!node.isEditable || isSensitive(node) ||
                node.text?.toString() != original ||
                node.packageName?.toString() != originalPackage || replacement.isBlank()) return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally { node.recycle() }
    }

    override fun onInterrupt() { hideBubble() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideBubble()
        instance = null
        super.onDestroy()
    }
}

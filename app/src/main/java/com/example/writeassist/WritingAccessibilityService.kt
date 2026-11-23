package com.example.writeassist

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.content.Intent
import android.text.InputType

class WritingAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: WritingAccessibilityService? = null
        @Volatile var capturedText: String? = null
        @Volatile var capturedPackage: String? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var manager: WindowManager? = null
    private var lastText = ""
    private var lastPackage = ""
    private val refresh = Runnable { updateFromFocus() }

    override fun onServiceConnected() {
        instance = this
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) { hideBubble(); return }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 700)
        }
    }
    private fun editableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus?.isEditable == true) return focus
        focus?.recycle()
        return null
    }
    private fun updateFromFocus() {
        val node = editableNode()
        if (node == null) { hideBubble(); return }
        val pkg = node.packageName?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val cls = node.className?.toString() ?: ""
        val isPassword = node.isPassword ||
            (node.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 ||
            (node.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) != 0 ||
            (node.inputType and InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) != 0 ||
            (node.inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0
        node.recycle()
        if (pkg == packageName || isPassword || cls.contains("Password", true) ||
            text.isBlank() || text.length > 2500 || pkg.isBlank()) {
            hideBubble(); return
        }
        lastText = text
        lastPackage = pkg
        showBubble()
    }
    private fun showBubble() {
        if (bubble != null) return
        val view = TextView(this).apply {
            text = "✎ Check writing"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(22, 14, 22, 14)
            background = GradientDrawable().apply {
                setColor(Color.rgb(25, 93, 155))
                cornerRadius = 60f
            }
            setOnClickListener {
                val current = editableNode()
                val currentText = current?.text?.toString()
                val currentPkg = current?.packageName?.toString()
                val safe = current?.isPassword != true && current?.isEditable == true
                current?.recycle()
                if (!safe || currentText != lastText || currentPkg != lastPackage) {
                    hideBubble(); return@setOnClickListener
                }
                capturedText = lastText
                capturedPackage = lastPackage
                hideBubble()
                val intent = Intent(this@WritingAccessibilityService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("accessibility_text", lastText)
                startActivity(intent)
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 160 }
        try { manager?.addView(view, params); bubble = view } catch (_: Exception) { bubble = null }
    }
    private fun hideBubble() {
        bubble?.let { try { manager?.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }
    fun applyReplacement(original: String, replacement: String, originalPackage: String): Boolean {
        val node = editableNode() ?: return false
        return try {
            val current = node.text?.toString()
            val pkg = node.packageName?.toString()
            if (!node.isEditable || node.isPassword || current != original ||
                pkg != originalPackage || replacement.isBlank()) return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) { capturedText = null; capturedPackage = null; hideBubble() }
            ok
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

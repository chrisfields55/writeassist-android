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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

class WritingAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: WritingAccessibilityService? = null
        @Volatile var capturedText: String? = null
        @Volatile var capturedPackage: String? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var lastEditableEventNode: AccessibilityNodeInfo? = null
    private var bubble: LinearLayout? = null
    private var title: TextView? = null
    private var suggestion: TextView? = null
    private var originalPreview: TextView? = null
    private var applyButton: TextView? = null
    private var checkButton: TextView? = null
    private var feedback: TextView? = null
    private var suggestionOrigin = ""
    private var dismissedKey = ""
    private var expanded = false
    private var activeEdits: List<SuggestionDiff.Edit> = emptyList()
    private var editIndex = 0
    private var dismissButton: TextView? = null
    private var nextButton: TextView? = null
    private var previousButton: TextView? = null
    private var lastText = ""
    private var lastPackage = ""
    private val aiWorker = Executors.newSingleThreadExecutor()
    private var revision: String? = null
    private var revisionSource = ""
    private var revisionPackage = ""
    private var generation = 0
    private var lastRequestedKey = ""
    private var lostFocusSince = 0L
    private var overlayTheme = ""
    private val autoCheck = Runnable { requestAiReview() }
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
        if (event.packageName?.toString() == packageName) return
        if (event.packageName?.toString() == "com.android.systemui") return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                val source = event.source
                if (source?.isEditable == true && !isSensitive(source)) {
                    lastEditableEventNode?.recycle()
                    lastEditableEventNode = AccessibilityNodeInfo.obtain(source)
                }
                source?.recycle()
            }
            handler.removeCallbacks(refresh)
            handler.removeCallbacks(autoCheck)
            handler.postDelayed(refresh, if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) 250 else 450)
        }
    }

    private fun editableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused?.isEditable == true && !isSensitive(focused)) return focused
        focused?.recycle()
        val cached = lastEditableEventNode ?: return null
        return try {
            if (!cached.refresh() || !cached.isEditable || isSensitive(cached)) null
            else AccessibilityNodeInfo.obtain(cached)
        } catch (_: Exception) { null }
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
        if (node == null) {
            if (lostFocusSince == 0L) lostFocusSince = android.os.SystemClock.uptimeMillis()
            if (android.os.SystemClock.uptimeMillis() - lostFocusSince > 1200L) hideBubble()
            else handler.postDelayed(refresh, 550)
            return
        }
        lostFocusSince = 0L
        val pkg = node.packageName?.toString() ?: ""
        val value = node.text?.toString() ?: ""
        val sensitive = isSensitive(node)
        node.recycle()
        if (pkg.isBlank() || pkg == packageName || sensitive ||
            value.isBlank() || value.length > 2500) {
            hideBubble()
            return
        }
        if (value != lastText || pkg != lastPackage) {
            generation++
            revision = null
            lastRequestedKey = ""
            dismissedKey = ""
            expanded = false
        }
        lastText = value
        lastPackage = pkg
        val corrected = revision?.takeIf { revisionSource == value && revisionPackage == pkg }
            ?: LocalCorrections.correct(value).takeIf { it != value }
        if (corrected != null && dismissedKey != pkg + "|" + value + "|" + corrected) {
            showBubble()
            renderSuggestion(corrected, if (revision != null) suggestionOrigin else "Offline")
        } else hideBubble()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        handler.removeCallbacks(autoCheck)
        if (prefs.getBoolean("auto_ai", true) && value.length in 12..1500 && lastRequestedKey != pkg + "|" + value) {
            handler.postDelayed(autoCheck, 1800)
        }
    }

    private fun requestAiReview(force: Boolean = false) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!force && !prefs.getBoolean("auto_ai", true)) return
        val source = lastText
        val pkg = lastPackage
        val requestGeneration = generation
        if (source.length !in 12..1500 || pkg.isBlank()) return
        val requestKey = pkg + "|" + source
        if (!force && lastRequestedKey == requestKey) return
        lastRequestedKey = requestKey
        checkButton?.text = "Checking with Gemini…"
        feedback?.text = ""
        aiWorker.execute {
            val result = GeminiFallback.review(this, source,
                "Correct spelling, grammar, punctuation, sentence structure, and natural flow.")
            handler.post {
                if (generation != requestGeneration || lastText != source ||
                    lastPackage != pkg || (!force && !prefs.getBoolean("auto_ai", true))) return@post
                val node = editableNode() ?: return@post
                val stillSafe = !isSensitive(node) && node.isEditable &&
                    node.text?.toString() == source && node.packageName?.toString() == pkg
                node.recycle()
                if (!stillSafe) return@post
                checkButton?.text = "Check with AI again  ›"
                if (result.text != source && result.text.isNotBlank()) {
                    revision = result.text
                    revisionSource = source
                    revisionPackage = pkg
                    suggestionOrigin = result.source
                    if (dismissedKey != pkg + "|" + source + "|" + result.text) {
                        showBubble()
                        renderSuggestion(result.text, result.source)
                    }
                    feedback?.text = if (result.source == "Local") result.notice else ""
                } else {
                    hideBubble()
                }
            }
        }
    }

    private fun renderSuggestion(corrected: String?, origin: String) {
        val changed = !corrected.isNullOrBlank() && corrected != lastText
        if (!changed) { hideBubble(); return }
        activeEdits = SuggestionDiff.between(lastText, corrected!!)
        if (activeEdits.isEmpty()) { hideBubble(); return }
        editIndex = editIndex.coerceIn(0, activeEdits.lastIndex)
        val edit = activeEdits[editIndex]
        title?.text = if (expanded) "✦  Suggestion " + (editIndex + 1) + " of " + activeEdits.size
            else "✦  " + activeEdits.size + " writing suggestion" + if (activeEdits.size == 1) "" else "s"
        originalPreview?.visibility = if (expanded) View.VISIBLE else View.GONE
        originalPreview?.text = "Original: " + edit.before.ifBlank { "(insert)" }.take(350)
        suggestion?.visibility = if (expanded) View.VISIBLE else View.GONE
        suggestion?.text = "Replace with: " + edit.after.ifBlank { "(remove)" }.take(450) +
            "\n" + origin
        applyButton?.visibility = if (expanded) View.VISIBLE else View.GONE
        dismissButton?.visibility = if (expanded) View.VISIBLE else View.GONE
        checkButton?.visibility = if (expanded) View.VISIBLE else View.GONE
        nextButton?.visibility = if (expanded && activeEdits.size > 1) View.VISIBLE else View.GONE
        previousButton?.visibility = if (expanded && activeEdits.size > 1) View.VISIBLE else View.GONE
        title?.setOnClickListener {
            expanded = !expanded
            renderSuggestion(corrected, origin)
        }
    }

    private fun renderCurrentSuggestion() {
        val corrected = revision?.takeIf { revisionSource == lastText && revisionPackage == lastPackage }
            ?: LocalCorrections.correct(lastText).takeIf { it != lastText }
        if (corrected != null) renderSuggestion(corrected, if (revision != null) suggestionOrigin else "Offline")
    }

    private fun applySuggestion() {
        val corrected = revision?.takeIf {
            revisionSource == lastText && revisionPackage == lastPackage
        } ?: LocalCorrections.correct(lastText).takeIf { it != lastText }
        if (corrected.isNullOrBlank()) { hideBubble(); return }
        val edit = activeEdits.getOrNull(editIndex) ?: return
        val original = lastText
        val pkg = lastPackage
        val replacement = SuggestionDiff.apply(original, edit)
        if (applyReplacement(original, replacement, pkg)) {
            revision = null
            lastRequestedKey = pkg + "|" + replacement
            lastText = replacement
            expanded = false
            hideBubble()
            handler.postDelayed(refresh, 350)
        } else {
            feedback?.text = "Direct replacement is not supported in this text field."
        }
    }

    private fun dismissSuggestion() {
        val corrected = revision?.takeIf {
            revisionSource == lastText && revisionPackage == lastPackage
        } ?: LocalCorrections.correct(lastText).takeIf { it != lastText }
        if (corrected != null) dismissedKey = lastPackage + "|" + lastText + "|" + corrected
        lastRequestedKey = lastPackage + "|" + lastText
        hideBubble()
    }

    private fun showBubble() {
        val dark = AppTheme.isDark(this)
        val theme = if (dark) "dark" else "light"
        if (bubble != null && overlayTheme != theme) hideBubble()
        if (bubble != null) return
        overlayTheme = theme
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (dark) Color.rgb(25, 34, 35) else Color.WHITE)
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
        val foreground = if (dark) Color.rgb(230, 242, 237) else Color.rgb(31, 41, 55)
        val secondary = if (dark) Color.rgb(168, 184, 178) else Color.rgb(100, 116, 139)
        val before = TextView(this).apply {
            textSize = 12f
            setTextColor(secondary)
            setPadding(0, dp(9), 0, dp(5))
            visibility = View.GONE
        }
        box.addView(before)
        originalPreview = before
        val fix = TextView(this).apply {
            textSize = 14f
            setTextColor(foreground)
            setPadding(0, dp(5), 0, dp(9))
            visibility = View.GONE
            maxLines = 12
        }
        box.addView(fix)
        suggestion = fix
        val previous = TextView(this).apply {
            text = "‹ Previous suggestion"
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
            setOnClickListener {
                editIndex = (editIndex - 1 + activeEdits.size) % activeEdits.size
                renderCurrentSuggestion()
            }
        }
        val next = TextView(this).apply {
            text = "Next suggestion ›"
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
            setOnClickListener {
                editIndex = (editIndex + 1) % activeEdits.size
                renderCurrentSuggestion()
            }
        }
        val dismiss = TextView(this).apply {
            text = "Dismiss suggestion"
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, dp(12), 0, dp(4))
            visibility = View.GONE
            setOnClickListener { dismissSuggestion() }
        }
        val apply = TextView(this).apply {
            text = "✓  Apply correction"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(21, 148, 96))
            setPadding(dp(10), dp(12), dp(10), dp(12))
            visibility = View.GONE
            setOnClickListener { applySuggestion() }
        }
        box.addView(apply)
        box.addView(dismiss)
        box.addView(previous)
        box.addView(next)
        previousButton = previous
        nextButton = next
        dismissButton = dismiss
        applyButton = apply
        val check = TextView(this).apply {
            text = "Check with AI  ›"
            textSize = 13f
            setTextColor(Color.rgb(21, 148, 96))
            setPadding(0, dp(12), 0, dp(6))
            setOnClickListener { requestAiReview(force = true) }
        }
        box.addView(check)
        checkButton = check
        check.visibility = View.GONE
        val note = TextView(this).apply {
            textSize = 12f
            setTextColor(secondary)
            setPadding(0, dp(5), 0, 0)
        }
        box.addView(note)
        feedback = note
        val params = WindowManager.LayoutParams(
            dp(310), WindowManager.LayoutParams.WRAP_CONTENT,
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
        overlayTheme = ""
        title = null
        suggestion = null
        originalPreview = null
        applyButton = null
        checkButton = null
        feedback = null
        dismissButton = null
        nextButton = null
        previousButton = null
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
            val oldCursor = node.textSelectionStart.coerceAtLeast(0)
            val changed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (changed) {
                val cursor = oldCursor.coerceAtMost(replacement.length)
                val selection = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
            }
            changed
        } finally { node.recycle() }
    }

    override fun onInterrupt() { hideBubble() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        lastEditableEventNode?.recycle()
        lastEditableEventNode = null
        aiWorker.shutdownNow()
        hideBubble()
        instance = null
        super.onDestroy()
    }
}

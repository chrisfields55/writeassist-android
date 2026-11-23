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
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.content.Context
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
    private var lastTypingEventAt = 0L
    private var eventTextSnapshot: String? = null
    private var eventPackageSnapshot: String? = null
    private var bubble: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var stylePreview: String? = null
    private var styleOriginal = ""
    private var styleName = ""
    private var styleGeneration = 0
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
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val typing = event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        val focus = event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
        if (typing || focus) {
            val source = event.source
            try {
                if (source?.isEditable == true && !isSensitive(source)) {
                    lastEditableEventNode?.recycle()
                    lastEditableEventNode = AccessibilityNodeInfo.obtain(source)
                    if (typing) {
                        eventTextSnapshot = source.text?.toString()
                            ?: event.text.joinToString("")
                        eventPackageSnapshot = pkg
                    }
                } else if (typing && event.text.isNotEmpty()) {
                    eventTextSnapshot = event.text.joinToString("")
                    eventPackageSnapshot = pkg
                }
            } finally { source?.recycle() }
        }
        if (typing) {
            lastTypingEventAt = android.os.SystemClock.uptimeMillis()
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 280)
        } else if (focus) {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 350)
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Frequent content-change events must not indefinitely postpone text checks.
            if (android.os.SystemClock.uptimeMillis() - lastTypingEventAt > 900L) {
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, 500)
            }
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
        if (node == null && !eventTextSnapshot.isNullOrBlank() &&
            android.os.SystemClock.uptimeMillis() - lastTypingEventAt < 2500L) {
            processText(eventPackageSnapshot.orEmpty(), eventTextSnapshot.orEmpty())
            return
        }
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
        if (sensitive) { hideBubble(); return }
        processText(pkg, value)
    }

    private fun processText(pkg: String, value: String) {
        if (pkg.isBlank() || pkg == packageName ||
            value.isBlank() || value.length > 2500) {
            hideBubble()
            return
        }
        if (value != lastText || pkg != lastPackage) {
            styleGeneration++
            stylePreview = null
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
        showBubble()
        if (corrected != null && dismissedKey != pkg + "|" + value + "|" + corrected) {
            renderSuggestion(corrected, if (revision != null) suggestionOrigin else "Offline")
        } else {
            activeEdits = emptyList()
            if (!expanded) title?.text = "W"
            else renderAssistantPanel()
        }
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
                    renderAssistantPanel()
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
            else "W  •  " + activeEdits.size
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
            resizeBubble()
            if (expanded) renderAssistantPanel() else renderSuggestion(corrected, origin)
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

    private fun resizeBubble() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        params.width = dp(if (expanded) 326 else 56)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        try { manager?.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    private fun renderAssistantPanel() {
        val dark = AppTheme.isDark(this)
        val foreground = if (dark) Color.WHITE else Color.rgb(30, 41, 49)
        val muted = if (dark) Color.LTGRAY else Color.DKGRAY
        title?.text = if (expanded) "✦  WriteAssist     ×" else "W"
        title?.gravity = if (expanded) Gravity.START else Gravity.CENTER
        originalPreview?.visibility = View.GONE
        suggestion?.visibility = View.GONE
        applyButton?.visibility = View.GONE
        dismissButton?.visibility = View.GONE
        previousButton?.visibility = View.GONE
        nextButton?.visibility = View.GONE
        checkButton?.visibility = if (expanded) View.VISIBLE else View.GONE
        val panel = bubble ?: return
        while (panel.childCount > 8) panel.removeViewAt(8)
        if (!expanded) { resizeBubble(); return }
        val styles = listOf(
            "Professional" to "Rewrite clearly and professionally, preserving all facts.",
            "Friendly" to "Rewrite in a warm, approachable tone.",
            "Informal" to "Rewrite in relaxed, conversational language.",
            "Formal" to "Rewrite with formal and respectful language.",
            "Concise" to "Make this shorter while preserving the important meaning.",
            "Confident" to "Make this direct and confident without exaggeration.",
            "Emojify" to "Add suitable emojis naturally while keeping the original meaning.",
            "Improve" to "Improve grammar, clarity, and flow without changing the meaning."
        )
        val header = TextView(this).apply {
            text = "Rewrite your text"
            textSize = 15f
            setTextColor(foreground)
            setPadding(0, dp(14), 0, dp(8))
        }
        panel.addView(header)
        for ((name, instruction) in styles) {
            val item = TextView(this).apply {
                text = name + "   ›"
                textSize = 14f
                setTextColor(foreground)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener { requestStyle(name, instruction) }
            }
            panel.addView(item)
        }
        val preview = stylePreview
        if (preview != null) {
            panel.addView(TextView(this).apply {
                text = styleName + " preview:\\n" + preview
                textSize = 14f
                setTextColor(foreground)
                setPadding(0, dp(12), 0, dp(12))
                maxLines = 10
            })
            panel.addView(TextView(this).apply {
                text = "✓  Apply rewrite"
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(21, 148, 96))
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener {
                    if (applyReplacement(styleOriginal, preview, lastPackage)) {
                        stylePreview = null
                        expanded = false
                        handler.postDelayed(refresh, 300)
                    } else feedback?.text = "This editor does not support direct replacement."
                }
            })
        }
        feedback?.setTextColor(muted)
        resizeBubble()
    }

    private fun requestStyle(name: String, instruction: String) {
        val source = lastText
        if (source.isBlank()) { feedback?.text = "Type something first."; return }
        val pkg = lastPackage
        val token = ++styleGeneration
        feedback?.text = "Rewriting as " + name + "…"
        aiWorker.execute {
            val result = GeminiFallback.review(this, source, instruction)
            handler.post {
                if (token != styleGeneration || lastText != source || lastPackage != pkg) return@post
                if (result.source == "Local") {
                    feedback?.text = "AI unavailable. Check your Gemini key and connection."
                } else {
                    styleOriginal = source
                    styleName = name
                    stylePreview = result.text
                    renderAssistantPanel()
                }
            }
        }
    }

    private fun showBubble() {
        val dark = AppTheme.isDark(this)
        val theme = if (dark) "dark" else "light"
        if (bubble != null && overlayTheme != theme) hideBubble()
        if (bubble != null) return
        overlayTheme = theme
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (dark) Color.rgb(30, 38, 39) else Color.WHITE)
            elevation = dp(8).toFloat()
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        val heading = TextView(this).apply {
            text = "W"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(21, 148, 96))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setOnClickListener {
                expanded = !expanded
                if (expanded) renderAssistantPanel() else {
                    renderAssistantPanel()
                    renderCurrentSuggestion()
                }
                resizeBubble()
            }
        }
        box.addView(heading)
        title = heading
        val foreground = if (dark) Color.WHITE else Color.rgb(31, 41, 55)
        val secondary = if (dark) Color.LTGRAY else Color.DKGRAY
        originalPreview = TextView(this).apply {
            textSize = 12f
            setTextColor(secondary)
            visibility = View.GONE
        }.also { box.addView(it) }
        suggestion = TextView(this).apply {
            textSize = 14f
            setTextColor(foreground)
            visibility = View.GONE
        }.also { box.addView(it) }
        applyButton = TextView(this).apply {
            text = "✓  Apply correction"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(21, 148, 96))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            visibility = View.GONE
            setOnClickListener { applySuggestion() }
        }.also { box.addView(it) }
        dismissButton = TextView(this).apply {
            text = "Dismiss suggestion"
            textSize = 13f
            setTextColor(secondary)
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { dismissSuggestion() }
        }.also { box.addView(it) }
        previousButton = TextView(this).apply {
            text = "‹ Previous"
            setTextColor(secondary)
            visibility = View.GONE
            setOnClickListener {
                if (activeEdits.isNotEmpty()) {
                    editIndex = (editIndex - 1 + activeEdits.size) % activeEdits.size
                    renderCurrentSuggestion()
                }
            }
        }.also { box.addView(it) }
        nextButton = TextView(this).apply {
            text = "Next ›"
            setTextColor(secondary)
            visibility = View.GONE
            setOnClickListener {
                if (activeEdits.isNotEmpty()) {
                    editIndex = (editIndex + 1) % activeEdits.size
                    renderCurrentSuggestion()
                }
            }
        }.also { box.addView(it) }
        checkButton = TextView(this).apply {
            text = "Check grammar with AI"
            textSize = 13f
            setTextColor(Color.rgb(21, 148, 96))
            setPadding(0, dp(10), 0, dp(8))
            visibility = View.GONE
            setOnClickListener { requestAiReview(true) }
        }.also { box.addView(it) }
        feedback = TextView(this).apply {
            textSize = 12f
            setTextColor(secondary)
        }.also { box.addView(it) }
        val params = WindowManager.LayoutParams(
            dp(56), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = (resources.displayMetrics.heightPixels * 0.52f).toInt()
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        heading.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!expanded && (kotlin.math.abs(event.rawX - downX) > dp(8) ||
                        kotlin.math.abs(event.rawY - downY) > dp(8))) {
                        params.x = (startX + event.rawX - downX).toInt().coerceAtLeast(0)
                        params.y = (startY + event.rawY - downY).toInt().coerceAtLeast(0)
                        try { manager?.updateViewLayout(box, params) } catch (_: Exception) {}
                        true
                    } else false
                }
                else -> false
            }
        }
        try {
            manager?.addView(box, params)
            bubble = box
            bubbleParams = params
            if (expanded) renderAssistantPanel()
        } catch (_: Exception) { bubble = null; bubbleParams = null }
    }

    private fun hideBubble() {
        bubble?.let { try { manager?.removeView(it) } catch (_: Exception) {} }
        bubble = null
        bubbleParams = null
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
        eventTextSnapshot = null
        aiWorker.shutdownNow()
        hideBubble()
        instance = null
        super.onDestroy()
    }
}

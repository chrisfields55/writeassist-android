package com.example.writeassist

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class WritingAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var editor: AccessibilityNodeInfo? = null
    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val settingsChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            AssistantControls.ENABLED, AssistantControls.NOTIFICATION -> {
                AssistantControls.updateNotification(this)
                updateFromFocus()
            }
            "auto_ai" -> {
                handler.removeCallbacks(autoCheck)
                updateFromFocus()
            }
            "appearance" -> { hideBubble(); updateFromFocus() }
        }
    }
    private var bubble: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelScroll: ScrollView? = null
    private var panelContent: LinearLayout? = null
    private var stylePreview: String? = null
    private var styleOriginal = ""
    private var styleName = ""
    private var styleGeneration = 0
    private var styleBusy = false
    private var title: TextView? = null
    private var checkButton: TextView? = null
    private var feedback: TextView? = null
    private val ignoredSuggestions = mutableSetOf<String>()
    private var reviewTab = true
    private var bubbleX = -1
    private var bubbleY = -1
    private var expanded = false
    private var activeEdits: List<SuggestionDiff.Edit> = emptyList()
    private var lastText = ""
    private var lastPackage = ""
    private val aiWorker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var lastRequestedKey = ""
    private var overlayTheme = ""
    private val autoCheck = Runnable { requestAiReview() }
    private val refresh = Runnable { updateFromFocus() }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    override fun onServiceConnected() {
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        settings.registerOnSharedPreferenceChangeListener(settingsChanged)
        AssistantControls.updateNotification(this)
        updateFromFocus()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> updateFromFocus()
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                {
                    handler.removeCallbacks(refresh)
                    handler.postDelayed(refresh, 180)
                }
        }
    }

    private fun editableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            finally { root.recycle() }
        if (focused == null) return null
        if (focused.isFocused && focused.isEditable && focused.packageName?.toString() != packageName &&
            !isSensitive(focused)) return focused
        focused.recycle()
        return null
    }

    private fun clearSession() {
        editor?.recycle()
        editor = null
        generation++
        styleGeneration++
        styleBusy = false
        stylePreview = null
        lastRequestedKey = ""
        lastText = ""
        lastPackage = ""
        activeEdits = emptyList()
        ignoredSuggestions.clear()
        expanded = false
        handler.removeCallbacks(autoCheck)
        hideBubble()
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
        if (!AssistantControls.enabled(this)) { clearSession(); return }
        val node = editableNode() ?: run { clearSession(); return }
        val pkg = node.packageName?.toString().orEmpty()
        val value = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        if (pkg.isBlank() || value.length > 2500) {
            node.recycle()
            clearSession()
            return
        }
        val changedEditor = node != editor
        if (changedEditor) {
            clearSession()
            editor = node
        } else node.recycle()
        val changed = changedEditor || value != lastText
        if (changed) {
            styleGeneration++
            styleBusy = false
            stylePreview = null
            generation++
            lastRequestedKey = ""
            ignoredSuggestions.clear()
        }
        lastText = value
        lastPackage = pkg
        val needsOverlay = bubble == null
        showBubble()
        if (changed || needsOverlay) {
            activeEdits = LocalCorrections.suggestions(value)
            renderAssistantPanel()
        }
        if (changed || !settings.getBoolean("auto_ai", false)) handler.removeCallbacks(autoCheck)
        if ((changed || needsOverlay) && settings.getBoolean("auto_ai", false) &&
            value.length in 12..2500 && lastRequestedKey != value) {
            handler.postDelayed(autoCheck, 1500)
        }
    }

    private fun requestAiReview(force: Boolean = false) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!force && !prefs.getBoolean("auto_ai", false)) return
        val source = lastText
        val pkg = lastPackage
        val requestGeneration = generation
        if (source.length !in 12..2500 || pkg.isBlank()) return
        val requestKey = source
        if (!force && lastRequestedKey == requestKey) return
        lastRequestedKey = requestKey
        checkButton?.text = "Checking with Gemini…"
        feedback?.text = ""
        aiWorker.execute {
            val result = GeminiFallback.review(this, source,
                "Correct spelling, grammar, punctuation, sentence structure, and natural flow.")
            handler.post {
                if (generation != requestGeneration || lastText != source ||
                    lastPackage != pkg || (!force && !prefs.getBoolean("auto_ai", false))) return@post
                val node = editableNode() ?: run { checkButton?.text = "Check grammar with AI"; return@post }
                val stillSafe = node == editor && !node.isShowingHintText &&
                    node.text?.toString() == source && node.packageName?.toString() == pkg
                node.recycle()
                if (!stillSafe) { checkButton?.text = "Check grammar with AI"; return@post }
                checkButton?.text = "Check with AI again  ›"
                if (result.text != source && result.text.isNotBlank()) {
                    if (result.source != "Local") {
                        showBubble()
                        activeEdits = SuggestionDiff.between(source, result.text)
                        renderAssistantPanel()
                    }
                    feedback?.text = if (result.source == "Local") result.notice else ""
                } else {
                    renderAssistantPanel()
                    feedback?.text = result.notice
                }
            }
        }
    }

    private fun resizeBubble() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        params.width = if (expanded) (resources.displayMetrics.widthPixels - dp(32)).coerceAtMost(dp(430)) else dp(52)
        params.gravity = if (expanded) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.TOP or Gravity.LEFT
        params.x = if (expanded) 0 else bubbleX.coerceIn(0, (resources.displayMetrics.widthPixels - dp(52)).coerceAtLeast(0))
        params.y = if (expanded) dp(12) else bubbleY.coerceIn(0, (resources.displayMetrics.heightPixels - dp(90)).coerceAtLeast(0))
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        try { manager?.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    private fun suggestionId(edit: SuggestionDiff.Edit) = "${edit.start}:${edit.end}:${edit.before}:${edit.after}"

    private fun renderAssistantPanel() {
        val dark = AppTheme.isDark(this)
        val foreground = if (dark) Color.WHITE else Color.rgb(30, 41, 49)
        val muted = if (dark) Color.LTGRAY else Color.DKGRAY
        title?.text = if (expanded) "W   WriteAssist   ×" else activeEdits.count { suggestionId(it) !in ignoredSuggestions }.takeIf { it > 0 }?.toString() ?: "W"
        title?.contentDescription = if (expanded) "Close WriteAssist" else "Open WriteAssist, " + activeEdits.count { suggestionId(it) !in ignoredSuggestions } + " suggestions"
        title?.gravity = if (expanded) Gravity.START else Gravity.CENTER
        checkButton?.visibility = if (expanded && reviewTab) View.VISIBLE else View.GONE
        feedback?.visibility = if (expanded) View.VISIBLE else View.GONE
        val panel = panelContent ?: return
        panel.removeAllViews()
        feedback?.text = ""
        panelScroll?.visibility = if (expanded) View.VISIBLE else View.GONE
        checkButton?.text = "Check full text with AI  ›"
        if (!expanded) { resizeBubble(); return }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tab(label: String, selected: Boolean, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.rgb(18, 155, 103) else muted)
            setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener { action() }
        }
        tabs.addView(tab("Suggestions (" + activeEdits.count { suggestionId(it) !in ignoredSuggestions } + ")", reviewTab) {
            reviewTab = true; renderAssistantPanel()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        tabs.addView(tab("Rewrite", !reviewTab) {
            reviewTab = false; renderAssistantPanel()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(tabs)
        if (reviewTab) {
            val visible = activeEdits.filter { suggestionId(it) !in ignoredSuggestions }
            if (visible.isEmpty()) {
                panel.addView(TextView(this).apply {
                    text = "No suggestions yet. Keep typing or check with AI."
                    textSize = 14f
                    setTextColor(muted)
                    setPadding(dp(8), dp(18), dp(8), dp(18))
                })
            }
            val snapshot = lastText
            val snapshotGeneration = generation
            for (edit in visible) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = rounded(if (dark) Color.rgb(43, 53, 54) else Color.rgb(245, 247, 246))
                }
                val category = when {
                    edit.before.isBlank() -> "Correctness · Add text"
                    edit.after.isBlank() -> "Correctness · Remove text"
                    edit.before.trim().equals(edit.after.trim(), ignoreCase = true) -> "Correctness · Formatting"
                    else -> "Writing suggestion"
                }
                card.addView(TextView(this).apply {
                    text = category
                    textSize = 12f
                    setTextColor(Color.rgb(28, 172, 114))
                })
                card.addView(TextView(this).apply {
                    text = edit.before.ifBlank { "(insert)" } + "  →  " + edit.after.ifBlank { "(remove)" }
                    textSize = 15f
                    setTextColor(foreground)
                    setPadding(0, dp(10), 0, dp(10))
                })
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                actions.addView(TextView(this).apply {
                    text = "Dismiss"
                    textSize = 13f
                    setTextColor(muted)
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    setOnClickListener {
                        ignoredSuggestions.add(suggestionId(edit))
                        renderAssistantPanel()
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
                actions.addView(TextView(this).apply {
                    text = "✓  Accept"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = rounded(Color.rgb(21, 148, 96))
                    setOnClickListener {
                        if (snapshotGeneration != generation) return@setOnClickListener
                        val replacement = SuggestionDiff.apply(snapshot, edit)
                        if (applyReplacement(snapshot, replacement, edit)) {
                            updateFromFocus()
                        } else feedback?.text = "The editor changed or could not apply this correction."
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
                card.addView(actions)
                panel.addView(card, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) })
            }
            resizeBubble()
            return
        }
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
        val options = if (stylePreview == null) styles else emptyList()
        for ((name, instruction) in options) {
            val item = TextView(this).apply {
                text = name + "   ›"
                textSize = 14f
                setTextColor(foreground)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener { requestStyle(name, instruction) }
            }
            panel.addView(item)
        }
        if (styleBusy) panel.addView(TextView(this).apply {
            text = "Generating rewrite…"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(8))
        })
        val preview = stylePreview
        if (preview != null) {
            panel.addView(TextView(this).apply {
                text = styleName + " preview:\n" + preview
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
                    if (applyReplacement(styleOriginal, preview)) {
                        stylePreview = null
                        expanded = false
                        handler.postDelayed(refresh, 300)
                    } else feedback?.text = "This editor does not support direct replacement."
                }
            })
            panel.addView(tab("Discard rewrite", false) {
                stylePreview = null
                renderAssistantPanel()
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
        styleBusy = true
        stylePreview = null
        renderAssistantPanel()
        aiWorker.execute {
            val result = GeminiFallback.review(this, source, instruction)
            handler.post {
                if (token != styleGeneration || lastText != source || lastPackage != pkg) return@post
                styleBusy = false
                if (result.source == "Local") {
                    renderAssistantPanel()
                    feedback?.text = result.notice.ifBlank { "AI rewrite unavailable. Check your Gemini key and connection." }
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
        if (bubbleX < 0) {
            val saved = getSharedPreferences("bubble_position", MODE_PRIVATE)
            bubbleX = saved.getInt("x", dp(8))
            bubbleY = saved.getInt("y", (resources.displayMetrics.heightPixels * 0.48f).toInt())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (dark) Color.rgb(30, 38, 39) else Color.WHITE)
            elevation = dp(8).toFloat()
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        val heading = TextView(this).apply {
            text = "W"
            textSize = 18f
            minHeight = dp(44)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(21, 148, 96))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setOnClickListener {
                expanded = !expanded
                if (expanded) reviewTab = true
                renderAssistantPanel()
                resizeBubble()
            }
        }
        box.addView(heading)
        title = heading
        val foreground = if (dark) Color.WHITE else Color.rgb(31, 41, 55)
        val secondary = if (dark) Color.LTGRAY else Color.DKGRAY
        checkButton = TextView(this).apply {
            text = "Check grammar with AI"
            textSize = 13f
            setTextColor(Color.rgb(21, 148, 96))
            setPadding(0, dp(10), 0, dp(8))
            visibility = View.GONE
            setOnClickListener { requestAiReview(true) }
        }.also { box.addView(it) }
        feedback = TextView(this).apply {
            visibility = View.GONE
            textSize = 12f
            setTextColor(secondary)
        }.also { box.addView(it) }
        panelContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panelScroll = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = false
            addView(panelContent)
        }.also { box.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(330))) }
        val params = WindowManager.LayoutParams(
            dp(52), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = bubbleX.coerceIn(0, (resources.displayMetrics.widthPixels - dp(52)).coerceAtLeast(0))
            y = bubbleY.coerceIn(0, (resources.displayMetrics.heightPixels - dp(90)).coerceAtLeast(0))
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        heading.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!expanded && (dragging ||
                        kotlin.math.abs(event.rawX - downX) > dp(5) ||
                        kotlin.math.abs(event.rawY - downY) > dp(5))) {
                        dragging = true
                        val maxX = (resources.displayMetrics.widthPixels - dp(52)).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(0)
                        params.x = (startX + event.rawX - downX).toInt().coerceIn(0, maxX)
                        params.y = (startY + event.rawY - downY).toInt().coerceIn(0, maxY)
                        try { manager?.updateViewLayout(box, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        bubbleX = params.x
                        bubbleY = params.y
                        getSharedPreferences("bubble_position", MODE_PRIVATE).edit()
                            .putInt("x", bubbleX).putInt("y", bubbleY).apply()
                    } else {
                        heading.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
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
        checkButton = null
        feedback = null
        panelContent = null
        panelScroll = null
    }

    private fun applyReplacement(
        original: String,
        replacement: String,
        edit: SuggestionDiff.Edit? = null
    ): Boolean {
        if (!AssistantControls.enabled(this) || original == replacement) return false
        val node = editableNode() ?: return false
        return try {
            if (node != editor || node.isShowingHintText || node.text?.toString() != original ||
                node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return false
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
            }
            val changed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (changed && start >= 0 && end >= start) {
                val selection = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                        SuggestionDiff.mapOffset(start, edit, replacement.length))
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                        SuggestionDiff.mapOffset(end, edit, replacement.length))
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
            }
            changed
        } finally { node.recycle() }
    }

    override fun onInterrupt() { clearSession() }

    override fun onDestroy() {
        settings.unregisterOnSharedPreferenceChangeListener(settingsChanged)
        AssistantControls.cancelNotification(this)
        handler.removeCallbacksAndMessages(null)
        clearSession()
        aiWorker.shutdownNow()
        super.onDestroy()
    }
}

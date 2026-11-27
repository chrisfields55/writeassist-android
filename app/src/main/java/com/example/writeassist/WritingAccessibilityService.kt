package com.example.writeassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.Build
import android.view.inputmethod.EditorInfo
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
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView

class WritingAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var inputMethod: InputMethod? = null
    private var inputSnapshot: InputSnapshot? = null

    private data class InputSnapshot(val packageName: String, val text: String, val offset: Int, val caret: Int)

    @android.annotation.TargetApi(33)
    override fun onCreateInputMethod(): InputMethod =
        object : InputMethod(this) {
            override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
                if (inputSnapshot != null) clearSession()
                scheduleInputRefresh()
            }

            override fun onFinishInput() {
                if (inputSnapshot != null) clearSession()
                scheduleInputRefresh()
            }

            override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int,
                newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
                scheduleInputRefresh()
            }
        }.also { inputMethod = it }

    private fun scheduleInputRefresh() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 180)
    }

    @android.annotation.TargetApi(33)
    private fun readInputSnapshot(): InputSnapshot? {
        if (Build.VERSION.SDK_INT < 33) return null
        val method = inputMethod ?: return null
        if (!method.currentInputStarted) return null
        val info = method.currentInputEditorInfo ?: return null
        val pkg = info.packageName ?: return null
        val input = info.inputType
        val variation = input and InputType.TYPE_MASK_VARIATION
        if (pkg.isBlank() || pkg == packageName ||
            input and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT ||
            variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) ||
            info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return null
        val snapshot = method.currentInputConnection?.getSurroundingText(2501, 2501, 0) ?: return null
        val text = snapshot.text.toString()
        if (snapshot.offset < 0 || text.length > 2500 || snapshot.selectionEnd !in 0..text.length) return null
        return InputSnapshot(pkg, text, snapshot.offset, snapshot.selectionEnd)
    }

    private var editor: AccessibilityNodeInfo? = null
    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val settingsChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            AssistantControls.ENABLED, AssistantControls.NOTIFICATION -> {
                AssistantControls.updateNotification(this)
                updateFromFocus()
            }
            "appearance" -> { hideBubble(); updateFromFocus() }
        }
    }
    private var bubble: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelScroll: ScrollView? = null
    private var panelContent: LinearLayout? = null
    private var title: TextView? = null
    private var checkButton: TextView? = null
    private var feedback: TextView? = null
    private val ignoredSuggestions = mutableSetOf<String>()
    private var reviewTab = true
    private var bubbleX = -1
    private var bubbleY = -1
    private var expanded = false
    private data class AcceptedEdit(
        val expectedText: String,
        val remaining: List<SuggestionDiff.Edit>,
        val expiresAt: Long
    )
    private var acceptedEdit: AcceptedEdit? = null
    private var activeEdits: List<SuggestionDiff.Edit> = emptyList()
    private var lastText = ""
    private var lastPackage = ""
    private var generation = 0
    private var overlayTheme = ""
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
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source
                if (source != null && source.refresh() && isLiveEditor(source)) {
                    updateFromFocus(source)
                } else {
                    source?.recycle()
                    updateFromFocus()
                }
            }
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
        val systemFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (systemFocus != null) {
            if (isLiveEditor(systemFocus)) return systemFocus
            systemFocus.recycle()
        }
        findEditableFocus(rootInActiveWindow)?.let { return it }
        // The active root can belong to the keyboard while the app retains input focus.
        val appWindows = windows
        try {
            for (window in appWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (window.isFocused || window.isActive)) {
                    findEditableFocus(window.root)?.let { return it }
                }
            }
        } finally {
            appWindows.forEach { it.recycle() }
        }
        return null
    }

    private fun findEditableFocus(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val focused = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            finally { root.recycle() }
        if (focused == null) return null
        if (isLiveEditor(focused)) return focused
        focused.recycle()
        return null
    }

    private fun isLiveEditor(node: AccessibilityNodeInfo): Boolean =
        node.isFocused && node.isVisibleToUser &&
            (node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) &&
            node.packageName?.toString() != packageName && !isSensitive(node)

    private fun clearSession() {
        editor?.recycle()
        editor = null
        inputSnapshot = null
        generation++
        lastText = ""
        lastPackage = ""
        acceptedEdit = null
        activeEdits = emptyList()
        ignoredSuggestions.clear()
        expanded = false
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

    private fun updateFromFocus(eventEditor: AccessibilityNodeInfo? = null) {
        if (!AssistantControls.enabled(this)) {
            eventEditor?.recycle()
            clearSession()
            return
        }
        var node = eventEditor ?: editableNode()
        val input = if (node == null || node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
            readInputSnapshot()?.takeIf { node == null || it.packageName == node?.packageName?.toString() }
        } else null
        if (input != null) {
            node?.recycle()
            node = null
        }
        val pkg = input?.packageName ?: node?.packageName?.toString().orEmpty()
        val value = input?.text ?: node?.takeUnless { it.isShowingHintText }?.text?.toString().orEmpty()
        if (pkg.isBlank() || value.length > 2500) {
            node?.recycle()
            clearSession()
            return
        }
        val changedEditor = node != editor || (input == null) != (inputSnapshot == null) ||
            input?.packageName != inputSnapshot?.packageName || input?.offset != inputSnapshot?.offset
        if (changedEditor) {
            clearSession()
            editor = node
        } else node?.recycle()
        inputSnapshot = input
        val changed = changedEditor || value != lastText
        val accepted = acceptedEdit?.takeIf {
            changed && value == it.expectedText && android.os.SystemClock.uptimeMillis() <= it.expiresAt
        }
        if (changed) acceptedEdit = null
        if (changed) {
            generation++
            ignoredSuggestions.clear()
        }
        lastText = value
        lastPackage = pkg
        val needsOverlay = bubble == null
        showBubble()
        if (changed || needsOverlay) {
            val local = LocalCorrections.suggestions(value)
            activeEdits = if (accepted == null) local else {
                val remaining = accepted.remaining
                (remaining + local.filter { candidate ->
                    remaining.none { it.start == candidate.start ||
                        it.start < candidate.end && candidate.start < it.end }
                }).sortedBy { it.start }
            }
            renderAssistantPanel()
        }
    }

    private fun openNanoReview() {
        if (lastText.isBlank() || liveCaret(lastText) == null) return
        val review = android.content.Intent(this, MainActivity::class.java).apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_TEXT, lastText)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(review)
    }

    private fun visibleSuggestions(): List<SuggestionDiff.Edit> =
        activeEdits.filter { suggestionId(it) !in ignoredSuggestions }

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
        title?.text = if (expanded) "W   WriteAssist   ×" else visibleSuggestions().size.takeIf { it > 0 }?.toString() ?: "W"
        title?.contentDescription = if (expanded) "Close WriteAssist" else "Open WriteAssist, " + visibleSuggestions().size + " suggestions"
        title?.gravity = if (expanded) Gravity.START else Gravity.CENTER
        checkButton?.visibility = if (expanded && reviewTab) View.VISIBLE else View.GONE
        feedback?.visibility = if (expanded) View.VISIBLE else View.GONE
        val panel = panelContent ?: return
        panel.removeAllViews()
        feedback?.text = ""
        panelScroll?.visibility = if (expanded) View.VISIBLE else View.GONE
        checkButton?.text = "Open Gemini Nano review  ›"
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
        tabs.addView(tab("Suggestions (" + visibleSuggestions().size + ")", reviewTab) {
            reviewTab = true; renderAssistantPanel()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        tabs.addView(tab("Rewrite", !reviewTab) {
            reviewTab = false; renderAssistantPanel()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(tabs)
        if (reviewTab) {
            val visible = visibleSuggestions()
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
            val compatible = mutableListOf<SuggestionDiff.Edit>()
            // Prefer the individual proofreading edits when a rewrite covers the same text.
            for (candidate in visible) {
                if (compatible.none { it.start == candidate.start ||
                    it.start < candidate.end && candidate.start < it.end }) compatible.add(candidate)
            }
            if (compatible.size > 1) {
                panel.addView(tab("Accept ${compatible.size} compatible suggestions", true) {
                    if (snapshotGeneration != generation || visibleSuggestions() != visible) return@tab
                    val start = compatible.minOf { it.start }
                    val end = compatible.maxOf { it.end }
                    val revised = compatible.sortedByDescending { it.start }.fold(snapshot) { text, edit ->
                        SuggestionDiff.apply(text, edit)
                    }
                    val combined = SuggestionDiff.Edit(start, end, snapshot.substring(start, end),
                        revised.substring(start, revised.length - (snapshot.length - end)))
                    if (applyReplacement(snapshot, revised, combined)) {
                        scheduleInputRefresh()
                    } else {
                        feedback?.text = "The editor changed or could not apply these corrections."
                    }
                })
            }
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
                        val remaining = activeEdits.filter { suggestionId(it) !in ignoredSuggestions }
                            .mapNotNull { SuggestionDiff.afterAccepting(it, edit, replacement) }
                        acceptedEdit = AcceptedEdit(replacement, remaining,
                            android.os.SystemClock.uptimeMillis() + 3000)
                        if (applyReplacement(snapshot, replacement, edit)) {
                            scheduleInputRefresh()
                        } else {
                            acceptedEdit = null
                            feedback?.text = "The editor changed or could not apply this correction."
                        }
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
        panel.addView(tab("Open Gemini Nano for rewriting", true) { openNanoReview() })
        resizeBubble()
    }

    private fun liveCaret(source: String): Int? {
        val previous = inputSnapshot
        if (previous != null) {
            val live = readInputSnapshot() ?: return null
            return live.caret.takeIf {
                live.packageName == previous.packageName && live.offset == previous.offset && live.text == source
            }
        }
        val node = editableNode() ?: return null
        return try {
            if (node != editor || node.text?.toString() != source) return null
            node.textSelectionEnd
        } finally { node.recycle() }
    }

    private fun currentSentence(source: String): IntRange? {
        val caret = liveCaret(source) ?: return null
        if (caret !in 0..source.length) return null
        val sentences = java.text.BreakIterator.getSentenceInstance(java.util.Locale.ENGLISH)
        sentences.setText(source)
        val index = caret.coerceAtMost(source.lastIndex)
        val start = sentences.preceding(index + 1)
        val end = sentences.following(index)
        if (start < 0 || end <= start) return null
        val segment = source.substring(start, end)
        val first = segment.indexOfFirst { !it.isWhitespace() }
        val last = segment.indexOfLast { !it.isWhitespace() }
        return if (first >= 0) (start + first)..(start + last) else null
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
            text = "Open Gemini Nano review"
            textSize = 13f
            setTextColor(Color.rgb(21, 148, 96))
            setPadding(0, dp(10), 0, dp(8))
            visibility = View.GONE
            setOnClickListener { openNanoReview() }
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
        if (inputSnapshot != null) return applyThroughInput(original, replacement, edit)
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

    @android.annotation.TargetApi(33)
    private fun applyThroughInput(original: String, replacement: String, edit: SuggestionDiff.Edit?): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val previous = inputSnapshot ?: return false
        val connection = inputMethod?.currentInputConnection ?: return false
        val live = readInputSnapshot() ?: return false
        if (live.text != original || live.packageName != previous.packageName ||
            live.offset != previous.offset) return false
        val start = (edit?.start ?: 0) + live.offset
        val end = (edit?.end ?: original.length) + live.offset
        connection.setSelection(start, end)
        val selected = connection.getSurroundingText(0, 0, 0) ?: return false
        if (selected.offset < 0 || selected.offset + selected.selectionStart != start ||
            selected.offset + selected.selectionEnd != end ||
            selected.text.toString() != (edit?.before ?: original)) return false
        connection.commitText(edit?.after ?: replacement, 1, null)
        scheduleInputRefresh()
        return true
    }

    override fun onInterrupt() { clearSession() }

    override fun onDestroy() {
        settings.unregisterOnSharedPreferenceChangeListener(settingsChanged)
        AssistantControls.cancelNotification(this)
        handler.removeCallbacksAndMessages(null)
        clearSession()
        super.onDestroy()
    }
}

package com.aidev.four

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

class TerminalImeProxyEditText(context: Context) : AppCompatEditText(context) {
    var onComposingChanged: (String) -> Unit = {}
    var onCommittedText: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onEnter: () -> Unit = {}
    var tuiMode = false
    var tuiKeyHandler: ((KeyEvent) -> Boolean)? = null
    private var clearing = false
    private var currentComposing = ""

    private var tuiComposing = ""
    private var tuiComposingSent = false

    init {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setTextColor(Color.TRANSPARENT)
        setBackgroundColor(Color.TRANSPARENT)
        isCursorVisible = false
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        val base = super.onCreateInputConnection(outAttrs)
        return object : InputConnectionWrapper(base, true) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (tuiMode) {
                    tuiComposing = text?.toString().orEmpty()
                    tuiComposingSent = false
                    clearProxyText()
                    return super.setComposingText("", 1)
                }
                val newText = text?.toString().orEmpty()
                val oldText = currentComposing
                var prefixLen = 0
                val minLen = minOf(oldText.length, newText.length)
                while (prefixLen < minLen && oldText[prefixLen] == newText[prefixLen]) {
                    prefixLen++
                }
                val removed = oldText.length - prefixLen
                android.util.Log.d("AIDEV_INPUT", "setComposing old=\"$oldText\" new=\"$newText\" prefix=$prefixLen removed=$removed added=${newText.length - prefixLen}")
                repeat(removed) { onBackspace() }
                for (i in prefixLen until newText.length) {
                    onCommittedText(newText[i].toString())
                }
                currentComposing = newText
                onComposingChanged(currentComposing)
                return super.setComposingText(text, newCursorPosition)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (tuiMode) {
                    val str = text?.toString().orEmpty()
                    if (str.isNotEmpty()) onCommittedText(str)
                    tuiComposingSent = true
                    tuiComposing = ""
                    clearProxyText()
                    return super.commitText("", 1)
                }
                val committed = text?.toString().orEmpty()
                android.util.Log.d("AIDEV_INPUT", "commitText text=\"$committed\" composing=\"$currentComposing\" tui=$tuiMode")
                if (committed.isNotEmpty() && committed != currentComposing) {
                    val composedLen = currentComposing.length
                    if (composedLen > 0) repeat(composedLen) { onBackspace() }
                    onCommittedText(committed)
                }
                currentComposing = ""
                onComposingChanged("")
                val result = super.commitText(text, newCursorPosition)
                clearProxyText()
                return result
            }

            override fun finishComposingText(): Boolean {
                if (tuiMode) {
                    if (!tuiComposingSent && tuiComposing.isNotEmpty()) {
                        onCommittedText(tuiComposing)
                        tuiComposingSent = true
                    }
                    tuiComposing = ""
                    clearProxyText()
                    return super.finishComposingText()
                }
                android.util.Log.d("AIDEV_INPUT", "finishComposing composing=\"$currentComposing\" tui=$tuiMode")
                currentComposing = ""
                onComposingChanged("")
                return super.finishComposingText()
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (tuiMode) {
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }
                val dropCount = beforeLength.coerceAtLeast(1)
                android.util.Log.d("AIDEV_INPUT", "deleteSurrounding before=$beforeLength after=$afterLength composing=\"$currentComposing\"")
                if (currentComposing.isNotEmpty()) {
                    currentComposing = currentComposing.dropLast(dropCount)
                    onComposingChanged(currentComposing)
                }
                repeat(dropCount) { onBackspace() }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (tuiMode) {
                    val handler = tuiKeyHandler
                    if (handler != null) {
                        return handler(event)
                    }
                    return super.sendKeyEvent(event)
                }
                android.util.Log.d("AIDEV_INPUT", "sendKeyEvent action=${event.action} keyCode=${event.keyCode} chars=${event.characters} tui=$tuiMode")
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> {
                            onBackspace()
                            return true
                        }
                        KeyEvent.KEYCODE_ENTER -> {
                            onEnter()
                            return true
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    fun clearProxyText() {
        if (clearing) return
        clearing = true
        post {
            text?.clear()
            clearing = false
        }
    }
}

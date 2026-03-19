package com.andforce.andclaw

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executor

@SuppressLint("AccessibilityPolicy")
class AgentAccessibilityService : AccessibilityService() {
    companion object {
        var instance: AgentAccessibilityService? = null
        private const val TAG = "AiAccessibility"
    }

    override fun onServiceConnected() { instance = this }

    fun captureScreenHierarchy(): String {
        val sb = StringBuilder()
        val roots = getWindowRoots()
        if (roots.isEmpty()) return "Empty Screen"
        roots.forEachIndexed { index, root ->
            val window = root.window
            sb.append(
                "[window:$index" +
                    ",type=${window?.type}" +
                    ",focused=${window?.isFocused}" +
                    ",active=${window?.isActive}" +
                    ",pkg=${root.packageName}]\n"
            )
            parseNode(root, sb)
        }
        return sb.toString()
    }

    private fun parseNode(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        if (node.isClickable || !node.text.isNullOrEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            sb.append("{text:'${node.text}', bounds:[${rect.left},${rect.top},${rect.right},${rect.bottom}]}\n")
        }
        for (i in 0 until node.childCount) parseNode(node.getChild(i), sb)
    }

    fun click(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return dispatchGesture(gesture, null, null)
    }

    fun longPress(x: Int, y: Int, durationMs: Long = 1000): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return dispatchGesture(gesture, null, null)
    }

    private val browserPackages = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
        "org.mozilla.firefox", "org.mozilla.fenix",
        "com.microsoft.emmx", "com.opera.browser", "com.brave.browser",
        "com.UCMobile", "com.quark.browser", "com.tencent.mtt",
        "mark.via", "org.nicoco.nicobrowser", "com.explore.web.browser",
        "com.vivaldi.browser", "com.sec.android.app.sbrowser"
    )

    fun isWebViewContext(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (isBrowserPackage(root.packageName?.toString())) return true
        return containsWebView(root)
    }

    private fun isBrowserPackage(pkg: String?): Boolean =
        pkg != null && browserPackages.contains(pkg)

    private fun containsWebView(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 15) return false
        val cls = node.className?.toString() ?: ""
        if (cls.contains("WebView", ignoreCase = true)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsWebView(child, depth + 1)) return true
        }
        return false
    }

    fun inputText(text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        }

        val root = rootInActiveWindow
        if (root != null) {
            val editableNode = findEditableNode(root)
            if (editableNode != null) {
                if (editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("input", text))
                if (editableNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
            }
        }

        val anyFocused = focusedNode ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (anyFocused != null) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("input", text))
            if (anyFocused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 20) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child, depth + 1)
            if (result != null) return result
        }
        return null
    }

    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    fun clickNodeByText(vararg texts: String): Boolean {
        val targets = texts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (targets.isEmpty()) return false

        for (root in getWindowRoots()) {
            val node = findNodeByText(root, targets) ?: continue
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }

            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                return click(rect.centerX(), rect.centerY())
            }
        }
        return false
    }

    private fun getWindowRoots(): List<AccessibilityNodeInfo> {
        val interactiveRoots = windows
            ?.mapNotNull(AccessibilityWindowInfo::getRoot)
            ?.filterNotNull()
            ?: emptyList()
        if (interactiveRoots.isNotEmpty()) return interactiveRoots

        val activeRoot = rootInActiveWindow
        return if (activeRoot != null) listOf(activeRoot) else emptyList()
    }

    private fun findNodeByText(
        node: AccessibilityNodeInfo,
        targets: List<String>,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (depth > 25) return null

        val text = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()
        val matches = targets.any { target ->
            text.equals(target, ignoreCase = true) || contentDesc.equals(target, ignoreCase = true)
        }
        if (matches) {
            return findClickableAncestor(node) ?: node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByText(child, targets, depth + 1)
            if (result != null) return result
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var hops = 0
        while (current != null && hops < 8) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return null
    }

    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Executor { it.run() },
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer, screenshot.colorSpace
                    )
                    screenshot.hardwareBuffer.close()
                    callback(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "截屏失败, errorCode=$errorCode")
                    callback(null)
                }
            }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

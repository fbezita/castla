package com.castla.mirror.utils

object ImeState {
    const val VISIBLE = 1
    const val SERVED_INPUT = 1 shl 1
    const val INPUT_TARGET_ON_DISPLAY = 1 shl 2

    fun parseInputMethodDump(dumpsys: String): Int {
        var state = 0
        if (isImeVisible(dumpsys)) {
            state = state or VISIBLE
        }
        if (hasServedInput(dumpsys)) {
            state = state or SERVED_INPUT
        }
        return state
    }

    fun parseWindowDump(dumpsys: String, displayId: Int): Int {
        if (displayId <= 0) return 0
        return if (ImeTargetParser.displaysWithInputTarget(dumpsys).contains(displayId)) {
            INPUT_TARGET_ON_DISPLAY
        } else {
            0
        }
    }

    private fun isImeVisible(dumpsys: String): Boolean {
        if (dumpsys.contains("mInputShown=true")) return true
        if (dumpsys.contains("mIsInputViewShown=true")) return true
        if (dumpsys.contains("isInputViewShown=true")) return true
        if (dumpsys.contains("mShowInputRequested=true")) return true
        if (dumpsys.contains("mInputViewStarted=true")) return true
        if (dumpsys.contains("mDecorViewWasVisible=true")) return true
        val imeWindowVis = Regex("""mImeWindowVis=(0x[0-9a-fA-F]+|\d+)""")
            .find(dumpsys)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { raw ->
                if (raw.startsWith("0x", ignoreCase = true)) {
                    raw.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                } else {
                    raw.toIntOrNull()
                }
            }
        if (imeWindowVis != null && imeWindowVis != 0) return true
        val decorVisible = dumpsys.contains("mDecorViewVisible=true")
        val windowVisible = dumpsys.contains("mWindowVisible=true")
        return decorVisible && windowVisible
    }

    private fun hasServedInput(dumpsys: String): Boolean {
        if (dumpsys.contains("mServedView=") && !dumpsys.contains("mServedView=null")) return true
        if (dumpsys.contains("mServedInputConnection=") && !dumpsys.contains("mServedInputConnection=null")) return true
        if (dumpsys.contains("mCurClient=") && !dumpsys.contains("mCurClient=null")) {
            if (
                dumpsys.contains("mInputShown=true") ||
                dumpsys.contains("mShowRequested=true") ||
                dumpsys.contains("mShowInputRequested=true") ||
                dumpsys.contains("mIsInputViewShown=true") ||
                dumpsys.contains("isInputViewShown=true")
            ) return true
        }
        return false
    }
}

package com.focusledger.timer

/**
 * Process-level flag. A fresh Android process starts with this false, so the
 * first Activity creation after a genuine cold start can be distinguished
 * from a rotation or an activity being recreated by the OS.
 */
object AppState {
    var processTouched = false
}

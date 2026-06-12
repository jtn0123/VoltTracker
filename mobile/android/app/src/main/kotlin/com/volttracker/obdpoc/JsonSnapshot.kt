package com.volttracker.obdpoc

import org.json.JSONObject

/**
 * Thread-safe last-value holder for the JSON payloads MainActivity shares between the UI thread
 * (writer) and the WebView JavaBridge thread (readers such as `exportDebugBundle`,
 * `getAppStateJson`, and `isLoggingActive`).
 *
 * `@Volatile` alone only publishes the *reference* safely; a JSONObject is mutable, so the old
 * pattern relied on every caller remembering to never `.put(...)` on an instance after assigning
 * it. This holder makes that contract structural: [set]/[setFromString] store a freshly parsed
 * copy that shares no mutable state with the caller, and the stored instance is never mutated
 * again, so the volatile swap is a safe immutable-snapshot publication.
 */
internal class JsonSnapshot {
    @Volatile
    private var value = JSONObject()

    /** Publishes a snapshot parsed from [json]. Blank/malformed input stores an empty object. */
    fun setFromString(json: String?) {
        value = MainActivityUtils.parseJson(json)
    }

    /** Publishes an isolated copy of [next]; later caller-side mutation cannot leak in. */
    fun set(next: JSONObject) {
        value = MainActivityUtils.parseJson(next.toString())
    }

    /** The current snapshot. Callers must treat it as read-only. */
    fun get(): JSONObject = value
}

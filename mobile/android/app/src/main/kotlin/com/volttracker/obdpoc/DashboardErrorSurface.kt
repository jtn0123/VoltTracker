package com.volttracker.obdpoc

import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The native fallback surface for the dashboard WebView: a dark full-bleed panel with a message
 * and an optional Retry button, shown when the renderer dies ("reconnecting" state), when
 * recovery gives up, or when the dashboard JS never completes its handshake. Built in code (the
 * app has no layout inflater usage for the dashboard) and mounted on the same [FrameLayout] that
 * hosts the WebView, so it covers a broken/blank page instead of leaving it silently dead.
 *
 * Main-thread only, like all view work.
 */
class DashboardErrorSurface(
    private val container: FrameLayout,
    private val onRetry: Runnable,
) {
    private var surface: LinearLayout? = null
    private var message: TextView? = null
    private var retry: Button? = null

    /** Shows (or updates) the surface with [messageResId]; [showRetry] toggles the Retry button. */
    fun show(
        messageResId: Int,
        showRetry: Boolean,
    ) {
        val panel = surface ?: build().also { surface = it }
        message?.setText(messageResId)
        retry?.visibility = if (showRetry) android.view.View.VISIBLE else android.view.View.GONE
        if (panel.parent == null) {
            container.addView(panel)
        }
        panel.bringToFront()
    }

    /** Removes the surface from the container if it is showing. */
    fun hide() {
        val panel = surface ?: return
        if (panel.parent != null) {
            container.removeView(panel)
        }
    }

    /** True while the surface is mounted on the container. */
    fun isShowing(): Boolean = surface?.parent != null

    private fun build(): LinearLayout {
        val context = container.context
        val panel = LinearLayout(context)
        panel.id = R.id.dashboard_error_surface
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER
        panel.setBackgroundColor(ContextCompat.getColor(context, R.color.volt_dark))
        panel.layoutParams =
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val messageView = TextView(context)
        messageView.id = R.id.dashboard_error_message
        messageView.setTextColor(MESSAGE_TEXT_COLOR)
        messageView.textSize = MESSAGE_TEXT_SIZE_SP
        messageView.gravity = Gravity.CENTER
        val pad = (MESSAGE_PADDING_DP * context.resources.displayMetrics.density).toInt()
        messageView.setPadding(pad, pad, pad, pad)
        panel.addView(
            messageView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        message = messageView

        val retryButton = Button(context)
        retryButton.id = R.id.dashboard_error_retry
        retryButton.setText(R.string.dashboard_retry)
        retryButton.setOnClickListener { onRetry.run() }
        panel.addView(
            retryButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        retry = retryButton
        return panel
    }

    private companion object {
        // Matches the dashboard's near-white body text against the volt_dark chrome.
        val MESSAGE_TEXT_COLOR = 0xFFE8EAF2.toInt()
        const val MESSAGE_TEXT_SIZE_SP = 16f
        const val MESSAGE_PADDING_DP = 24f
    }
}

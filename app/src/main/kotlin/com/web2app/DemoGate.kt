package com.web2app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

/**
 * DEMO BUILD: the app runs for ONE session, then every later launch shows
 * [DemoExpiredActivity] instead. A "session" is one app process: the id below
 * is new every time Android starts the app, so moving between this app's own
 * screens keeps working, but closing it and opening it again does not.
 *
 * Built from nocodeappbuilder.dev without Builder Pro; Pro builds use the full
 * runtime repo, which has no gate.
 */
object DemoGate {
    private const val PREFS = "demo_gate"
    private const val KEY_SESSION = "first_session"
    private val SESSION: String = UUID.randomUUID().toString()

    const val UPGRADE_URL = "https://www.nocodeappbuilder.dev/pricing/?utm_source=demo_apk&utm_medium=app&utm_campaign=demo_expired"

    /** True when this launch is past the one demo run. The first call ever claims the run. */
    fun expired(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val first = prefs.getString(KEY_SESSION, null)
        if (first == null) {
            prefs.edit().putString(KEY_SESSION, SESSION).commit()
            return false
        }
        return first != SESSION
    }

    /**
     * Sends an expired launch to the "demo ended" screen. Returns true if it did.
     *
     * [restoring] is true when Android is bringing back a screen it killed in the
     * background (savedInstanceState != null). That is still the first run, so the
     * new process takes the run over instead of being treated as a second launch.
     */
    fun block(activity: Activity, restoring: Boolean = false): Boolean {
        if (restoring) {
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_SESSION, null) != null) {
                prefs.edit().putString(KEY_SESSION, SESSION).commit()
                return false
            }
        }
        if (!expired(activity)) return false
        activity.startActivity(Intent(activity, DemoExpiredActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        activity.finish()
        return true
    }
}

/** Shown on every launch after the demo run: explains it and links to Builder Pro. */
class DemoExpiredActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        val ink = Color.parseColor("#141311")
        val accent = Color.parseColor("#E8562A")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.parseColor("#F6F3EC"))
        }
        val badge = TextView(this).apply {
            text = "DEMO ENDED"
            setTextColor(accent)
            textSize = 12f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "This demo app has ended"
            setTextColor(ink)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        val body = TextView(this).apply {
            text = "This was a one-time demo of your app, made with No-Code App Builder. " +
                "Get Builder Pro to download the full app with no time limit, plus the AAB for Google Play."
            setTextColor(Color.parseColor("#4A4741"))
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        }
        val button = Button(this).apply {
            text = "Get the full app"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(ink) }
            setPadding(dp(28), dp(14), dp(28), dp(14))
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DemoGate.UPGRADE_URL))) }
            }
        }
        val site = TextView(this).apply {
            text = "nocodeappbuilder.dev"
            setTextColor(Color.parseColor("#76716A"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(badge)
        root.addView(title)
        root.addView(body)
        root.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(26) })
        root.addView(site)
        setContentView(root)
    }
}

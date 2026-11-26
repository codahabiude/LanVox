package com.example.wifip2pchat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton

/**
 * Onboarding tutorial shown on first launch.
 *
 * 4 swipeable pages explaining how to use the app:
 *   1. Welcome — Connect directly with nearby phones
 *   2. Discovery — Find peers via Wi-Fi Direct
 *   3. Chat — Send real-time text messages
 *   4. Calls — Make audio and video calls
 *
 * After completing or skipping, the user is taken to MainActivity
 * and the onboarding is marked as completed in SharedPreferences.
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "lanvox_prefs"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"

        /** Check if onboarding has been completed before. */
        fun isOnboardingDone(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ONBOARDING_DONE, false)
        }

        /** Mark onboarding as completed. */
        private fun markOnboardingDone(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ONBOARDING_DONE, true)
                .apply()
        }
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var layoutDots: LinearLayout
    private lateinit var btnNext: MaterialButton
    private lateinit var btnSkip: MaterialButton
    private lateinit var dots: Array<ImageView>

    private val pages = listOf(
        OnboardingPage(
            iconResId = R.drawable.ic_brand_logo,
            title = "Welcome to LanVox",
            description = "Connect two phones directly over Wi-Fi — no internet, no router, no cellular data. Just Wi-Fi and proximity."
        ),
        OnboardingPage(
            iconResId = R.drawable.ic_search,
            title = "Discover Nearby Devices",
            description = "Tap Discover to find other phones running LanVox nearby. Both phones need Wi-Fi on and Location permissions granted for discovery to work."
        ),
        OnboardingPage(
            iconResId = R.drawable.ic_send,
            title = "Chat in Real Time",
            description = "Once connected, exchange instant text messages. Messages travel directly between phones over your private link — fast and secure."
        ),
        OnboardingPage(
            iconResId = R.drawable.ic_phone,
            title = "Audio & Video Calls",
            description = "Make voice calls or video calls with live camera feed. Your peer gets a ringing screen with Accept or Decline. All communication stays peer-to-peer."
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        initViews()
        setupViewPager()
        setupDots(0)
    }

    private fun initViews() {
        viewPager = findViewById(R.id.viewPager)
        layoutDots = findViewById(R.id.layoutDots)
        btnNext = findViewById(R.id.btnNext)
        btnSkip = findViewById(R.id.btnSkip)

        btnNext.setOnClickListener { goToNextPage() }
        btnSkip.setOnClickListener { finishOnboarding() }
    }

    private fun setupViewPager() {
        val adapter = OnboardingAdapter(pages)
        viewPager.adapter = adapter

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                setupDots(position)
                updateButtons(position)
            }
        })
    }

    /**
     * Create or update dot indicators to reflect the current page.
     */
    private fun setupDots(currentPage: Int) {
        layoutDots.removeAllViews()
        dots = Array(pages.size) { i ->
            val dot = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(24, 24).apply {
                    marginStart = 4
                    marginEnd = 4
                }
                setImageResource(
                    if (i == currentPage) R.drawable.dot_active
                    else R.drawable.dot_inactive
                )
            }
            layoutDots.addView(dot)
            dot
        }
    }

    private fun updateButtons(position: Int) {
        if (position == pages.size - 1) {
            btnNext.text = "Get Started"
            btnSkip.visibility = View.GONE
        } else {
            btnNext.text = "Next"
            btnSkip.visibility = View.VISIBLE
        }
    }

    private fun goToNextPage() {
        val current = viewPager.currentItem
        if (current < pages.size - 1) {
            viewPager.currentItem = current + 1
        } else {
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        markOnboardingDone(this)
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onBackPressed() {
        val current = viewPager.currentItem
        if (current > 0) {
            viewPager.currentItem = current - 1
        } else {
            finishOnboarding()
        }
    }
}

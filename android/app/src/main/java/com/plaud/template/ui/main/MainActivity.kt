package com.plaud.template.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.plaud.template.PlaudTemplateApp
import com.plaud.template.R
import com.plaud.template.databinding.ActivityMainBinding
import com.plaud.template.storage.RecordingStore
import com.plaud.template.ui.ask.AskFragment
import com.plaud.template.ui.files.FilesFragment
import com.plaud.template.ui.home.HomeFragment
import com.plaud.template.ui.onboarding.WelcomeActivity
import com.plaud.template.ui.settings.SettingsFragment

/**
 * Main screen — bottom floating Tab Bar + four lifecycle-isolated Fragments.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var homeFragment: HomeFragment
    private lateinit var filesFragment: FilesFragment
    private lateinit var askFragment: AskFragment
    private lateinit var settingsFragment: SettingsFragment
    private var activeFragment: Fragment? = null

    private var selectedTab = 0

    private val deviceManager get() = (application as PlaudTemplateApp).deviceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If no device has been connected before, go back to Welcome — unless the user chose
        // "Connect device later" on Welcome (device can be added afterwards).
        if (RecordingStore.lastConnectedDeviceSN == null && !RecordingStore.hasSkippedOnboarding) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedTab = savedInstanceState?.getInt(STATE_SELECTED_TAB) ?: 0
        setupFragments()
        setupTabBar()
        updateTabAppearance()

        // Cloud binding alerts (e.g. device bound to another account)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                deviceManager.cloudAlerts.collect { message ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Device Binding")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun setupFragments() {
        homeFragment = supportFragmentManager.findFragmentByTag("home") as? HomeFragment ?: HomeFragment()
        filesFragment = supportFragmentManager.findFragmentByTag("files") as? FilesFragment ?: FilesFragment()
        askFragment = supportFragmentManager.findFragmentByTag("ask") as? AskFragment ?: AskFragment()
        settingsFragment = supportFragmentManager.findFragmentByTag("settings") as? SettingsFragment ?: SettingsFragment()

        val fragments = listOf(homeFragment, filesFragment, askFragment, settingsFragment)
        val tags = listOf("home", "files", "ask", "settings")
        val target = fragments[selectedTab.coerceIn(fragments.indices)]
        supportFragmentManager.beginTransaction().setReorderingAllowed(true).apply {
            fragments.forEachIndexed { index, fragment ->
                if (!fragment.isAdded) add(R.id.fragmentContainer, fragment, tags[index])
                if (fragment == target) {
                    show(fragment)
                    setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
                } else {
                    hide(fragment)
                    setMaxLifecycle(fragment, Lifecycle.State.CREATED)
                }
            }
        }.commitNow()
        activeFragment = target
    }

    private fun setupTabBar() {
        // Pin the floating bar to safe-area bottom + 8dp (mirrors iOS)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.tabBar) { v, insets ->
            val bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            (v.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin =
                bottom + (8 * v.resources.displayMetrics.density).toInt()
            v.requestLayout()
            insets
        }

        binding.tabHome.setOnClickListener { selectTab(0) }
        binding.tabFiles.setOnClickListener { selectTab(1) }
        binding.tabAsk.setOnClickListener { selectTab(2) }
        binding.tabSettings.setOnClickListener { selectTab(3) }
    }

    fun openAsk() = selectTab(2)

    private fun selectTab(index: Int) {
        if (selectedTab == index && activeFragment != null) return
        selectedTab = index

        val target = when (index) {
            0 -> homeFragment
            1 -> filesFragment
            2 -> askFragment
            3 -> settingsFragment
            else -> homeFragment
        }

        activeFragment?.let { current ->
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .hide(current)
                .setMaxLifecycle(current, Lifecycle.State.CREATED)
                .show(target)
                .setMaxLifecycle(target, Lifecycle.State.RESUMED)
                .commitNow()
        }
        activeFragment = target

        updateTabAppearance()
    }

    private fun updateTabAppearance() {
        val tabs = listOf(binding.tabHome, binding.tabFiles, binding.tabAsk, binding.tabSettings)
        val icons = listOf(binding.tabHomeIcon, binding.tabFilesIcon, binding.tabAskIcon, binding.tabSettingsIcon)
        val labels = listOf(binding.tabHomeLabel, binding.tabFilesLabel, binding.tabAskLabel, binding.tabSettingsLabel)

        for (i in tabs.indices) {
            val isSelected = i == selectedTab
            tabs[i].background = if (isSelected) {
                ContextCompat.getDrawable(this, R.drawable.bg_tab_selected)
            } else {
                null
            }
            // Selected = black icon+label, unselected = #7A7A7A (mirrors iOS)
            val tint = ContextCompat.getColor(this, if (isSelected) R.color.black else R.color.tab_unselected)
            icons[i].setColorFilter(tint)
            labels[i].setTextColor(tint)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, selectedTab)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val STATE_SELECTED_TAB = "selected_tab"
    }
}

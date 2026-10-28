package com.iskezis.myst

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.iskezis.myst.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val navHost = supportFragmentManager.findFragmentById(R.id.navHost) as NavHostFragment
        navController = navHost.navController

        binding.navBar.navHome.setOnClickListener { navigateTo(R.id.homeFragment) }
        binding.navBar.navPlaylists.setOnClickListener { navigateTo(R.id.playlistsFragment) }
        binding.navBar.navSettings.setOnClickListener { navigateTo(R.id.settingsFragment) }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateSelection(destination.id)
        }
        updateSelection(navController.currentDestination?.id ?: R.id.homeFragment)
    }

    private fun navigateTo(destinationId: Int) {
        if (navController.currentDestination?.id == destinationId) return
        val options = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(navController.graph.startDestinationId, false)
            .build()
        navController.navigate(destinationId, null, options)
        if (destinationId == R.id.playlistsFragment) {
            binding.navBar.navCenterCard.animate()
                .scaleX(1.12f).scaleY(1.12f).setDuration(120)
                .withEndAction {
                    binding.navBar.navCenterCard.animate()
                        .scaleX(1f).scaleY(1f).setDuration(180).start()
                }
                .start()
        }
    }

    private fun updateSelection(destinationId: Int) {
        binding.navBar.navHome.isSelected = destinationId == R.id.homeFragment
        binding.navBar.navPlaylists.isSelected = destinationId == R.id.playlistsFragment
        binding.navBar.navSettings.isSelected = destinationId == R.id.settingsFragment
    }
}

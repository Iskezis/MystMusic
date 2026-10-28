package com.iskezis.myst.ui.player

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.iskezis.myst.databinding.ActivityPlayerBinding
import com.iskezis.myst.ui.player.PlayerFragment
import com.iskezis.myst.util.AppLog

/**
 * Tela de reprodução em uma Activity própria.
 *
 * O mini player usa esta Activity em vez de navegar pelo NavController. Isso
 * evita telas duplicadas quando o usuário toca várias vezes no mini player e
 * mantém a tela do player independente do estado da navegação principal.
 * O [PlayerFragment] continua sendo usado e reconhece quando está sem NavHost
 * (caso desta Activity), fechando a Activity ao tocar na seta de voltar.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.d(AppLog.TAG_UI, "PlayerActivity.onCreate savedInstanceState=${savedInstanceState != null}")
        binding = ActivityPlayerBinding.inflate(layoutInflater)
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

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.playerHost, PlayerFragment())
                .commitNow()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(com.iskezis.myst.R.anim.screen_fade_in, com.iskezis.myst.R.anim.slide_out_down)
    }
}

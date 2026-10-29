package com.iskezis.myst.ui.common

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.iskezis.myst.util.MystTheme

/**
 * Aplica o overlay AMOLED e os ícones das barras do sistema antes de a tela
 * inflar o layout. Assim a troca de tema não mostra um frame na cor errada.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        MystTheme.applyAmoledOverlay(this)
        super.onCreate(savedInstanceState)
        MystTheme.applySystemBars(this)
    }
}

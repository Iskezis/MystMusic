package com.iskezis.myst.ui.common

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.iskezis.myst.MystApp
import com.iskezis.myst.util.MystTheme

/**
 * Aplica o overlay AMOLED e os ícones das barras do sistema antes de a tela
 * inflar o layout. Assim a troca de tema não mostra um frame na cor errada.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Na TV, o tema chega pelo pareamento e é gravado nas preferências
        // temporárias; esta chamada garante que a próxima tela já use o modo
        // certo. No celular o valor normalmente já coincide.
        (application as? MystApp)?.applyAppearanceNightMode()
        MystTheme.applyAmoledOverlay(this)
        super.onCreate(savedInstanceState)
        // O AppCompat pode aplicar o modo noturno durante o `super.onCreate`.
        // Reaplica o overlay depois disso para a TV já nascer AMOLED quando o
        // tema chega pelo pareamento no mesmo processo.
        MystTheme.applyAmoledOverlay(this)
        MystTheme.applySystemBars(this)
    }
}

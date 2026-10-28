package com.iskezis.myst.ui.common

/**
 * Estado de uma seção de conteúdo. Todas as telas usam os três estados
 * (carregando, vazio, erro) e nunca escondem uma falha de rede.
 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data object Empty : LoadState<Nothing>
    data class Data<T>(val value: T) : LoadState<T>
    data class Error(val messageRes: Int) : LoadState<Nothing>
}

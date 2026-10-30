package com.iskezis.myst.ui.stats

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.stats.StatsData
import com.iskezis.myst.data.stats.StatsPeriod
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.WEEK,
    val state: LoadState<StatsData> = LoadState.Loading,
)

class StatsViewModel(private val app: MystApp) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        load(StatsPeriod.WEEK)
    }

    fun selectPeriod(period: StatsPeriod) {
        if (period == _state.value.period && _state.value.state !is LoadState.Error) return
        load(period)
    }

    fun retry() {
        load(_state.value.period)
    }

    fun refresh() {
        load(_state.value.period)
    }

    private fun load(period: StatsPeriod) {
        job?.cancel()
        _state.update { it.copy(period = period, state = LoadState.Loading) }
        val userId = app.session.session?.userId ?: 0L
        if (userId <= 0L) {
            _state.update { it.copy(state = LoadState.Empty) }
            return
        }
        job = viewModelScope.launch {
            val result = try {
                val data = app.stats.load(userId, period)
                if (data.isEmpty) LoadState.Empty else LoadState.Data(data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha carregando estatísticas", t)
                LoadState.Error(R.string.stats_error_subtitle)
            }
            _state.update { current ->
                if (current.period == period) current.copy(state = result) else current
            }
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { StatsViewModel(app as MystApp) }
        }
    }
}

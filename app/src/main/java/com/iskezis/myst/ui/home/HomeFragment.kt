package com.iskezis.myst.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.iskezis.myst.R
import com.iskezis.myst.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = checkNotNull(_binding)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.homeState.stateIcon.setImageResource(R.drawable.ic_music)
        binding.homeState.stateTitle.setText(R.string.state_empty)
        binding.homeState.stateSubtitle.text = getString(R.string.section_personalize_hint)
        binding.homeState.stateSubtitle.visibility = View.VISIBLE
        binding.homeState.root.visibility = View.VISIBLE
        binding.homeRefresh.isRefreshing = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

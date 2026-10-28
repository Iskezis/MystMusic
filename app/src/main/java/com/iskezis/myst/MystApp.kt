package com.iskezis.myst

import android.app.Application
import com.iskezis.myst.data.account.AccountRepository
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.data.db.AppDatabase
import com.iskezis.myst.data.db.PlaylistRepository
import com.iskezis.myst.data.recommendations.RecommendationRepository
import com.iskezis.myst.data.youtube.InnerTubeClient
import com.iskezis.myst.data.youtube.YouTubeRepository
import com.iskezis.myst.player.PlayerController

class MystApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val session: SessionManager by lazy { SessionManager(this) }
    val innerTube: InnerTubeClient by lazy { InnerTubeClient(this, session) }
    val youtube: YouTubeRepository by lazy { YouTubeRepository(innerTube) }
    val accounts: AccountRepository by lazy { AccountRepository(this, database, session, innerTube) }
    val playlists: PlaylistRepository by lazy { PlaylistRepository(database) }
    val recommendations: RecommendationRepository by lazy { RecommendationRepository(database, youtube) }
    val playerController: PlayerController by lazy { PlayerController(this) }
}

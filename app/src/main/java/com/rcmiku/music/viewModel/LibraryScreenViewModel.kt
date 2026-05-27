package com.rcmiku.music.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rcmiku.ncmapi.api.account.AccountApi
import com.rcmiku.ncmapi.api.account.UserPlaylistType
import com.rcmiku.ncmapi.model.FavoriteSongResponse
import com.rcmiku.ncmapi.model.Playlist
import com.rcmiku.ncmapi.model.UserInfoBatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryScreenViewModel @Inject constructor() : ViewModel() {
    private val _userInfo = MutableStateFlow<UserInfoBatch?>(null)
    val userInfo: StateFlow<UserInfoBatch?> = _userInfo.asStateFlow()

    private val _favoriteSong = MutableStateFlow<FavoriteSongResponse?>(null)
    val favoriteSong: StateFlow<FavoriteSongResponse?> = _favoriteSong.asStateFlow()

    private val _userPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val userPlaylists: StateFlow<List<Playlist>> = _userPlaylists.asStateFlow()

    init {
        observeUserIdChanges()
    }

    fun fetchUserInfo() {
        viewModelScope.launch {
            _userInfo.value = AccountApi.accountInfo().getOrNull()
        }
    }

    private fun fetchFavoriteSong(userId: Long) {
        viewModelScope.launch {
            _favoriteSong.value = AccountApi.favoriteSong(userId).getOrNull()
        }
    }

    private fun fetchUserPlaylists(userId: Long) {
        viewModelScope.launch {
            _userPlaylists.value = AccountApi.userPlaylist(
                userId = userId,
                userPlaylistType = UserPlaylistType.CREATE
            ).getOrNull()?.data?.playlist.orEmpty()
        }
    }

    fun clear() {
        _userInfo.value = null
        _favoriteSong.value = null
        _userPlaylists.value = emptyList()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeUserIdChanges() {
        viewModelScope.launch {
            userInfo
                .mapLatest { it?.account?.profile?.userId }
                .distinctUntilChanged()
                .collectLatest { userId ->
                    if (userId == null) {
                        _favoriteSong.value = null
                        _userPlaylists.value = emptyList()
                    } else {
                        fetchFavoriteSong(userId)
                        fetchUserPlaylists(userId)
                    }
                }
        }
    }
}

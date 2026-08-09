package com.simone.jarvismobile.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.navigation.FavoriteKind
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.navigation.NavFavoriteEntity
import com.simone.jarvismobile.navigation.NavigationRepository
import com.simone.jarvismobile.navigation.PlaceSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the favourites/history screen. All data is local (spec §10, §21). */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val places: PlaceSearchRepository,
    private val navigation: NavigationRepository,
) : ViewModel() {

    private val _favorites = MutableStateFlow<List<NavFavoriteEntity>>(emptyList())
    val favorites: StateFlow<List<NavFavoriteEntity>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<List<Pair<String, LatLng>>>(emptyList())
    val history: StateFlow<List<Pair<String, LatLng>>> = _history.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _favorites.value = places.favoriteEntities()
        _history.value = places.history()
    }

    /** Saves the current GPS position as HOME/WORK. */
    fun setHere(kind: FavoriteKind, label: String) = viewModelScope.launch {
        val loc = navigation.fix.value?.location
        if (loc == null) {
            _message.value = "Nessuna posizione GPS: apri la navigazione per acquisirla."
            return@launch
        }
        places.setFavorite(kind, label, loc)
        _message.value = "$label salvato."
        refresh()
    }

    fun delete(id: String) = viewModelScope.launch { places.deleteFavorite(id); refresh() }

    /** Routes to a saved place and brings the map to the front. */
    fun navigateTo(location: LatLng, label: String) {
        navigation.startNavigation(location)
        navigation.requestOpenScreen()
        viewModelScope.launch { places.addHistory(label, location) }
    }

    fun clearMessage() { _message.value = null }
}

package com.simone.jarvismobile.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.navigation.FavoriteKind
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.navigation.NavFavoriteEntity

/** Preferiti e cronologia della navigazione. Tutto locale (§10, §21). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preferiti e cronologia") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.setHere(FavoriteKind.HOME, "Casa") }, modifier = Modifier.weight(1f)) {
                    Text("Imposta Casa qui")
                }
                OutlinedButton(onClick = { viewModel.setHere(FavoriteKind.WORK, "Lavoro") }, modifier = Modifier.weight(1f)) {
                    Text("Imposta Lavoro qui")
                }
            }

            Text("Preferiti", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            if (favorites.isEmpty()) {
                Text("Nessun preferito. Usa i pulsanti sopra, o di' «ricorda questo posto» in navigazione.", style = MaterialTheme.typography.bodySmall)
            }

            Text("Cronologia", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(favorites, key = { it.id }) { fav ->
                    FavoriteRow(
                        fav = fav,
                        onNavigate = { viewModel.navigateTo(LatLng(fav.lat, fav.lon), fav.label) },
                        onDelete = { viewModel.delete(fav.id) },
                    )
                }
                items(history, key = { it.first + it.second.lat }) { (label, loc) ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { viewModel.navigateTo(loc, label) }) {
                                Icon(Icons.Filled.Navigation, contentDescription = "Naviga")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(fav: NavFavoriteEntity, onNavigate: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(fav.label.ifBlank { fav.kind }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fav.kind.lowercase(), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onNavigate) { Icon(Icons.Filled.Navigation, contentDescription = "Naviga") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Elimina") }
        }
    }
}

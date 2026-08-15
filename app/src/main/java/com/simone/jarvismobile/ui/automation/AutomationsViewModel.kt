package com.simone.jarvismobile.ui.automation

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.simone.jarvismobile.automation.AutomationRepository
import com.simone.jarvismobile.automation.AutomationRunner
import com.simone.jarvismobile.automation.LocationTriggers
import com.simone.jarvismobile.automation.PlaceRepository
import com.simone.jarvismobile.core.automation.Automation
import com.simone.jarvismobile.core.automation.AutomationCodec
import com.simone.jarvismobile.core.automation.AutomationPhrase
import com.simone.jarvismobile.core.places.Place
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val repository: AutomationRepository,
    private val runner: AutomationRunner,
    private val placeRepository: PlaceRepository,
    private val locationTriggers: LocationTriggers,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val automations: StateFlow<List<Automation>> = repository.automations
    val places: StateFlow<List<Place>> = placeRepository.places

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    init {
        viewModelScope.launch {
            runCatching {
                placeRepository.reload()
                repository.reload()
            }
        }
    }

    fun hasForegroundLocation(): Boolean = locationTriggers.hasForegroundLocation()

    fun hasBackgroundLocation(): Boolean = locationTriggers.hasBackgroundLocation()

    /** Arrival rules whose place is not defined yet, so they cannot arm. */
    fun rulesMissingPlace(): List<Automation> = locationTriggers.rulesMissingPlace()

    /** Re-arm after the user changes the location permission from the OS dialog. */
    fun onLocationPermissionChanged() = viewModelScope.launch {
        runCatching {
            placeRepository.reload()
            repository.reload()
        }
    }

    /**
     * Saves the phone's current position under [name]. Reading the location once,
     * here, is the only time JARVIS asks the OS where the phone is; from then on
     * the geofence is watched by the system, not polled by the app.
     */
    @SuppressLint("MissingPermission")
    fun saveCurrentLocationAs(name: String, radiusMeters: Int) {
        val clean = name.trim()
        if (!Place.isValidName(clean)) {
            _message.value = "Dai un nome al luogo, per esempio «casa»."
            return
        }
        if (!locationTriggers.hasForegroundLocation()) {
            _message.value = "Serve il permesso di posizione per salvare un luogo."
            return
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { location ->
                    if (location == null) {
                        _message.value = "Posizione non disponibile ora. Riprova all'aperto."
                        return@addOnSuccessListener
                    }
                    viewModelScope.launch {
                        val place = Place(
                            name = clean,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            radiusMeters = Place.clampRadius(radiusMeters),
                        )
                        _message.value = if (placeRepository.save(place)) {
                            "Luogo salvato: ${place.name} (raggio ${place.radiusMeters} m)."
                        } else {
                            "Non sono riuscito a salvare il luogo."
                        }
                    }
                }
                .addOnFailureListener {
                    _message.value = "Non ho potuto leggere la posizione."
                }
        } catch (_: SecurityException) {
            _message.value = "Serve il permesso di posizione per salvare un luogo."
        }
    }

    fun removePlace(place: Place) = viewModelScope.launch {
        if (placeRepository.remove(place.name)) {
            _message.value = "Luogo eliminato: ${place.name}."
        }
    }

    /**
     * Creates a rule from a plain Italian sentence — the same grammar the chat
     * understands, so there is one thing to learn rather than a form and a
     * phrasebook.
     */
    fun create(phrase: String) = viewModelScope.launch {
        val parsed = AutomationPhrase.parse(phrase)
        if (parsed == null) {
            _message.value = "Non ho capito la regola. Prova: «ogni giorno alle 8 " +
                "ricordami di prendere le vitamine», «quando la batteria scende " +
                "sotto il 20% avvisami» oppure «quando arrivo a casa ricordami di " +
                "annaffiare»."
            return@launch
        }
        _message.value = if (repository.add(parsed)) {
            "Creata: ${AutomationCodec.describe(parsed)}"
        } else {
            "Non sono riuscito a salvare la regola."
        }
    }

    fun toggle(automation: Automation) = viewModelScope.launch {
        repository.setEnabled(automation.id, !automation.enabled)
    }

    fun delete(automation: Automation) = viewModelScope.launch {
        if (repository.remove(automation.id)) {
            _message.value = "Eliminata: ${AutomationCodec.describe(automation)}"
        }
    }

    /** Runs a rule now, so its effect can be judged without waiting for it. */
    fun testRun(automation: Automation) = viewModelScope.launch {
        _message.value = if (runner.run(automation)) {
            "Eseguita adesso."
        } else {
            "Non è stato possibile eseguirla: controlla i permessi di notifica."
        }
    }

    fun clearMessage() {
        _message.value = ""
    }
}

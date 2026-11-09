package com.simone.jarvismobile.core.offlinehub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalAppsTest {
    @Test fun kiwixNotInstalled() = assertEquals(AppDetection.NotInstalled, ExternalAppDetector.detect(ExternalApp.KIWIX, setOf("com.other")))
    @Test fun kiwixPlayVariant() = assertEquals(AppDetection.Single("org.kiwix.kiwixmobile"), ExternalAppDetector.detect(ExternalApp.KIWIX, setOf("org.kiwix.kiwixmobile")))
    @Test fun kiwixStandaloneVariant() = assertEquals(AppDetection.Single("org.kiwix.kiwixmobile.standalone"), ExternalAppDetector.detect(ExternalApp.KIWIX, setOf("org.kiwix.kiwixmobile.standalone")))

    @Test fun bothKiwixVariantsAskTheUserUnlessOneIsPreferred() {
        val both = setOf("org.kiwix.kiwixmobile", "org.kiwix.kiwixmobile.standalone")
        assertIs<AppDetection.Choose>(ExternalAppDetector.detect(ExternalApp.KIWIX, both))
        assertEquals(AppDetection.Single("org.kiwix.kiwixmobile.standalone"), ExternalAppDetector.detect(ExternalApp.KIWIX, both, "org.kiwix.kiwixmobile.standalone"))
        // a stale preference for an uninstalled variant does not win
        assertIs<AppDetection.Choose>(ExternalAppDetector.detect(ExternalApp.KIWIX, both, "org.kiwix.kiwixmobile.nightly"))
    }

    @Test fun organicMapsVariants() {
        assertEquals(AppDetection.NotInstalled, ExternalAppDetector.detect(ExternalApp.ORGANIC_MAPS, emptySet()))
        assertEquals(AppDetection.Single("app.organicmaps"), ExternalAppDetector.detect(ExternalApp.ORGANIC_MAPS, setOf("app.organicmaps")))
        assertIs<AppDetection.Choose>(ExternalAppDetector.detect(ExternalApp.ORGANIC_MAPS, setOf("app.organicmaps", "app.organicmaps.web")))
    }

    @Test fun everyOutcomeHasAnItalianMessageAndNeverPromisesAnInstall() {
        listOf(
            LaunchOutcome.NotInstalled(ExternalApp.KIWIX), LaunchOutcome.NotInstalled(ExternalApp.ORGANIC_MAPS),
            LaunchOutcome.NotHandled(ExternalApp.ORGANIC_MAPS, "i percorsi"), LaunchOutcome.NeedsChoice(ExternalApp.KIWIX, ExternalApp.KIWIX.packages),
            LaunchOutcome.Failed("x"), LaunchOutcome.Invalid("no"), LaunchOutcome.Launched("p", "ok"),
        ).forEach { assertTrue(LaunchMessages.text(it).isNotBlank()) }
        assertTrue("non installa app da solo" in LaunchMessages.text(LaunchOutcome.NotInstalled(ExternalApp.KIWIX)))
    }
}

class KiwixZimTest {
    @Test fun zimIsOpenedWithAViewIntentOnTheContentUriForTheChosenPackage() {
        val s = KiwixIntents.openZim("content://docs/zim/wiki.zim", "org.kiwix.kiwixmobile.standalone")
        assertEquals("android.intent.action.VIEW", s.action); assertEquals("org.kiwix.kiwixmobile.standalone", s.packageId)
        assertEquals("application/octet-stream", s.mimeType)
    }

    @Test fun theFallbackTellsTheUserHowToPickTheArchiveInKiwix() = assertTrue("Libreria" in KiwixIntents.OPEN_ZIM_FALLBACK)

    @Test fun zimMagicNumber() {
        assertTrue(ZimHeader.looksLikeZim(byteArrayOf(0x5A, 0x49, 0x4D, 0x04, 9, 9)))
        assertFalse(ZimHeader.looksLikeZim("%PDF".toByteArray()))
        assertFalse(ZimHeader.looksLikeZim(byteArrayOf(0x5A, 0x49)))
    }

    @Test fun accessClassification() {
        val ok = byteArrayOf(0x5A, 0x49, 0x4D, 0x04)
        assertEquals(ZimAccess.READABLE, ZimClassifier.access(true, true, true, ok))
        assertEquals(ZimAccess.PERMISSION_REVOKED, ZimClassifier.access(false, true, true, ok)) // SAF grant revoked
        assertEquals(ZimAccess.MISSING, ZimClassifier.access(true, false, false, null))
        assertEquals(ZimAccess.UNREADABLE, ZimClassifier.access(true, true, false, null))
        assertEquals(ZimAccess.UNREADABLE, ZimClassifier.access(true, true, true, null)) // header could not be read
        assertEquals(ZimAccess.NOT_A_ZIM, ZimClassifier.access(true, true, true, "ABCD".toByteArray()))
    }

    @Test fun zimNamesAndSizes() {
        assertTrue(ZimClassifier.isZimName("wikipedia_it_all.ZIM")); assertTrue(ZimClassifier.isZimName("big.zimaa"))
        assertFalse(ZimClassifier.isZimName("notes.txt"))
        assertEquals("3.0 GB", ZimClassifier.formatSize(3L * 1024 * 1024 * 1024)); assertEquals("512 KB", ZimClassifier.formatSize(512L * 1024))
        assertEquals("dimensione sconosciuta", ZimClassifier.formatSize(0))
    }

    @Test fun aZimIsConsultableInKiwixButNeverQueryableByTheAi() {
        val e = ZimEntry("content://x", "wiki.zim", 10, ZimAccess.READABLE, true)
        assertFalse(e.aiQueryable)
        assertTrue("non posso leggerne il contenuto" in ZimPolicy.NOT_QUERYABLE)
        assertTrue("non ne legge il contenuto" in ZimPolicy.LIBRARY_BANNER)
        assertTrue("non vengono copiati" in ZimPolicy.NO_COPY)
    }

    @Test fun mapsProviderPersistence() {
        MapsProvider.entries.forEach { assertEquals(it, MapsProvider.fromStored(it.name)) }
        assertEquals(MapsProvider.GOOGLE_MAPS, MapsProvider.fromStored(null)) // unchanged default: no silent switch
        assertEquals(MapsProvider.GOOGLE_MAPS, MapsProvider.fromStored("garbage"))
    }
}

class OrganicMapsLinksTest {
    private val rome = GeoPoint(41.9028, 12.4964)
    private val milan = GeoPoint(45.4642, 9.19)

    @Test fun routeUsesAllFiveParametersInTheOrderOrganicMapsRequires() {
        val s = OrganicMapsLinks.route(rome, "Posizione attuale", milan, "Duomo di Milano", RouteMode.CAR, "app.organicmaps")
        assertEquals("om://route?sll=41.902800,12.496400&saddr=Posizione%20attuale&dll=45.464200,9.190000&daddr=Duomo%20di%20Milano&type=vehicle", s.uri)
        val keys = Regex("[?&](\\w+)=").findAll(s.uri).map { it.groupValues[1] }.toList()
        assertEquals(listOf("sll", "saddr", "dll", "daddr", "type"), keys)
    }

    @Test fun routeModes() {
        assertEquals(listOf("vehicle", "pedestrian", "bicycle", "transit"), RouteMode.entries.map { it.omType })
    }

    @Test fun showPointPutsLlFirstThenName() {
        val specs = OrganicMapsLinks.show(rome, "Colosseo", "app.organicmaps")
        assertEquals("om://map?ll=41.902800,12.496400&n=Colosseo", specs[0].uri)
        assertTrue(specs[1].uri.startsWith("geo:41.902800,12.496400")) // standard Android fallback
    }

    @Test fun searchHasTheOmLinkAndAGeoFallbackAndNothingOnline() {
        val specs = OrganicMapsLinks.search("farmacia h24", "app.organicmaps")
        assertEquals("om://search?query=farmacia%20h24", specs[0].uri)
        assertEquals("geo:0,0?q=farmacia%20h24", specs[1].uri)
        specs.forEach { assertFalse(it.uri.startsWith("http") || "google" in it.uri) }
        assertTrue(OrganicMapsLinks.search("  ", null).isEmpty())
    }

    @Test fun encodingIsUtf8PercentEncoding() {
        assertEquals("caff%C3%A8%20%26%20t%C3%A8", OrganicMapsLinks.enc("caffè & tè"))
        assertEquals("a-b_c.d~e", OrganicMapsLinks.enc("a-b_c.d~e"))
    }

    @Test fun coordinatesAreValidated() {
        assertEquals(rome, CoordinateParser.parse("41.9028, 12.4964"))
        assertEquals(rome, CoordinateParser.parse("41,9028 12,4964"))
        assertEquals(GeoPoint(-33.8688, 151.2093), CoordinateParser.parse("-33.8688;151.2093"))
        assertNull(CoordinateParser.parse("91.0, 10.0")); assertNull(CoordinateParser.parse("10.0, 181.0"))
        assertNull(CoordinateParser.parse("abc")); assertNull(CoordinateParser.parse("41.9028"))
        assertFalse(GeoPoint(Double.NaN, 1.0).isValid)
    }

    @Test fun invalidCoordinatesCannotBuildALink() {
        assertTrue(runCatching { OrganicMapsLinks.show(GeoPoint(100.0, 0.0), null, null) }.isFailure)
        assertTrue(runCatching { OrganicMapsLinks.route(GeoPoint(0.0, 0.0), "", GeoPoint(0.0, 200.0), "", RouteMode.CAR, null) }.isFailure)
    }
}

class OrganicMapsPlannerTest {
    private val rome = GeoPoint(41.9028, 12.4964)
    private val milan = GeoPoint(45.4642, 9.19)
    private val pkg = "app.organicmaps"

    @Test fun searchIsALocalOrganicMapsSearch() {
        val p = OrganicMapsPlanner.plan(OrganicRequest.Search("farmacia"), pkg) as OrganicPlan.Launch
        assertTrue(p.candidates.first().uri.startsWith("om://search?query=farmacia"))
    }

    @Test fun showInvalidCoordinatesIsRefusedNotSent() =
        assertIs<OrganicPlan.Refused>(OrganicMapsPlanner.plan(OrganicRequest.Show(GeoPoint(95.0, 0.0), null), pkg))

    @Test fun routeWithVerifiedDestinationAndOriginUsesOmRouteAndSaysItIsAPreview() {
        val p = OrganicMapsPlanner.plan(OrganicRequest.Route(milan, "Milano", RouteMode.BIKE, rome), pkg) as OrganicPlan.Launch
        assertTrue(p.candidates.single().uri.startsWith("om://route?sll="))
        assertTrue(p.candidates.single().uri.endsWith("type=bicycle"))
        assertTrue("anteprima" in p.message)
    }

    @Test fun routeWithoutOriginFallsBackToShowingTheDestinationAndExplainsWhy() {
        val p = OrganicMapsPlanner.plan(OrganicRequest.Route(milan, "Milano", RouteMode.CAR, null), pkg) as OrganicPlan.Launch
        assertTrue(p.candidates.first().uri.startsWith("om://map?ll=45.464200"))
        assertTrue("punto di partenza" in p.message)
    }

    @Test fun anAmbiguousDestinationNeverGetsInventedCoordinates() {
        val p = OrganicMapsPlanner.plan(OrganicRequest.Route(null, "via Roma 5", RouteMode.CAR, rome), pkg) as OrganicPlan.Launch
        assertTrue(p.candidates.all { "ll=" !in it.uri && "dll" !in it.uri && !it.uri.matches(Regex(".*\\d+\\.\\d{4,}.*")) })
        assertTrue(p.candidates.first().uri.startsWith("om://search?query=via%20Roma%205"))
        assertTrue("coordinate verificate" in p.message)
    }

    @Test fun routeWithInvalidDestinationIsRefused() =
        assertIs<OrganicPlan.Refused>(OrganicMapsPlanner.plan(OrganicRequest.Route(GeoPoint(0.0, 999.0), "x", RouteMode.CAR, rome), pkg))

    @Test fun noPlanEverReachesTheNetworkOrAGeocoder() {
        val requests = listOf(
            OrganicRequest.Open, OrganicRequest.Search("bar"), OrganicRequest.Show(rome, "Roma"),
            OrganicRequest.Route(milan, "Milano", RouteMode.WALK, rome), OrganicRequest.Route(null, "casa di Luca", RouteMode.CAR, null),
        )
        requests.forEach { r ->
            val p = OrganicMapsPlanner.plan(r, pkg)
            if (p is OrganicPlan.Launch) p.candidates.forEach {
                assertFalse(it.uri.startsWith("http") || "google" in it.uri || "nominatim" in it.uri, it.uri)
            }
        }
    }

    @Test fun destinationFixUsesOnlySavedOrExplicitCoordinates() {
        assertIs<DestinationFix.Verified>(DestinationFix.of("casa", rome to "Casa"))
        assertIs<DestinationFix.Verified>(DestinationFix.of("41.9028, 12.4964", null))
        assertIs<DestinationFix.TextOnly>(DestinationFix.of("Via Roma 5", null))
        assertIs<DestinationFix.TextOnly>(DestinationFix.of("casa", GeoPoint(500.0, 0.0) to "Casa")) // corrupt saved place is not trusted
    }
}

class OfflineHubParserTest {
    private fun p(s: String) = OfflineHubParser.parse(s)

    @Test fun openApps() {
        assertEquals(HubCommand.OpenOrganicMaps, p("Apri Organic Maps"))
        assertEquals(HubCommand.OpenKiwix, p("apri Kiwix."))
        assertEquals(HubCommand.OpenLibrary, p("Apri la biblioteca offline"))
        assertEquals(HubCommand.OpenOfflineNavigator, p("Apri il navigatore offline"))
    }

    @Test fun pharmacyNearMeOnOrganicMaps() =
        assertEquals(HubCommand.OrganicSearch("farmacia"), p("Cerca una farmacia vicino a me su Organic Maps"))

    @Test fun showCoordinatesOnOrganicMaps() {
        val c = p("Mostra 41.9028, 12.4964 su Organic Maps") as HubCommand.OrganicShow
        assertEquals(GeoPoint(41.9028, 12.4964), c.point)
        val bad = p("Mostra 95.0, 12.4964 su Organic Maps") as HubCommand.OrganicShow
        assertNull(bad.point)
    }

    @Test fun routeWithModes() {
        assertEquals(HubCommand.OrganicRoute("casa", RouteMode.CAR), p("Portami a casa con Organic Maps"))
        assertEquals(HubCommand.OrganicRoute("Via Roma 5", RouteMode.WALK), p("Portami a Via Roma 5 con Organic Maps a piedi"))
        assertEquals(HubCommand.OrganicRoute("Torino", RouteMode.BIKE), p("naviga verso Torino con Organic Maps in bicicletta"))
    }

    @Test fun kiwixSearchAndZimQuestions() {
        assertEquals(HubCommand.KiwixSearch("Leonardo da Vinci"), p("Cerca Leonardo da Vinci su Kiwix"))
        assertTrue(p("Cosa dice l'archivio ZIM su Leonardo?") is HubCommand.ZimQuestion)
        assertTrue(p("Chi era Dante secondo Wikipedia offline?") == null || p("Chi era Dante secondo Wikipedia offline?") is HubCommand.ZimQuestion)
    }

    @Test fun ordinaryChatAndOtherFeaturesAreNeverCaptured() {
        listOf(
            "Ciao", "Portami a casa", "Cerca una pizzeria", "Cerca nei miei documenti la polizza", "Apri la camera", "Mostra le note sulla moto",
            "Apri Spotify", "Che ore sono", "Cerca la ricetta del tiramisù su internet", "Crea una nota chiamata Kiwix",
        ).forEach { assertNull(p(it), it) }
    }
}

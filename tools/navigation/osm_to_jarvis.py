#!/usr/bin/env python3
"""
osm_to_jarvis.py — genera i dati offline per JARVIS Offline Navigation.

Da un estratto OpenStreetMap (.osm.pbf) produce i file che l'app JARVIS scarica
e usa senza rete:

  routing.json    la rete stradale (grafo nodi + archi) per il calcolo percorsi
  search.json     l'elenco luoghi/POI per la ricerca "portami a ..." (leggibile)
  search.sqlite   lo stesso indice, pre-costruito in SQLite+FTS5: l'app lo apre
                   in sola lettura invece di ricostruire l'indice sul telefono
  regions.json    (opzionale) il manifest del catalogo, con gli URL da ospitare

Schema di search.sqlite (vedi anche README.md):
  meta(key, value)                    schema_version, region_id
  places (FTS5): ref_id, name, address, category, region_id, lat, lon, importance
    — ref_id/category/region_id/lat/lon/importance sono UNINDEXED (dati, non
    testo cercabile); name/address sono le colonne su cui gira il full-text.

NON gira sul telefono: si esegue una volta sul computer. La mappa vettoriale
(.pmtiles) NON viene creata qui (serve un tile-builder tipo planetiler/tilemaker,
oppure una .pmtiles gia' pronta) — vedi README.md.

Due modalita':
  * completa : passi anche --pmtiles <file>; lo script ne calcola dimensione e
               SHA-256 e lo mette nel manifest (mappa + percorsi + ricerca).
  * leggera  : niente --pmtiles; generi solo percorsi + ricerca e nel manifest
               metti l'URL di una .pmtiles gia' ospitata (--pmtiles-url).

Requisiti:  pip install osmium
Estratto Lazio:  https://download.geofabrik.de/europe/italy/centro-latest.osm.pbf
  (Geofabrik pubblica per macro-aree; "Centro" include il Lazio. In alternativa
   ritaglia un bbox del Lazio con osmium extract.)

Esempio (leggero):
  python osm_to_jarvis.py --pbf centro-latest.osm.pbf \\
      --region-id lazio --region-name "Lazio" --out ./out \\
      --base-url https://tuo-sito.example/maps \\
      --pmtiles-url https://tuo-sito.example/maps/lazio.pmtiles

Esempio (completo):
  python osm_to_jarvis.py --pbf centro-latest.osm.pbf \\
      --region-id lazio --region-name "Lazio" --out ./out \\
      --pmtiles ./lazio.pmtiles \\
      --base-url https://tuo-sito.example/maps
"""

import argparse
import hashlib
import json
import os
import sqlite3
import sys
from collections import Counter

try:
    import osmium
except ImportError:
    sys.exit("Manca pyosmium. Installa con:  pip install osmium")

# Bumped only if the search.sqlite table shape changes incompatibly — mirrors
# RegionMetadata.SUPPORTED_REGION_SCHEMA_VERSION on the app side, so a reader
# can refuse an old/new-format database instead of misreading its columns.
SEARCH_SCHEMA_VERSION = 1

# --- classificazione strade -------------------------------------------------

# Classi stradali percorribili in auto e velocita' nominale (km/h) di default.
DRIVABLE_SPEED = {
    "motorway": 110, "trunk": 90, "primary": 70, "secondary": 60,
    "tertiary": 50, "unclassified": 50, "residential": 40, "living_street": 20,
    "service": 20, "road": 40,
    "motorway_link": 60, "trunk_link": 55, "primary_link": 50,
    "secondary_link": 45, "tertiary_link": 40,
}

# Categoria app (PlaceCategory) per i tag OSM piu' comuni.
AMENITY_CATEGORY = {
    "fuel": "FUEL", "parking": "PARKING", "restaurant": "RESTAURANT",
    "fast_food": "RESTAURANT", "cafe": "BAR", "bar": "BAR", "pub": "BAR",
    "pharmacy": "PHARMACY", "hospital": "HOSPITAL", "clinic": "HOSPITAL",
    "police": "POLICE",
}
PLACE_CATEGORY = {
    "city": ("CITY", 0.95), "town": ("TOWN", 0.75),
    "village": ("VILLAGE", 0.5), "hamlet": ("VILLAGE", 0.45),
    "suburb": ("TOWN", 0.6), "neighbourhood": ("TOWN", 0.5),
}


def road_speed(tags, klass):
    ms = tags.get("maxspeed", "")
    if ms:
        num = "".join(ch for ch in ms if ch.isdigit())
        if num:
            try:
                v = int(num)
                if 5 <= v <= 140:
                    return v
            except ValueError:
                pass
    return DRIVABLE_SPEED.get(klass, 40)


def oneway_flag(tags):
    v = tags.get("oneway", "").lower()
    if v in ("yes", "true", "1"):
        return 1
    if v in ("-1", "reverse"):
        return -1
    return 0


# --- lettura OSM ------------------------------------------------------------

class JarvisHandler(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.coords = {}          # node ref -> (lat, lon), solo nodi su strade
        self.ways = []            # (refs, klass, speed, oneway, toll, ferry, name)
        self.usage = Counter()    # quante volte un nodo compare su strade
        self.places = []          # POI/luoghi: dict per search.json
        self._pid = 0

    def next_id(self):
        self._pid += 1
        return self._pid

    def way(self, w):
        tags = {t.k: t.v for t in w.tags}
        highway = tags.get("highway")
        route = tags.get("route")
        name = tags.get("name", "")

        # Strade percorribili -> grafo di routing.
        if highway in DRIVABLE_SPEED or route == "ferry":
            refs = []
            for n in w.nodes:
                if not n.location.valid():
                    continue
                refs.append(n.ref)
                self.coords[n.ref] = (n.location.lat, n.location.lon)
            if len(refs) >= 2:
                klass = highway or "ferry"
                speed = 8 if route == "ferry" else road_speed(tags, klass)
                ow = oneway_flag(tags)
                toll = tags.get("toll", "").lower() in ("yes", "true", "1")
                ferry = route == "ferry"
                for r in refs:
                    self.usage[r] += 1
                self.usage[refs[0]] += 1   # endpoints sempre nodi del grafo
                self.usage[refs[-1]] += 1
                self.ways.append((refs, klass, speed, ow, toll, ferry, name))

        # Vie con nome -> voce di ricerca (STREET), punto rappresentativo.
        if highway in DRIVABLE_SPEED and name:
            mid = w.nodes[len(w.nodes) // 2]
            if mid.location.valid():
                self._add_place(name, "STREET", mid.location.lat, mid.location.lon, 0.2, name)

        # POI mappati (aree con nome): ospedali, hotel, ecc.
        cat = self._category(tags)
        if cat and name:
            lat, lon = self._way_centroid(w)
            if lat is not None:
                self._add_place(name, cat, lat, lon, 0.4, self._address(tags, name))
        elif self._has_address(tags):
            # Un edificio con civico ma senza nome/categoria (la stragrande
            # maggioranza delle case) — indicizzato solo come indirizzo, cosi'
            # "Via Cristoforo Colombo 100" si trova anche se non e' un POI.
            lat, lon = self._way_centroid(w)
            if lat is not None:
                self._add_address_place(tags, lat, lon)

    def node(self, n):
        tags = {t.k: t.v for t in n.tags}
        name = tags.get("name", "")
        if name:
            place = tags.get("place")
            if place in PLACE_CATEGORY:
                cat, imp = PLACE_CATEGORY[place]
                self._add_place(name, cat, n.location.lat, n.location.lon, imp, name)
                return
            cat = self._category(tags)
            if cat:
                self._add_place(name, cat, n.location.lat, n.location.lon, 0.4, self._address(tags, name))
                return
        # Nodi indirizzo puri (comuni in OSM: addr:housenumber/addr:street senza
        # alcun name) — spec §3/§12 "Via Cristoforo Colombo 100".
        if self._has_address(tags):
            self._add_address_place(tags, n.location.lat, n.location.lon)

    # --- helper ---
    def _category(self, tags):
        a = tags.get("amenity")
        if a in AMENITY_CATEGORY:
            return AMENITY_CATEGORY[a]
        if tags.get("tourism") in ("hotel", "hostel", "guest_house", "motel"):
            return "HOTEL"
        if tags.get("tourism") in ("attraction", "museum", "viewpoint", "artwork"):
            return "TOURISM"
        if tags.get("shop"):
            return "SHOP"
        if tags.get("railway") in ("station", "halt"):
            return "STATION"
        if tags.get("aeroway") == "aerodrome":
            return "AIRPORT"
        return None

    def _address(self, tags, fallback):
        """Human-readable address string: 'Via X 12, 00184 Roma'. Housenumber and
        postcode are folded in (spec §3) so both display and the ranker's
        address-completeness bonus (PlaceSearchRanker, comma-separated parts) see
        them, without needing separate structured columns."""
        street = tags.get("addr:street", "")
        housenumber = tags.get("addr:housenumber", "")
        postcode = tags.get("addr:postcode", "")
        city = tags.get("addr:city", "")
        street_part = f"{street} {housenumber}".strip() if street else ""
        locality_part = " ".join(p for p in (postcode, city) if p)
        parts = [p for p in (street_part, locality_part) if p]
        return ", ".join(parts) if parts else fallback

    def _has_address(self, tags):
        return bool(tags.get("addr:housenumber")) and bool(tags.get("addr:street"))

    def _add_address_place(self, tags, lat, lon):
        street = tags["addr:street"]
        housenumber = tags["addr:housenumber"]
        label = f"{street} {housenumber}"
        # Low importance: a bare address is a fallback match, never meant to
        # outrank a real POI/landmark with the same tokens in its name.
        self._add_place(label, "ADDRESS", lat, lon, 0.15, self._address(tags, label))

    def _way_centroid(self, w):
        lats, lons = [], []
        for n in w.nodes:
            if n.location.valid():
                lats.append(n.location.lat)
                lons.append(n.location.lon)
        if not lats:
            return None, None
        return sum(lats) / len(lats), sum(lons) / len(lons)

    def _add_place(self, name, category, lat, lon, importance, address):
        self.places.append({
            "id": f"p{self.next_id()}",
            "name": name,
            "category": category,
            "lat": round(lat, 7),
            "lon": round(lon, 7),
            "importance": importance,
            "address": address,
        })


# --- costruzione grafo di routing -------------------------------------------

def build_routing(handler):
    """Spezza ogni strada nei suoi incroci -> nodi + archi del grafo."""
    graph_id = {}      # osm ref -> id progressivo del grafo
    nodes = []

    def node_id(ref):
        if ref not in graph_id:
            graph_id[ref] = len(graph_id) + 1
            lat, lon = handler.coords[ref]
            nodes.append({"id": graph_id[ref], "lat": round(lat, 7), "lon": round(lon, 7)})
        return graph_id[ref]

    links = []
    for refs, klass, speed, ow, toll, ferry, name in handler.ways:
        # Punti di taglio: estremi + nodi condivisi con altre strade (incroci).
        segment = [refs[0]]
        for r in refs[1:]:
            segment.append(r)
            is_junction = handler.usage[r] > 1 or r == refs[-1]
            if is_junction and len(segment) >= 2:
                _emit_link(handler, node_id, links, segment, klass, speed, ow, toll, ferry, name)
                segment = [r]
    return {"nodes": nodes, "links": links}


def _emit_link(handler, node_id, links, seg_refs, klass, speed, ow, toll, ferry, name):
    geometry = [[round(handler.coords[r][1], 7), round(handler.coords[r][0], 7)] for r in seg_refs]
    a = node_id(seg_refs[0])
    b = node_id(seg_refs[-1])
    if a == b:
        return
    oneway = ow != 0
    if ow == -1:
        geometry = list(reversed(geometry))
        a, b = b, a
    links.append({
        "from": a, "to": b, "name": name, "class": klass,
        "speed": speed, "oneway": oneway, "toll": toll, "ferry": ferry,
        "geometry": geometry,
    })


# --- output -----------------------------------------------------------------

def write_sqlite(places, region_id, path):
    """Pre-built SQLite+FTS5 search index (spec §2, §4): generated here on the
    PC, opened read-only by the app — no OSM/JSON parsing or index-building ever
    runs on the phone. One file per region, same shape as search.json's entries."""
    if os.path.exists(path):
        os.remove(path)
    conn = sqlite3.connect(path)
    try:
        conn.execute("PRAGMA journal_mode=DELETE")  # ship a single file, no -wal/-shm sidecars
        conn.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        conn.execute("INSERT INTO meta VALUES ('schema_version', ?)", (str(SEARCH_SCHEMA_VERSION),))
        conn.execute("INSERT INTO meta VALUES ('region_id', ?)", (region_id,))
        try:
            conn.execute(
                """
                CREATE VIRTUAL TABLE places USING fts5(
                    ref_id UNINDEXED,
                    name,
                    address,
                    category UNINDEXED,
                    region_id UNINDEXED,
                    lat UNINDEXED,
                    lon UNINDEXED,
                    importance UNINDEXED
                )
                """
            )
        except sqlite3.OperationalError as e:
            conn.close()
            os.remove(path)
            sys.exit(
                f"Il modulo sqlite3 di questo Python non supporta FTS5 ({e}). "
                "Serve un Python la cui libreria SQLite sia compilata con FTS5 "
                "(vale per la maggior parte delle build recenti di python.org/Linux)."
            )
        conn.executemany(
            "INSERT INTO places (ref_id, name, address, category, region_id, lat, lon, importance) "
            "VALUES (:id, :name, :address, :category, :region_id, :lat, :lon, :importance)",
            ({**p, "region_id": region_id} for p in places),
        )
        conn.commit()
    finally:
        conn.close()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description="Genera routing.json/search.json (+manifest) da un .osm.pbf")
    ap.add_argument("--pbf", required=True, help="estratto OSM (.osm.pbf)")
    ap.add_argument("--region-id", required=True)
    ap.add_argument("--region-name", required=True)
    ap.add_argument("--out", default="./out")
    ap.add_argument("--pmtiles", help="(completa) file .pmtiles gia' pronto: ne calcola size+sha per il manifest")
    ap.add_argument("--pmtiles-url", help="(leggera) URL della .pmtiles gia' ospitata, per il manifest")
    ap.add_argument("--base-url", help="URL base dove ospiterai i file; genera regions.json")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    print(f"Lettura {args.pbf} (puo' richiedere qualche minuto)...")
    h = JarvisHandler()
    h.apply_file(args.pbf, locations=True)

    print(f"Strade percorribili: {len(h.ways)} · nodi: {len(h.coords)} · luoghi: {len(h.places)}")

    routing = build_routing(h)
    routing_path = os.path.join(args.out, "routing.json")
    with open(routing_path, "w", encoding="utf-8") as f:
        json.dump(routing, f, ensure_ascii=False)
    print(f"Scritto {routing_path}  (nodi {len(routing['nodes'])}, archi {len(routing['links'])})")

    search_path = os.path.join(args.out, "search.json")
    with open(search_path, "w", encoding="utf-8") as f:
        json.dump(h.places, f, ensure_ascii=False)
    print(f"Scritto {search_path}  ({len(h.places)} voci)")

    sqlite_path = os.path.join(args.out, "search.sqlite")
    write_sqlite(h.places, args.region_id, sqlite_path)
    print(f"Scritto {sqlite_path}  ({len(h.places)} voci, FTS5, schema v{SEARCH_SCHEMA_VERSION})")

    # Manifest opzionale.
    if args.base_url:
        base = args.base_url.rstrip("/")
        rid = args.region_id
        entry = {
            "id": rid,
            "name": args.region_name,
            "routingUrl": f"{base}/{rid}.routing.json",
            "searchUrl": f"{base}/{rid}.search.json",
            "searchSqliteUrl": f"{base}/{rid}.search.sqlite",
        }
        if args.pmtiles:
            entry["pmtilesUrl"] = f"{base}/{rid}.pmtiles"
            entry["sizeBytes"] = os.path.getsize(args.pmtiles)
            entry["sha256"] = sha256_file(args.pmtiles)
        elif args.pmtiles_url:
            entry["pmtilesUrl"] = args.pmtiles_url
        else:
            print("ATTENZIONE: nessun --pmtiles / --pmtiles-url: il manifest non avra' la mappa.")
        manifest = {"regions": [entry]}
        manifest_path = os.path.join(args.out, "regions.json")
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
        print(f"Scritto {manifest_path}")
        print("\nRinomina/carica i file cosi' (accanto alla mappa):")
        print(f"  {rid}.routing.json    <- {routing_path}")
        print(f"  {rid}.search.json     <- {search_path}   (facoltativo: fallback/ispezione)")
        print(f"  {rid}.search.sqlite   <- {sqlite_path}   (usato dall'app)")
        if args.pmtiles:
            print(f"  {rid}.pmtiles         <- {args.pmtiles}")
        print(f"  regions.json          <- {manifest_path}  (URL da incollare nell'app)")

    print("\nFatto.")


if __name__ == "__main__":
    main()

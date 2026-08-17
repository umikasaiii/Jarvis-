# JARVIS Offline Navigation — generare i dati di una regione

Il navigatore, per funzionare **offline**, ha bisogno di **4 file** per regione:

| File | Cosa | Come si ottiene |
|------|------|-----------------|
| `<id>.pmtiles` | la **mappa** vettoriale | tile-builder (planetiler/tilemaker) **oppure** una `.pmtiles` già pronta |
| `<id>.routing.json` | la **rete stradale** (per i percorsi) | **`osm_to_jarvis.py`** (questo tool) |
| `<id>.search.sqlite` | l'**indice di ricerca** (SQLite+FTS5, usato dall'app) | **`osm_to_jarvis.py`** (questo tool) |
| `<id>.search.json` | lo stesso elenco luoghi/POI in JSON (facoltativo: ispezione/fallback) | **`osm_to_jarvis.py`** (questo tool) |

L'app **scarica e usa** questi file; **non li crea** (troppo pesante per il
telefono) — apre `search.sqlite` in sola lettura, senza mai ricostruire un
indice sul dispositivo. Questi passi si fanno **una volta sul PC**, poi carichi
i file su un qualsiasi hosting statico e l'app li scarica (da URL o dal
catalogo `regions.json`).

`routing.json`, `search.sqlite` e `search.json` non esistono già pronti: vanno
generati con questo script. La mappa `.pmtiles` invece puoi generarla **o**
prenderla già pronta.

## Schema di `search.sqlite`

```sql
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
-- righe: ('schema_version', '1'), ('region_id', '<id-regione>')

CREATE VIRTUAL TABLE places USING fts5(
    ref_id UNINDEXED,     -- id stabile della voce
    name,                 -- ricercabile (full-text)
    address,              -- ricercabile (full-text) — "Via X 12, 00184 Roma"
    category UNINDEXED,   -- PlaceCategory (STREET, ADDRESS, PHARMACY, FUEL, ...)
    region_id UNINDEXED,
    lat UNINDEXED,
    lon UNINDEXED,
    importance UNINDEXED  -- 0..1, prominenza OSM-style
);
```

Un file per regione (`<id>.search.sqlite`), sola lettura sul telefono. `schema_version`
segue lo stesso principio di `RegionMetadata.SUPPORTED_REGION_SCHEMA_VERSION`
lato app: se in futuro la forma della tabella cambia in modo incompatibile, la
versione sale e l'app può rifiutare un file vecchio invece di leggerlo male.

## Cosa viene indicizzato

Oltre a strade con nome e POI con categoria riconosciuta (farmacie, benzinai,
ristoranti, bar, ospedali, parcheggi, hotel, negozi, stazioni, aeroporti...),
ora vengono indicizzati anche i **civici**: qualunque nodo/edificio con
`addr:housenumber` + `addr:street` diventa una voce `ADDRESS` cercabile
("Via Cristoforo Colombo 100"), anche senza nome o categoria — prima di questa
estensione i numeri civici non erano indicizzati (limite ora chiuso, vedi
"Note oneste" più sotto).

---

## 1. Prerequisiti

```bash
pip install osmium
```

Scarica un estratto OSM che copra il Lazio (Geofabrik pubblica per macro-aree;
il Lazio è dentro "Italia centro"):

```bash
wget https://download.geofabrik.de/europe/italy/centro-latest.osm.pbf
```

(Facoltativo — ritagliare solo il Lazio per file più piccoli, con `osmium-tool`:
`osmium extract -b 11.4,41.0,13.9,42.7 centro-latest.osm.pbf -o lazio.osm.pbf`)

---

## 2. La mappa `.pmtiles`

Scegli **una** delle due strade.

### A) Mappa già pronta (più semplice)
Procurati una `.pmtiles` del Lazio/Italia (es. build con lo strumento di
[Protomaps](https://protomaps.com/) o un estratto che hai). Ospitala e annota
il suo URL: lo passerai come `--pmtiles-url`.

### B) Generarla tu (planetiler, un comando)
[planetiler](https://github.com/onthegomap/planetiler) produce direttamente
`.pmtiles` da un `.osm.pbf`:

```bash
# serve Java 21+
java -Xmx4g -jar planetiler.jar --download=false \
     --osm-path=lazio.osm.pbf --output=lazio.pmtiles
```

(In alternativa [tilemaker](https://github.com/systemed/tilemaker) con output
pmtiles.) Lo stile dell'app (`jarvis-navigation.json`) usa i layer standard
OpenMapTiles: `transportation`, `transportation_name`, `building`, `water`,
`landuse` — quelli che planetiler/tilemaker generano di default.

---

## 3. Generare percorsi + ricerca (+ manifest)

### Modalità leggera (mappa ospitata altrove)
```bash
python osm_to_jarvis.py --pbf lazio.osm.pbf \
    --region-id lazio --region-name "Lazio" --out ./out \
    --base-url https://tuo-sito.example/maps \
    --pmtiles-url https://tuo-sito.example/maps/lazio.pmtiles
```

### Modalità completa (hai la `.pmtiles` in locale)
```bash
python osm_to_jarvis.py --pbf lazio.osm.pbf \
    --region-id lazio --region-name "Lazio" --out ./out \
    --pmtiles ./lazio.pmtiles \
    --base-url https://tuo-sito.example/maps
```

Output in `./out/`:
- `routing.json`, `search.sqlite` (indice FTS5, usato dall'app), `search.json`
  (stessi dati in JSON — facoltativo, utile per ispezionare a occhio o come
  fallback su una regione scaricata con un tool più vecchio)
- `regions.json` (il manifest, con gli URL sopra incluso `searchSqliteUrl`; in
  modalità completa include anche dimensione e SHA-256 della mappa).

---

## 4. Caricare e collegare all'app

Carica su `https://tuo-sito.example/maps/` (o qualunque hosting statico):

```
lazio.pmtiles          # la mappa
lazio.routing.json     # <- out/routing.json
lazio.search.sqlite    # <- out/search.sqlite   (l'app usa questo)
lazio.search.json      # <- out/search.json     (facoltativo)
regions.json           # <- out/regions.json  (il catalogo)
```

Nell'app: **Impostazioni → Navigazione offline → Mappe offline**:
- **Catalogo regioni** → incolla l'URL di `regions.json` → **Aggiorna** → **Scarica** «Lazio»
  (scarica mappa + percorsi + ricerca in un colpo), **oppure**
- **Scarica da URL** la `.pmtiles`, poi sulla riga della regione **+ Percorsi** e **+ Ricerca**
  con gli URL dei rispettivi file.

Fatto: dentro i confini del Lazio la mappa si renderizza, «portami a…» calcola il
percorso, la ricerca trova i luoghi — tutto **offline**.

---

## Note oneste / limiti

- Il grafo di routing di questo script è volutamente semplice (una classe strada
  → una velocità nominale, oneway/pedaggio/traghetto di base, split agli incroci).
  È deterministico e sufficiente per il calcolo percorsi dell'app; non modella
  svolte vietate, corsie o restrizioni orarie. Per routing "da produzione" si può
  puntare in futuro ai dati BRouter (`.rd5`) mantenendo lo stesso `RoadGraph`.
- `search.sqlite`/`search.json` coprono luoghi (città/paesi), POI comuni (benzina,
  parcheggi, ristoranti, farmacie, ospedali, hotel, stazioni, aeroporti, negozi),
  strade con nome e — da questa versione — **numeri civici** (qualunque nodo/edificio
  con `addr:housenumber`+`addr:street`, anche senza nome/categoria).
- L'indirizzo indicizzato resta una singola stringa arricchita ("Via X 12, 00184
  Roma"), non colonne separate per via/civico/CAP/comune: sufficiente per la
  ricerca full-text e per il bonus di "completezza indirizzo" nel ranking
  (`PlaceSearchRanker`), senza la complessità di uno schema a colonne dedicate.
- Questo tool NON è compilato/testato dalla CI dell'app: è uno strumento da PC.
  `write_sqlite()` (solo libreria standard, nessuna dipendenza in più) è comunque
  verificabile in isolamento senza `osmium`/dati OSM reali.

# JARVIS Offline Navigation — generare i dati di una regione

Il navigatore, per funzionare **offline**, ha bisogno di **3 file** per regione:

| File | Cosa | Come si ottiene |
|------|------|-----------------|
| `<id>.pmtiles` | la **mappa** vettoriale | tile-builder (planetiler/tilemaker) **oppure** una `.pmtiles` già pronta |
| `<id>.routing.json` | la **rete stradale** (per i percorsi) | **`osm_to_jarvis.py`** (questo tool) |
| `<id>.search.json` | l'**elenco luoghi/POI** (per la ricerca) | **`osm_to_jarvis.py`** (questo tool) |

L'app **scarica e usa** questi file; **non li crea** (troppo pesante per il
telefono). Questi passi si fanno **una volta sul PC**, poi carichi i file su un
qualsiasi hosting statico e l'app li scarica (da URL o dal catalogo `regions.json`).

`routing.json` e `search.json` non esistono già pronti: vanno generati con questo
script. La mappa `.pmtiles` invece puoi generarla **o** prenderla già pronta.

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
- `routing.json`, `search.json`
- `regions.json` (il manifest, con gli URL sopra; in modalità completa include
  anche dimensione e SHA-256 della mappa).

---

## 4. Caricare e collegare all'app

Carica su `https://tuo-sito.example/maps/` (o qualunque hosting statico):

```
lazio.pmtiles          # la mappa
lazio.routing.json     # <- out/routing.json
lazio.search.json      # <- out/search.json
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
- `search.json` copre luoghi (città/paesi), POI comuni (benzina, parcheggi,
  ristoranti, farmacie, ospedali, hotel, stazioni, aeroporti, negozi) e strade con
  nome. Numeri civici puntuali non sono inclusi in questa prima versione.
- Questo tool NON è compilato/testato dalla CI dell'app: è uno strumento da PC.

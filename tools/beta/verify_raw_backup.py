#!/usr/bin/env python3
"""BETA 1/8 - inspect a raw JARVIS data copy (tar made by raw_backup.*) WITHOUT touching the phone.

Works on a temporary extraction: the .tar and the phone are never modified.
  python verify_raw_backup.py jarvis-raw-YYYYMMDD-HHMM.tar [--json report.json]

Prints: SHA-256 of the tar, SQLite integrity + schema version, row counts of every user-data table,
number of records in the Markdown stores, documents, chat entries. Run it on the copy taken BEFORE the
update and again on a copy taken AFTER: the counts must match (new data aside).
Exit code 0 = every check passed, 1 = a problem was found.
"""
import hashlib, json, os, shutil, sqlite3, sys, tarfile, tempfile

PKG_NOTE = "debug builds use applicationId com.simone.jarvismobile.debug (isolated test build: ...debug.beta)"
TABLES = [  # (table, human label)
    ("archive_items", "Archivio: appunti / da vedere"), ("archive_lists", "Archivio: liste"),
    ("archive_list_items", "Archivio: voci di lista"), ("archive_links", "Archivio: collegamenti"),
    ("documents", "Documenti importati"), ("document_chunks", "Documenti: porzioni indicizzate"),
    ("automation_rules", "Automazioni (regole avanzate)"), ("automation_places", "Luoghi salvati"),
    ("automation_executions", "Esecuzioni automazioni"), ("parking_location", "Parcheggio"),
    ("assistant_tasks", "Coda risposte AI"), ("conversational_memory", "Memoria conversazionale"),
    ("agent_responsibilities", "Responsabilita' agente"), ("proactive_occurrences", "Occorrenze briefing"),
    ("trigger_evidence", "Evidenze trigger briefing"), ("proactive_schedule_plans", "Piani di schedulazione"),
    ("nav_favorites", "Navigazione: preferiti"), ("nav_history", "Navigazione: cronologia"),
]
MD_STORES = [("memoria.md", "Memoria (locale)"), ("agenda.md", "Agenda / attivita' (locale)"),
             ("automazioni.md", "Automazioni (file locale)")]

def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args: print(__doc__); return 1
    tar_path = args[0]; out_json = sys.argv[sys.argv.index("--json") + 1] if "--json" in sys.argv else None
    report, problems = {"tar": os.path.basename(tar_path), "tar_sha256": sha256(tar_path), "size_bytes": os.path.getsize(tar_path)}, []
    tmp = tempfile.mkdtemp(prefix="jarvis-verify-")
    try:
        with tarfile.open(tar_path) as t:
            names = t.getnames()
            for m in t.getmembers():  # refuse path tricks
                if m.name.startswith("/") or ".." in m.name.split("/"): problems.append(f"unsafe path in tar: {m.name}")
            t.extractall(tmp, filter="data") if sys.version_info >= (3, 12) else t.extractall(tmp)
        report["entries"] = len(names)
        db = os.path.join(tmp, "databases", "jarvis.db")
        if not os.path.isfile(db): problems.append("databases/jarvis.db missing")
        else:
            con = sqlite3.connect(db)  # on the temporary extraction, never on the phone
            integ = con.execute("PRAGMA integrity_check").fetchone()[0]
            report["db_integrity"] = integ; report["db_user_version"] = con.execute("PRAGMA user_version").fetchone()[0]
            if integ != "ok": problems.append(f"integrity_check: {integ}")
            have = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            counts = {}
            for tname, label in TABLES:
                if tname in have: counts[tname] = {"label": label, "rows": con.execute(f'SELECT COUNT(*) FROM "{tname}"').fetchone()[0]}
            report["tables"] = counts
            if "archive_items" in have:
                try: report["archive_items_by_type"] = dict(con.execute("SELECT type, COUNT(*) FROM archive_items GROUP BY type").fetchall())
                except sqlite3.Error: pass
            con.close()
        md = {}
        for fname, label in MD_STORES:
            p = os.path.join(tmp, "files", fname)
            if os.path.isfile(p):
                lines = open(p, encoding="utf-8", errors="replace").read().splitlines()
                md[fname] = {"label": label, "bytes": os.path.getsize(p), "records_approx": sum(1 for l in lines if l.lstrip().startswith("- "))}
        report["markdown_stores"] = md
        docs = os.path.join(tmp, "files", "documents")
        report["document_files"] = len(os.listdir(docs)) if os.path.isdir(docs) else 0
        bg = os.path.join(tmp, "files", "note_backgrounds")
        report["note_background_files"] = len(os.listdir(bg)) if os.path.isdir(bg) else 0
        chat = os.path.join(tmp, "files", "chat.json")
        if os.path.isfile(chat):
            try:
                d = json.load(open(chat, encoding="utf-8")); report["chat_entries"] = len(d) if isinstance(d, list) else len(d.get("messages", d))
            except Exception as e: problems.append(f"chat.json unreadable: {e}")
        ds = os.path.join(tmp, "files", "datastore", "jarvis_settings.preferences_pb")
        report["settings_datastore_present"] = os.path.isfile(ds)
        if not report["settings_datastore_present"]: problems.append("settings datastore missing")
        report["note"] = PKG_NOTE
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    report["problems"] = problems
    print(json.dumps(report, indent=2, ensure_ascii=False))
    if out_json: json.dump(report, open(out_json, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    print("\nRESULT:", "OK" if not problems else "PROBLEMS FOUND"); return 0 if not problems else 1

if __name__ == "__main__":
    sys.exit(main())

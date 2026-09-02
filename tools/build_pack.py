# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Turn a Wiktextract dump into a WordTap dictionary pack.

A pack answers one question offline: what does this word mean, explained in the
language the phone is set to. It therefore belongs to a pair of languages, the
language of the words and the language of the explanations, and that pair is what
a Wiktextract dump already is: kaikki.org publishes one file per Wiktionary
edition (which fixes the explanation language) and per word language.

    uv run tools/build_pack.py --gloss-lang en --word-lang de \
        --input kaikki.org-dictionary-German.jsonl --output en-de.db

The output is a SQLite file the app opens read only. Inflected forms are indexed
alongside the lemmas, because a reader taps the word in front of them, which is
"Speisen" far more often than "Speise".
"""

import argparse
import json
import sqlite3
import sys
import unicodedata
import zlib
from pathlib import Path

# A screenful of senses is already more than anyone reads in a popup, and the
# tail of a long Wiktionary entry is where the obscure ones live.
MAX_SENSES = 12
MAX_EXAMPLES = 1
MAX_GLOSS = 400
MAX_EXAMPLE = 200

# Examples are worth their space for the senses someone actually reads first.
SENSES_WITH_EXAMPLES = 4

# Wiktextract marks non-entries with these; they carry no meaning of their own.
SKIP_TAGS = {"no-gloss", "misspelling"}

SCHEMA = """
PRAGMA journal_mode = OFF;
PRAGMA synchronous = OFF;

CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE entries (
    id     INTEGER PRIMARY KEY,
    lemma  TEXT NOT NULL,
    key    TEXT NOT NULL,
    pos    TEXT,
    ipa    TEXT,
    -- The senses as deflated JSON. Gloss text is half the pack and compresses to
    -- about a third of itself, which is worth one inflate per lookup.
    senses BLOB NOT NULL
);

-- One row per way of writing a word that should find this entry: the lemma
-- itself and every inflected form Wiktionary lists for it. WITHOUT ROWID, so the
-- table is its own index rather than a table plus an index of the same size; a
-- language with rich inflection has several forms per entry and this is the
-- largest thing in the pack.
CREATE TABLE forms (
    key      TEXT NOT NULL,
    entry_id INTEGER NOT NULL,
    -- What this spelling is of the entry: "plural", "genitive singular",
    -- "past participle". Null when the spelling is the entry's own lemma. This is
    -- half the answer for an inflected word: which reading of the word is on the
    -- page, not only which entry to show.
    label    TEXT,
    PRIMARY KEY (key, entry_id)
) WITHOUT ROWID;
"""

INDEXES = """
CREATE INDEX entries_key ON entries (key);
"""


def normalise(word: str) -> str:
    """The shape a word is looked up by: case and accent folding stay out of it.

    Case is folded because a word at the start of a sentence is the same word.
    Nothing else is: stripping accents would merge distinct words in most
    languages this has to serve.
    """
    return unicodedata.normalize("NFC", word).casefold().strip()


# Tags that describe the wiring rather than the word, and read as noise in a label.
LABEL_NOISE = {"form-of", "inflection-of", "alt-of"}


def label_of(tags: list[str]) -> str | None:
    """The human-readable name of a word form, from Wiktionary's tags."""
    words = [t.replace("-", " ") for t in tags if t not in LABEL_NOISE]
    return " ".join(words[:4]) if words else None


def links_of(entry: dict) -> list[tuple[str, str | None]]:
    """(lemma this word is a form of, name of the form) for each form-of sense.

    A dump has a whole entry for "Speisen" whose only content is that it is the
    plural of "Speise". That is the inflection table in another shape: keeping it
    is what lets a tap on the word in the text land on the right reading of the
    right entry, so it is stored as a link to that entry rather than shown as an
    entry saying nothing.
    """
    links = []
    for sense in entry.get("senses", []):
        for target in (sense.get("form_of") or []) + (sense.get("alt_of") or []):
            word = (target.get("word") or "").strip()
            if word:
                links.append((normalise(word), label_of(sense.get("tags") or [])))
    return links


def senses_of(entry: dict) -> list[dict]:
    out = []
    for sense in entry.get("senses", []):
        tags = sense.get("tags") or []
        if SKIP_TAGS.intersection(tags):
            continue
        # A sense that only says "plural of Speise" is carried by the link instead.
        if sense.get("form_of") or sense.get("alt_of"):
            continue
        glosses = sense.get("glosses") or sense.get("raw_glosses") or []
        gloss = "; ".join(g.strip() for g in glosses if g).strip()
        if not gloss:
            continue
        examples = []
        if len(out) < SENSES_WITH_EXAMPLES:
            for example in sense.get("examples", [])[:MAX_EXAMPLES]:
                text = (example.get("text") or "").strip()
                if text:
                    examples.append(text[:MAX_EXAMPLE])
        item = {"g": gloss[:MAX_GLOSS]}
        if examples:
            item["x"] = examples
        # Tags say "plural", "colloquial", "obsolete": the difference between a
        # sense someone can use and one that would mislead them.
        keep = [t for t in tags if t not in ("form-of",)]
        if keep:
            item["t"] = keep[:6]
        out.append(item)
        if len(out) >= MAX_SENSES:
            break
    return out


def forms_of(entry: dict) -> dict[str, str | None]:
    """Every spelling in the entry's own inflection table, and what each one is."""
    forms: dict[str, str | None] = {}
    for form in entry.get("forms", []):
        text = (form.get("form") or "").strip()
        if not text or text in ("-", "—"):
            continue
        tags = form.get("tags") or []
        # Romanisations and transliterations are a different script, not another
        # way the word appears in the text someone is reading.
        if "romanization" in tags or "transliteration" in tags:
            continue
        if len(text) > 80:
            continue
        key = normalise(text)
        label = label_of(tags)
        # The fullest label wins: a spelling that is several forms at once is
        # named by the first one either way, and a labelled row beats a bare one.
        if key not in forms or (label and not forms[key]):
            forms[key] = label
    return forms


def build(input_path: Path, output_path: Path, gloss_lang: str, word_lang: str) -> None:
    if output_path.exists():
        output_path.unlink()
    db = sqlite3.connect(output_path)
    db.executescript(SCHEMA)

    db.execute("CREATE TEMP TABLE links (key TEXT, target TEXT, label TEXT)")

    entry_id = 0
    written = 0
    read = 0
    link_rows: list[tuple[str, str, str | None]] = []
    form_rows: list[tuple[str, int, str | None]] = []
    entry_rows: list[tuple[int, str, str, str | None, str | None, bytes]] = []

    with input_path.open(encoding="utf-8") as handle:
        for line in handle:
            read += 1
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                continue
            word = (entry.get("word") or "").strip()
            if not word:
                continue
            key = normalise(word)
            for target, label in links_of(entry):
                if target != key:
                    link_rows.append((key, target, label))

            senses = senses_of(entry)
            if not senses:
                continue

            entry_id += 1
            ipa = None
            for sound in entry.get("sounds", []):
                if sound.get("ipa"):
                    ipa = sound["ipa"]
                    break
            entry_rows.append(
                (
                    entry_id,
                    word,
                    key,
                    entry.get("pos"),
                    ipa,
                    zlib.compress(
                        json.dumps(senses, ensure_ascii=False, separators=(",", ":")).encode(),
                        9,
                    ),
                )
            )
            form_rows.append((key, entry_id, None))
            for form_key, label in forms_of(entry).items():
                if form_key != key:
                    form_rows.append((form_key, entry_id, label))
            written += 1

            if len(entry_rows) >= 20000:
                flush(db, entry_rows, form_rows, link_rows)
                entry_rows, form_rows, link_rows = [], [], []
                print(f"  {read:>9} lines, {written:>8} entries", file=sys.stderr)

    flush(db, entry_rows, form_rows, link_rows)

    # Resolve the form-of links now that every entry exists: a link points at a
    # lemma by name, and only here is it known which entry that name is.
    db.execute("CREATE INDEX links_target ON links (target)")
    db.execute(
        """
        INSERT OR IGNORE INTO forms (key, entry_id, label)
        SELECT l.key, e.id, l.label FROM links l JOIN entries e ON e.key = l.target
        """
    )
    resolved = db.total_changes
    db.executescript(INDEXES)
    db.executemany(
        "INSERT INTO meta (key, value) VALUES (?, ?)",
        [
            ("gloss_lang", gloss_lang),
            ("word_lang", word_lang),
            ("entries", str(written)),
            ("source", "wiktextract via kaikki.org"),
            ("format", "3"),
        ],
    )
    db.commit()
    forms = db.execute("SELECT count(*) FROM forms").fetchone()[0]
    db.execute("VACUUM")
    db.close()
    size = output_path.stat().st_size / 1e6
    print(
        f"{written} entries from {read} lines, {forms} form spellings "
        f"-> {output_path} ({size:.0f} MB)"
    )


def flush(db: sqlite3.Connection, entry_rows: list, form_rows: list, link_rows: list) -> None:
    db.executemany(
        "INSERT INTO entries (id, lemma, key, pos, ipa, senses) VALUES (?, ?, ?, ?, ?, ?)",
        entry_rows,
    )
    db.executemany(
        "INSERT OR IGNORE INTO forms (key, entry_id, label) VALUES (?, ?, ?)", form_rows
    )
    db.executemany("INSERT INTO links (key, target, label) VALUES (?, ?, ?)", link_rows)
    db.commit()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="Wiktextract JSONL")
    parser.add_argument("--output", required=True, type=Path, help="pack to write")
    parser.add_argument("--gloss-lang", required=True, help="language of the explanations")
    parser.add_argument("--word-lang", required=True, help="language of the words")
    args = parser.parse_args()
    build(args.input, args.output, args.gloss_lang, args.word_lang)


if __name__ == "__main__":
    main()

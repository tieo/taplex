# WordTap

Tap a single word anywhere on the screen and see what it means. No copying, no app
switching, and no full screen translation: one word, its dictionary entry.

## How it works

Word lookup is an accessibility service. Nothing is recorded and nothing runs between
lookups: the words come from the accessibility node tree, which the apps on screen fill in
themselves, and only a screen that reports no usable text costs a single screenshot.

1. Press the system accessibility button, or the WordTap quick settings tile. There is no
   bubble of WordTap's own, so nothing of WordTap's can end up inside a capture.
2. Every visible node's text is read, and each word's box comes from the rectangle the view
   reports for each of its characters
   (`AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY`). That is the exact
   spelling and the exact position, with no recognition in between, and the live screen
   stays visible under the word boxes.
3. Where a screen does not answer that request usefully, one screenshot is taken through
   `AccessibilityService.takeScreenshot` and ML Kit recognises the text in it. Chrome is
   such a screen: it reports one rectangle per paragraph rather than per character, which
   would put every word of the paragraph in the same box. Games, images, video and PDFs
   rendered as bitmaps report no text at all and go the same way. A captured frame is shown
   frozen, because the app underneath keeps scrolling while the overlay is up.
4. Tapping a word shows its entry: the lemma, its part of speech and pronunciation, its
   senses numbered as the dictionary numbers them, the marks that separate a usable sense
   from an obsolete or regional one, examples, and a link to the full Wiktionary article.

Tapping anywhere else, or pressing back, returns to the app.

The older path, a MediaProjection session held open behind a draggable bubble, is still
there for devices where the accessibility service cannot be turned on. It is what the
ongoing recording notification is, and it is the only mode that records anything.

## Dictionaries

A dictionary is a pack: one SQLite file holding the words of one language explained in
another, built from Wiktionary by `tools/build_pack.py`.

```
curl -O https://kaikki.org/dictionary/German/kaikki.org-dictionary-German.jsonl
uv run tools/build_pack.py --gloss-lang en --word-lang de \
    --input kaikki.org-dictionary-German.jsonl --output en-de.db
adb push en-de.db /sdcard/Android/data/de.tieo.wordtap/files/dictionaries/en-de.db
```

The name is `<language of the explanations>-<language of the words>.db`. WordTap identifies
the language on screen and opens the pack that explains it in the phone's language. The
German pack holds 94,000 entries in 113 MB.

Inflected words are what a reader actually taps, so every spelling in an entry's inflection
table is indexed against that entry, and so is every "plural of" entry Wiktionary has as a
page of its own. Both carry what the spelling is, so a tap on "Speisen" answers with
"Speise, plural" rather than with the bare lemma.

## Which reading of the word

A spelling usually leads to several entries. "Speisen" is the plural of the noun "Speise"
and a form of the verb "speisen", and in "die Zubereitung der Speisen" only one of them is
what the sentence says. The line the word was read from is kept with it, the words around it
are looked up in the same pack, and what those can be decides: a word that can be an article
pulls the reading towards a noun, one that can only be a pronoun pulls it towards a verb.
Nothing in that is written in terms of any one language; it is stated in parts of speech,
which every pack carries.

A word with no entry says so, and shows the on-device machine translation underneath, marked
as the guess it is.

## Building

The SDK path in `local.properties` points at the per-user Nix profile, never at a
`/nix/store` path, because the store path changes on every SDK rebuild.

```
gradle assembleDebug
gradle testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Permissions to grant once: draw over other apps, and word lookup under Settings →
Accessibility. The screen capture fallback additionally asks for notifications and for the
capture consent when it is armed.

## State

Working and tested on an API 36 emulator: node reading, screenshot fallback, word hit
testing, dictionary entries with senses and examples, and the ranking that picks the reading
that fits the sentence.

Rough edges worth fixing next:

- Packs are copied onto the device by hand; there is no way to install one from the app.
- Chrome and other browsers always go through the screenshot path, since they report a box
  per paragraph rather than per character.
- No history and no word list.
- The overlay grabs all touches while it is up, which is intended, but there is no visual
  hint that back or a miss tap dismisses it.

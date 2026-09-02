# WordTap

Tap a single word anywhere on the screen and see it translated. No copying, no app
switching, and no full screen translation: one word, one popup.

## How it works

Arming the app starts a foreground service that holds a MediaProjection session and shows a
draggable bubble over every app.

1. Tap the bubble. The service hides it for one frame, pulls the current frame from the
   VirtualDisplay, and puts it on screen as a frozen image. Freezing matters: the app
   underneath keeps scrolling, so live coordinates would drift away from the ones the
   recogniser returned.
2. ML Kit text recognition runs on that frame. Its result is a tree of
   TextBlock, Line and Element, where an Element is a word and carries its own bounding
   box. Those boxes are the tap targets.
3. Tapping a box translates that word with the ML Kit on-device translator and shows the
   result next to it. Tapping anywhere else, or pressing back, returns to the app.

Because the pipeline is OCR rather than the accessibility tree, it works on text that
cannot be selected: games, images, video frames, PDFs rendered as bitmaps.

Recognition and translation both run on the device. The first use of a language pair
downloads a model of roughly 30 MB, after which the app needs no network at all.

## Language detection

The source language is identified from the longest text blocks on screen rather than from
the whole frame or from the tapped word. A screenshot is mostly chrome (clock, battery,
URL bar, button labels) and identification on all of it returns "und" or nonsense: a
German Wikipedia article was identified as Danish at 0.13 confidence. Restricted to the
three longest blocks, the same screen gives German at 0.999.

A fixed source language can be set instead, and the target language defaults to the device
locale.

## Building

The SDK path in `local.properties` points at the per-user Nix profile, never at a
`/nix/store` path, because the store path changes on every SDK rebuild.

```
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Permissions to grant once: draw over other apps, notifications, and the screen capture
consent that appears when arming.

## State

Working and tested on an API 36 emulator: bubble, freeze, word hit testing, on-device
de to en translation, quick settings tile.

Rough edges worth fixing next:

- The quick settings tile is implemented but has not been exercised.
- The captured frame includes the status bar of the moment of capture, so the frozen image
  shows a slightly stale clock next to the live one.
- No history, no word list, no lookup of the same word in a dictionary rather than a
  translator.
- The overlay grabs all touches while it is up, which is intended, but there is no visual
  hint that back or a miss tap dismisses it.

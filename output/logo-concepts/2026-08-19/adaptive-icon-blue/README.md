# InkTalk Blue Adaptive Icon Candidate

This package prepares the approved blue/navy/cream speech-bubble mascot as a
separate Android adaptive-icon candidate. It does not replace the manifest's
current `@mipmap/ic_launcher` reference.

## Layer contract

- Container: `108 x 108 dp`
- Guaranteed safe zone: centered `66 x 66 dp`
- Foreground visible bounds in the 1080 px audit canvas: `(211, 318)-(869, 761)`
- Safe-zone bounds in the 1080 px audit canvas: `(210, 210)-(870, 870)`
- Background: exact solid `#0878F9`, provided by `@color/app_icon_background`
- Foreground: genuine RGBA PNG with transparent corners
- Monochrome: existing `@drawable/ic_launcher_monochrome`

The complete colored mascot, including the bubble tail, is inside the 66 dp
safe zone. Waveform, eyes, and mouth therefore remain inside every OEM mask.

## Project resources

- `app/src/main/res/drawable-nodpi/inktalk_blue_adaptive_foreground.png`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_blue_candidate.xml`

To activate after approval, change the active `ic_launcher.xml` foreground to
`@drawable/inktalk_blue_adaptive_foreground`, or point the manifest icon and
roundIcon to `@mipmap/ic_launcher_blue_candidate` for API 26+ after adding an
appropriate pre-26 fallback.

## Audit artifacts

- `foreground-layer-1080.png`: transparent foreground layer
- `preview-solid-blue-1080.png`: deterministic solid-background preview
- `preview-solid-blue-32.png`: native 32 x 32 readability check
- `safe-zone-preview-1080.png`: 72 dp viewport, 66 dp safe zone, and foreground bbox
- `build_adaptive_icon.py`: repeatable extraction, placement, and validation
- `rejected/`: two ImageGen outputs that drew checkerboards instead of alpha

ImageGen was used for the background-extraction attempts. Both results were
rejected after `sips` and `file` confirmed they had no alpha channel. The final
foreground uses a documented deterministic extraction from the second rejected
RGB checkerboard source; no failed constraint was hidden by silent processing.

## Validation

- Foreground PNG: `1080 x 1080`, RGBA, transparent at all four corners
- Foreground bbox: fully contained by the centered 66 dp safe zone
- Solid preview: every pixel outside foreground alpha is exactly `#0878F9`
- Native `32 x 32` preview: waveform, equal eyes, mouth, and bubble tail remain discernible
- `./gradlew :app:processDebugResources :app:assembleDebug`: passed
- `./gradlew :app:lintDebug`: passed
- Debug APK contains both `inktalk_blue_adaptive_foreground.png` and
  `ic_launcher_blue_candidate.xml`

These checks do not prove appearance under every physical launcher/OEM mask.
Real-device visual approval remains a separate acceptance step.

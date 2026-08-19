# InkTalk IP Logo Candidates

Generated on 2026-08-19 with the Codex built-in ImageGen tool.

- Provider/model: Codex built-in ImageGen; the tool schema did not expose a model identifier.
- Constraint delivery: main-prompt constraints.
- Native output: 1254 x 1254 PNG; no resampling performed.
- Input-image use: the supplied phone mascot was used only as conceptual guidance. It was not passed to ImageGen as a reference image.

## Shared prompt

```text
Use case: logo-brand.
Asset type: independent square InkTalk Android app-logo candidate, one candidate only.
Brand context: InkTalk is a friendly voice-first Android input method with live speech-to-text and a microphone/starred-microphone AI instruction switch.
Create a logo first and a character second. Use one complete direct full-bleed 1:1 square artwork with normal square corners.
Build one dominant continuous silhouette from 6-10 broad basic shapes. Use at most two broad internal regions. Use a face with exactly two simple eyes and one simple mouth, without highlights. Keep the mark readable at 32 x 32.
Keep the mascot front-facing and upright, emerging from a lower corner and filling 75-85% of the square.
Use an ultra-clean Flat-first vector-friendly treatment with thick, blunt, rounded geometry and only 8-12% extremely subtle internal tonal modeling.
Use exactly three semantic colors: exactly two IP colors plus one fully opaque solid backdrop color. Reuse the two IP colors for all facial and internal marks.
Constraints: no text, letters, watermark, border, frame, card, app-icon mask, extra mascot, arms, hands, legs, phone hardware, menu icon, scenery, texture, background gradient, vignette, halo, lighting variation, cast shadow, thin lines, sharp tips, glossy hotspot, cavity, bevel, extrusion, clay, plastic, plush, inflatable, toy-like, photorealistic material, or strong 3D rendering.
```

## Candidate prompts and evaluation

| Label | Direction and variant prompt | Color mapping | Background | Evaluation |
| --- | --- | --- | --- | --- |
| A1 | Upright rounded voice-input panel; navy outer silhouette, cream inner panel, face above one large three-bar waveform; emerge lower-right. | IP: `#17233C`, `#FFE4AE`; backdrop: `#1976F3` | Transparent despite the requested blue backdrop | Non-recommended: transparent variation is preserved, but the mark is tilted, noticeably modeled, and not the requested lower-corner upright composition. |
| A2 | Compact rounded voice-input panel; deep-brown outer silhouette, burnt-orange face region and large waveform; emerge lower-left. | IP: `#302A25`, `#9A5B16`; backdrop: `#FAF0E2` | Opaque, visible light variation | Non-recommended: tilted and oversized; backdrop is not uniformly flat. |
| B1 | Blunt ink-drop mascot; burnt-amber outer silhouette, deep-brown belly and large three-bar waveform; emerge lower-right. | IP: `#9A5B16`, `#302A25`; backdrop: `#FAF0E2` | Opaque, visible light variation | Non-recommended under the strict rubric: strong concept, but centered presentation and background vignette remain. |
| B2 | Blunt ink-drop mascot; navy outer silhouette, cream belly, navy face and three-bar waveform; emerge lower-left. | IP: `#17243B`, `#F4D7A1`; backdrop: `#1677F4` | Opaque, visible blue halo/vignette | Non-recommended under the strict rubric, but strongest original concept for brand recognition and 32 px readability. |
| C1 | Horizontal voice-purpose capsule; amber pill silhouette, cream slider/face region, large cream waveform; emerge lower-right. | IP: `#AE6B23`, `#FAF0E2`; backdrop: `#302A25` | Opaque, visible light variation | Non-recommended under the strict rubric: product connection is clear, but the background is not flat and the wide mark may be weaker in adaptive-icon masks. |
| C2 | Compact vertical capsule; deep-brown silhouette, orange face region and orange waveform; emerge lower-left. | IP: `#302A25`, `#D8892B`; backdrop: `#F7F0E8` | Opaque, visible light variation | Non-recommended under the strict rubric: readable and simple, but it drifts toward a generic panel and uses a vignetted backdrop. |
| B2-R | Targeted B2 retry: mechanically uniform blue backdrop, zero tilt/perspective, flat blunt ink drop with the same face and waveform. | IP: `#17243B`, `#F4D7A1`; backdrop: `#1677F4` | Opaque, residual blue halo/vignette | Non-recommended under the strict background rule; best candidate to refine because silhouette, brand metaphor, face, and waveform remain clear. |

## Files

- `A1-input-panel-blue.png`
- `A2-input-panel-warm.png`
- `B1-inkdrop-warm.png`
- `B2-inkdrop-blue.png`
- `B2R-inkdrop-blue-flat-retry.png`
- `C1-voice-pill-horizontal.png`
- `C2-voice-pill-compact.png`

No candidate has replaced the current launcher artwork.

## C1 enlargement refinements

Both refinements use the original `C1-voice-pill-horizontal.png` as an edit target through the Codex built-in ImageGen tool. Constraint delivery remained in the main prompt.

| Label | Requested edit | Color mapping | Background | Evaluation |
| --- | --- | --- | --- | --- |
| C1-L1 | Enlarge the complete mascot by about 15%; move the face group toward the waveform; make the near eye larger, far eye smaller, and shift the asymmetric smile left. | IP: warm amber and warm cream; backdrop: deep ink brown | Opaque, inherited light variation | The gaze reads toward the waveform and the scale is deliberately bold, but the cream face region is cropped on the right. Preserve as the aggressive crop option. |
| C1-L2 | Preserve the enlarged left-directed face, then correct framing so the full face region and full waveform remain visible. | IP: warm amber and warm cream; backdrop: deep ink brown | Opaque, inherited light variation | Preferred enlargement: substantially larger than the original, complete identifying regions, clear left-directed expression, and good small-size readability. Still non-recommended under the strict flat-background rule because the inherited background retains a vignette. |

Files:

- `C1L-enlarged-gaze-left-v1.png`
- `C1L2-enlarged-gaze-left-complete.png`

## C1 bubble and equal-eye corrections

These edits returned to the original C1 design after the user rejected the oversized unequal-eye version.

| Label | Requested correction | Background | Evaluation |
| --- | --- | --- | --- |
| C1-M1 | Restore two precisely equal eyes, make the cream region an independent speech bubble with a short blunt left-pointing nub, and request a modest enlargement. | Opaque, inherited light variation | Bubble and equal eyes are correct, but ImageGen made the mascot smaller than requested. Preserved as an intermediate. |
| C1-M2 | Preserve the bubble and equal eyes; enlarge only the whole mascot. | Opaque, inherited light variation | Complete bubble and waveform, but still conservative in scale. Best option when complete containment matters most. |
| C1-M3 | Preserve all internal design; magnify to a medium-large proportion. | Opaque, inherited light variation | Clearly smaller than the rejected oversized versions and slightly larger than original C1. Equal eyes and bubble nub remain, but the cream bubble is lightly cropped on the right. |

Files:

- `C1M-bubble-equal-eyes-small-intermediate.png`
- `C1M2-bubble-equal-eyes-medium-attempt.png`
- `C1M3-bubble-equal-eyes-medium-large.png`

## C1 outer-bubble semantic correction

The user clarified that the warm-amber outer frame is the speech bubble; the cream inner region is the contained character, not a bubble.

| Label | Correction | Background | Evaluation |
| --- | --- | --- | --- |
| C1-OB | Return to original C1; keep the complete warm-amber outer silhouette as the speech bubble with one short blunt lower-right tail; keep the cream inner character tail-free; use equal eyes and shift the whole face group left toward the waveform; apply only a subtle scale increase. | Opaque, inherited light variation | Recommended direction for continued refinement. Outer-bubble semantics, equal eyes, complete waveform, contained character, and balanced scale are restored. The remaining strict-rubric deviation is the inherited background vignette. |

File:

- `C1OB-outer-brown-bubble-corrected.png`

## C1-OB blue palette exploration

The supplied phone-mascot image was used only as a color-palette reference. Its phone geometry, arms, hardware, glossy eyes, and 3D material were not transferred.

| Label | Color mapping | Background | Evaluation |
| --- | --- | --- | --- |
| C1-OB Blue V1 | Backdrop: electric cobalt blue; outer bubble and face marks: midnight navy; waveform and inner character: warm cream. | Opaque with visible blue illumination/vignette inherited from the palette reference | Strong technology and voice-product association; exact three semantic color families and the complete outer-bubble structure are preserved. Non-recommended under the strict flat-background rule. |
| C1-OB Blue Flat Retry | Same three-color mapping; targeted background-uniformity correction only. | Opaque; illumination is reduced but a mild center glow remains | Preferred blue comparison. Equal eyes, outer navy bubble, blunt tail, waveform, and inner character are intact. Remaining deviation: background is not mechanically uniform. |

Files:

- `C1OB-blue-palette-v1.png`
- `C1OB-blue-palette-flat-retry.png`

## Blue adaptive-icon safe-zone package

The two built-in ImageGen background-extraction attempts returned RGB PNGs with
painted checkerboards and no alpha channel. They were rejected and preserved.
The final foreground was then derived deterministically from the checkerboard's
neutral saturation boundary, with the procedure and edge cleanup recorded in
`adaptive-icon-blue/build_adaptive_icon.py`.

- Android layer canvas: `108 x 108 dp`
- Guaranteed safe zone: centered `66 x 66 dp`
- Audit canvas: `1080 x 1080 px`
- Foreground bbox: `(211, 318)-(869, 761)`
- Safe-zone bbox: `(210, 210)-(870, 870)`
- Background: exact `#0878F9` outside every foreground alpha pixel
- Foreground: real RGBA, transparent at all four corners
- Project candidate: `@mipmap/ic_launcher_blue_candidate`
- Active manifest icon: unchanged (`@mipmap/ic_launcher`)
- Native 32 x 32 preview remains readable
- Android resource processing, Debug assembly, and Lint passed
- Debug APK contains the foreground PNG and candidate adaptive XML
- Physical launcher/OEM-mask visual acceptance remains pending

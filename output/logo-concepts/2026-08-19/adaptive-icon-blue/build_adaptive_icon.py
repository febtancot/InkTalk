#!/usr/bin/env python3
"""Build auditable Android adaptive-icon assets from the rejected checkerboard PNG.

The image generator returned RGB checkerboards instead of alpha twice. This script
uses the checkerboard's zero saturation and the mascot's two chromatic color
families to derive a real alpha mask, then places the complete mark inside the
official 66/108 adaptive-icon safe zone.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter


CANVAS_PX = 1080
SAFE_PX = 660
SAFE_MIN = (CANVAS_PX - SAFE_PX) // 2
SAFE_MAX = SAFE_MIN + SAFE_PX
VIEWPORT_72_MIN = 180
VIEWPORT_72_MAX = 900
BACKGROUND = (8, 120, 249, 255)  # #0878F9, current InkTalk app-icon blue.
NAVY_EDGE = np.array([23, 36, 59], dtype=np.uint8)  # #17243B
CREAM_EDGE = np.array([246, 217, 163], dtype=np.uint8)  # #F6D9A3


def smoothstep(value: np.ndarray) -> np.ndarray:
    value = np.clip(value, 0.0, 1.0)
    return value * value * (3.0 - 2.0 * value)


def extract_foreground(source: Image.Image) -> Image.Image:
    rgb = np.asarray(source.convert("RGB"), dtype=np.uint8)
    rgb_f = rgb.astype(np.float32) / 255.0
    maximum = rgb_f.max(axis=2)
    minimum = rgb_f.min(axis=2)
    saturation = np.divide(
        maximum - minimum,
        maximum,
        out=np.zeros_like(maximum),
        where=maximum > 0,
    )

    # The rejected background is neutral gray/white (saturation ~0), while both
    # allowed mascot families are chromatic. Preserve a soft antialiased edge.
    alpha_f = smoothstep((saturation - 0.018) / 0.16)
    alpha = np.rint(alpha_f * 255.0).astype(np.uint8)
    alpha_image = Image.fromarray(alpha, mode="L")
    alpha_image = alpha_image.filter(ImageFilter.MaxFilter(3))
    alpha_image = alpha_image.filter(ImageFilter.GaussianBlur(0.65))
    alpha = np.asarray(alpha_image, dtype=np.uint8)

    # Remove gray checkerboard contamination from partially transparent pixels.
    # Blue-dominant pixels belong to the navy family; the rest are cream.
    clean_rgb = rgb.copy()
    partial = (alpha > 0) & (alpha < 248)
    navy_like = rgb[:, :, 2] > rgb[:, :, 0]
    clean_rgb[partial & navy_like] = NAVY_EDGE
    clean_rgb[partial & ~navy_like] = CREAM_EDGE

    # Every external silhouette edge belongs to the navy speech bubble. The
    # checkerboard source can leave nearly opaque gray pixels after antialiasing,
    # so explicitly recolor a narrow alpha-boundary band to navy. Internal
    # cream/navy boundaries remain untouched because they are fully opaque.
    visible = Image.fromarray(np.where(alpha > 8, 255, 0).astype(np.uint8), mode="L")
    eroded = visible.filter(ImageFilter.MinFilter(7))
    edge_band = (np.asarray(visible) > 0) & (np.asarray(eroded) == 0)
    clean_rgb[edge_band] = NAVY_EDGE
    clean_rgb[alpha == 0] = 0

    rgba = np.dstack([clean_rgb, alpha])
    foreground = Image.fromarray(rgba, mode="RGBA")
    bbox = foreground.getchannel("A").getbbox()
    if bbox is None:
        raise RuntimeError("Foreground extraction produced an empty alpha mask")

    left, top, right, bottom = bbox
    pad = 4
    left = max(0, left - pad)
    top = max(0, top - pad)
    right = min(foreground.width, right + pad)
    bottom = min(foreground.height, bottom + pad)
    return foreground.crop((left, top, right, bottom))


def build_layer(tight: Image.Image) -> Image.Image:
    scale = min(SAFE_PX / tight.width, SAFE_PX / tight.height)
    target = (
        max(1, round(tight.width * scale)),
        max(1, round(tight.height * scale)),
    )
    resized = tight.resize(target, Image.Resampling.LANCZOS)
    layer = Image.new("RGBA", (CANVAS_PX, CANVAS_PX), (0, 0, 0, 0))
    x = (CANVAS_PX - resized.width) // 2
    y = (CANVAS_PX - resized.height) // 2
    layer.alpha_composite(resized, (x, y))
    return layer


def verify_layer(layer: Image.Image) -> tuple[int, int, int, int]:
    if layer.mode != "RGBA":
        raise RuntimeError(f"Expected RGBA foreground, got {layer.mode}")
    alpha = layer.getchannel("A")
    bbox = alpha.getbbox()
    if bbox is None:
        raise RuntimeError("Final foreground layer is empty")
    left, top, right, bottom = bbox
    if not (
        left >= SAFE_MIN
        and top >= SAFE_MIN
        and right <= SAFE_MAX
        and bottom <= SAFE_MAX
    ):
        raise RuntimeError(
            f"Foreground bbox {bbox} exceeds safe zone "
            f"({SAFE_MIN}, {SAFE_MIN}, {SAFE_MAX}, {SAFE_MAX})"
        )
    corners = [(0, 0), (CANVAS_PX - 1, 0), (0, CANVAS_PX - 1), (CANVAS_PX - 1, CANVAS_PX - 1)]
    if any(alpha.getpixel(point) != 0 for point in corners):
        raise RuntimeError("Foreground alpha is not transparent at every corner")
    return bbox


def composite_preview(layer: Image.Image) -> Image.Image:
    background = Image.new("RGBA", layer.size, BACKGROUND)
    return Image.alpha_composite(background, layer).convert("RGB")


def safe_zone_preview(preview: Image.Image, bbox: tuple[int, int, int, int]) -> Image.Image:
    diagnostic = preview.copy()
    draw = ImageDraw.Draw(diagnostic)
    draw.rectangle(
        (VIEWPORT_72_MIN, VIEWPORT_72_MIN, VIEWPORT_72_MAX - 1, VIEWPORT_72_MAX - 1),
        outline=(255, 190, 64),
        width=5,
    )
    draw.rectangle(
        (SAFE_MIN, SAFE_MIN, SAFE_MAX - 1, SAFE_MAX - 1),
        outline=(76, 230, 126),
        width=7,
    )
    draw.rectangle(
        (bbox[0], bbox[1], bbox[2] - 1, bbox[3] - 1),
        outline=(255, 255, 255),
        width=3,
    )
    draw.text((24, 22), "orange: 72dp viewport  green: 66dp safe zone  white: foreground bbox", fill=(255, 255, 255))
    return diagnostic


def verify_background(preview: Image.Image, layer: Image.Image) -> None:
    preview_rgb = np.asarray(preview.convert("RGB"), dtype=np.uint8)
    alpha = np.asarray(layer.getchannel("A"), dtype=np.uint8)
    outside = alpha == 0
    expected = np.array(BACKGROUND[:3], dtype=np.uint8)
    if not np.all(preview_rgb[outside] == expected):
        raise RuntimeError("Preview background is not uniformly #0878F9 outside the foreground")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("resource_png", type=Path)
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    args.resource_png.parent.mkdir(parents=True, exist_ok=True)

    source = Image.open(args.source)
    tight = extract_foreground(source)
    layer = build_layer(tight)
    bbox = verify_layer(layer)
    preview = composite_preview(layer)
    verify_background(preview, layer)
    diagnostic = safe_zone_preview(preview, bbox)

    foreground_path = args.output_dir / "foreground-layer-1080.png"
    preview_path = args.output_dir / "preview-solid-blue-1080.png"
    preview_32_path = args.output_dir / "preview-solid-blue-32.png"
    diagnostic_path = args.output_dir / "safe-zone-preview-1080.png"
    layer.save(foreground_path)
    preview.save(preview_path)
    preview.resize((32, 32), Image.Resampling.LANCZOS).save(preview_32_path)
    diagnostic.save(diagnostic_path)
    layer.save(args.resource_png)

    alpha = np.asarray(layer.getchannel("A"), dtype=np.uint8)
    print(f"foreground={foreground_path}")
    print(f"preview={preview_path}")
    print(f"preview_32={preview_32_path}")
    print(f"safe_zone_preview={diagnostic_path}")
    print(f"resource={args.resource_png}")
    print(f"foreground_bbox={bbox}")
    print(f"safe_zone=({SAFE_MIN}, {SAFE_MIN}, {SAFE_MAX}, {SAFE_MAX})")
    print(f"opaque_pixels={int(np.count_nonzero(alpha == 255))}")
    print(f"alpha_pixels={int(np.count_nonzero(alpha > 0))}")
    print("background=#0878F9 uniform outside alpha")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""生成 Car++ 启动图标（Car+ 风格：蓝紫渐变 + 手机/车机屏连接图形）。

产出 4 类文件 × 5 个密度：
  ic_launcher.png           圆角方形（旧版启动器用）
  ic_launcher_round.png     正圆（旧版启动器用）
  ic_launcher_background.png 自适应图标背景层（渐变）
  ic_launcher_foreground.png 自适应图标前景层（透明底 + 图形，居中安全区内）

用法: python3 scripts/gen_icon.py
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw

RESAMPLE = Image.Resampling.LANCZOS  # Pyright 对 RESAMPLE 的类型推断不可靠

RES = Path(__file__).resolve().parents[1] / "carpp-sdk/src/main/res"

# 密度 → 标准启动图标边长(px)
ICON_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# 密度 → 自适应图标层边长(px)（108dp × 密度因子）
LAYER_SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

SS = 4  # 超采样倍数，画完再缩，边缘才干净

# Car+ 气质的蓝紫渐变
GRAD_FROM = (74, 108, 247)   # #4A6CF7 蓝
GRAD_TO = (139, 92, 246)     # #8B5CF6 紫
GLYPH = (255, 255, 255, 255)


def gradient(size, angle_deg=135):
    """对角线性渐变。"""
    w = h = size
    img = Image.new("RGBA", (w, h))
    px = img.load()
    assert px is not None
    a = math.radians(angle_deg)
    dx, dy = math.cos(a), math.sin(a)
    # 投影范围归一化
    proj = [dx * x + dy * y for x in (0, w) for y in (0, h)]
    lo, hi = min(proj), max(proj)
    for y in range(h):
        for x in range(w):
            t = (dx * x + dy * y - lo) / (hi - lo)
            t = t * t * (3 - 2 * t)  # smoothstep，渐变更柔
            r = int(GRAD_FROM[0] + (GRAD_TO[0] - GRAD_FROM[0]) * t)
            g = int(GRAD_FROM[1] + (GRAD_TO[1] - GRAD_FROM[1]) * t)
            b = int(GRAD_FROM[2] + (GRAD_TO[2] - GRAD_FROM[2]) * t)
            px[x, y] = (r, g, b, 255)
    return img


def rounded_rect(d, box, radius, fill=None, outline=None, width=1):
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def glyph(size):
    """手机 ↔ 车机屏 的连接图形，画在透明底上（自适应图标前景层）。

    只用中心 66/108 区域（安全区），保证在任何遮罩形状下都不被裁。
    """
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 安全区：自适应图标 108dp 画布里，只保证中心 72dp 圆可见 → 取 66dp 留点余量
    safe = s * 66 / 108
    cx, cy = s / 2, s / 2

    # 车机屏（右侧，宽屏）
    screen_w = safe * 0.62
    screen_h = screen_w * 0.60
    sx0 = cx - safe * 0.04
    sy0 = cy - screen_h / 2
    sx1 = sx0 + screen_w
    sy1 = sy0 + screen_h
    sw = max(2, int(s * 0.020))
    rounded_rect(d, (sx0, sy0, sx1, sy1), int(screen_h * 0.18),
                 outline=GLYPH, width=sw)
    # 屏内：导航箭头（说明投的是手机画面）
    ax = sx0 + screen_w * 0.50
    ay = sy0 + screen_h * 0.50
    at = screen_h * 0.16
    d.polygon([
        (ax, ay - at * 1.5),
        (ax + at, ay + at * 0.9),
        (ax, ay + at * 0.35),
        (ax - at, ay + at * 0.9),
    ], fill=GLYPH)
    # 屏内底部：底座
    base_y = sy1 + screen_h * 0.10
    d.rounded_rectangle(
        (sx0 + screen_w * 0.30, base_y - sw * 0.6,
         sx1 - screen_w * 0.30, base_y + sw * 1.4),
        radius=int(sw), fill=GLYPH)

    # 手机（左侧，竖屏）
    ph_w = safe * 0.30
    ph_h = ph_w * 1.85
    px0 = cx - safe * 0.46
    py0 = cy - ph_h / 2
    px1 = px0 + ph_w
    py1 = py0 + ph_h
    rounded_rect(d, (px0, py0, px1, py1), int(ph_w * 0.22),
                 outline=GLYPH, width=sw)
    # 手机屏内小圆（Home 键 / 打孔摄像头意象）
    r = ph_w * 0.10
    d.ellipse((cx - safe * 0.46 + ph_w / 2 - r, py0 + ph_h * 0.14 - r,
               cx - safe * 0.46 + ph_w / 2 + r, py0 + ph_h * 0.14 + r),
              fill=GLYPH)

    # 连接波纹（手机与车机屏之间，三道弧）
    arc_cx = (px1 + sx0) / 2
    arc_cy = cy + screen_h * 0.10
    for i, rr in enumerate([safe * 0.10, safe * 0.17, safe * 0.24]):
        wdt = max(2, int(s * 0.016 - i * s * 0.003))
        if wdt < 1:
            continue
        d.arc((arc_cx - rr, arc_cy - rr, arc_cx + rr, arc_cy + rr),
              start=-52, end=52, fill=GLYPH, width=wdt)

    return img.resize((size, size), RESAMPLE)


def mask_icon(bg, radius_ratio, circle=False):
    """把渐变底裁成圆角方形/圆形（旧版启动器图标）。"""
    s = bg.size[0]
    mask = Image.new("L", (s * SS, s * SS), 0)
    md = ImageDraw.Draw(mask)
    if circle:
        md.ellipse((0, 0, s * SS - 1, s * SS - 1), fill=255)
    else:
        md.rounded_rectangle((0, 0, s * SS - 1, s * SS - 1),
                             radius=int(s * SS * radius_ratio), fill=255)
    mask = mask.resize((s, s), RESAMPLE)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    return out


def main():
    for density, size in ICON_SIZES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)

        # 完整图标：渐变底 + 图形，直接合成（旧版启动器只吃一张图）
        g = glyph(size)
        base = gradient(size)
        legacy = Image.alpha_composite(base, g)
        legacy = mask_icon(legacy, radius_ratio=0.20)
        legacy.resize((size, size), RESAMPLE).save(folder / "ic_launcher.png")

        round_img = Image.alpha_composite(gradient(size), g)
        mask_icon(round_img, 0, circle=True).save(folder / "ic_launcher_round.png")

    for density, size in LAYER_SIZES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        gradient(size).save(folder / "ic_launcher_background.png")
        glyph(size).save(folder / "ic_launcher_foreground.png")

    print(f"icons written to {RES}")


if __name__ == "__main__":
    main()

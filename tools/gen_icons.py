#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""「全民影视」品牌标识生成器

设计说明
--------
图形：圆角屏幕框（品牌渐变 135°）+ 框内播放三角，构成 "屏幕 / 播放" 的单一意象。
圆角半径与 App 内卡片（20rpx）沿用同一套圆角语言，图标 / 页头 / 启动页共用同一枚 glyph。

输出
----
static/icons/*.png   Android 各密度图标、自适应图标前景与背景
static/brand/*.png   透明底标识、横竖组合、单色版、品牌规范板

用法：/usr/bin/python3 tools/gen_icons.py
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICON_DIR = os.path.join(ROOT, 'static', 'icons')
BRAND_DIR = os.path.join(ROOT, 'static', 'brand')

# ---------------- 设计令牌 ----------------
BG_TOP = (32, 39, 56)      # 图标底·上 #202738
BG_BOT = (11, 13, 19)      # 图标底·下 #0b0d13
BR_START = (61, 155, 255)  # 品牌渐变起 #3d9bff（= App 主色）
BR_END = (124, 92, 255)    # 品牌渐变止 #7c5cff
WHITE = (252, 253, 255)
INK = (16, 19, 26)         # 浅底场景字形色

# 图形比例（以 glyph 画布边长 1.0 为基准）
FRAME_HALF = 0.375    # 屏幕框外沿半宽
FRAME_STROKE = 0.070  # 框线粗细
FRAME_RADIUS = 0.112  # 框圆角
TRI_RADIUS = 0.212    # 播放三角外接圆半径
TRI_ROUND = 0.014     # 三角圆角
TRI_DX = 0.022        # 三角视觉居中补偿（右移）

SS = 4  # 遮罩超采样倍数


def lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def diag_gradient(size, c1, c2, x0=0.0, y0=0.0, x1=1.0, y1=1.0):
    """两点定义方向的双色线性渐变"""
    img = Image.new('RGBA', (size, size))
    px = img.load()
    dx, dy = x1 - x0, y1 - y0
    n = dx * dx + dy * dy
    for y in range(size):
        for x in range(size):
            u = min(1.0, max(0.0, ((x / size - x0) * dx + (y / size - y0) * dy) / n))
            px[x, y] = tuple(int(v) for v in lerp(c1, c2, u)) + (255,)
    return img


def squircle_mask(size, radius_ratio=0.232, inset=0.0):
    m = Image.new('L', (size, size), 0)
    d = int(size * inset)
    ImageDraw.Draw(m).rounded_rectangle([d, d, size - 1 - d, size - 1 - d],
                                        radius=max(1, int(size * radius_ratio) - d), fill=255)
    return m


def vertical_gradient(size, top, bottom):
    img = Image.new('RGBA', (size, size))
    d = ImageDraw.Draw(img)
    for y in range(size):
        d.line([(0, y), (size, y)], fill=tuple(int(v) for v in lerp(top, bottom, y / max(1, size - 1))) + (255,))
    return img


def icon_base(size):
    """圆角方形底：垂直渐变 + 顶部柔光 + 1px 内描边"""
    base = Image.new('RGBA', (size, size))
    base.paste(vertical_gradient(size, BG_TOP, BG_BOT), (0, 0), squircle_mask(size))
    glow = Image.new('L', (size, size), 0)
    ImageDraw.Draw(glow).ellipse([-size * 0.35, -size * 0.78, size * 1.35, size * 0.55], fill=40)
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.10))
    base.paste(Image.new('RGBA', (size, size), (120, 160, 255, 255)), (0, 0),
               Image.composite(glow, Image.new('L', (size, size), 0), squircle_mask(size)))
    ImageDraw.Draw(base).rounded_rectangle([0.5, 0.5, size - 1.5, size - 1.5], radius=int(size * 0.232),
                                           outline=(255, 255, 255, 26), width=max(1, int(round(size / 170))))
    return base


def frame_mask(size):
    W = size * SS
    m = Image.new('L', (W, W), 0)
    c = W / 2
    ImageDraw.Draw(m).rounded_rectangle([c - FRAME_HALF * W, c - FRAME_HALF * W,
                                         c + FRAME_HALF * W, c + FRAME_HALF * W],
                                        radius=int(FRAME_RADIUS * W), outline=255,
                                        width=int(FRAME_STROKE * W))
    return m.resize((size, size), Image.LANCZOS)


def tri_mask(size):
    W = size * SS
    m = Image.new('L', (W, W), 0)
    cx = W / 2 + TRI_DX * W
    cy = W / 2
    pts = [(cx + TRI_RADIUS * W * math.cos(math.radians(a)), cy + TRI_RADIUS * W * math.sin(math.radians(a)))
           for a in (0, 120, 240)]
    ImageDraw.Draw(m).polygon(pts, fill=255)
    m = m.filter(ImageFilter.GaussianBlur(TRI_ROUND * W))
    m = m.point(lambda v: 255 if v > 141 else 0)
    return m.resize((size, size), Image.LANCZOS)


def mark(size, mono=None):
    """品牌 glyph（透明底）：屏幕框 + 播放三角
    mono=None  -> 渐变框 + 白三角（深色场景）
    mono=颜色  -> 单色（如 INK，用于浅色场景）"""
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    fm, tm = frame_mask(size), tri_mask(size)
    if mono is None:
        canvas.paste(diag_gradient(size, BR_START, BR_END, 0.06, 0.0, 0.94, 1.0), (0, 0), fm)
        canvas.paste(Image.new('RGBA', (size, size), WHITE + (255,)), (0, 0), tm)
    else:
        solid = Image.new('RGBA', (size, size), tuple(mono) + (255,))
        canvas.paste(solid, (0, 0), fm)
        canvas.paste(solid, (0, 0), tm)
    return canvas


def icon(size, render=1024):
    """App 图标：底 + 品牌光晕 + glyph"""
    s = max(render, size)
    canvas = icon_base(s)
    glow = Image.new('L', (s, s), 0)
    ImageDraw.Draw(glow).ellipse([s * 0.17, s * 0.17, s * 0.83, s * 0.83], fill=48)
    glow = glow.filter(ImageFilter.GaussianBlur(s * 0.13))
    canvas.paste(Image.new('RGBA', (s, s), (74, 130, 255, 255)), (0, 0),
                 Image.composite(glow, Image.new('L', (s, s), 0), squircle_mask(s)))
    canvas.alpha_composite(mark(s))
    return canvas.resize((size, size), Image.LANCZOS) if s != size else canvas


def adaptive_foreground(size=432, render=864):
    """Android 自适应图标前景：glyph 收在圆形安全区内"""
    s = max(render, size)
    out = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    inner = int(s * 0.62)
    g = mark(inner)
    off = (s - inner) // 2
    out.paste(g, (off, off), g)
    return out.resize((size, size), Image.LANCZOS)


def adaptive_background(size=432, render=864):
    return vertical_gradient(max(render, size), BG_TOP, BG_BOT).resize((size, size), Image.LANCZOS)


# ---------------- 字标 ----------------
FONT_PATH = '/System/Library/Fonts/Hiragino Sans GB.ttc'
NAME = '全民影视'


def load_font(px):
    try:
        return ImageFont.truetype(FONT_PATH, px, index=2)  # W6
    except Exception:
        return ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc', px)


def render_word(text, px, color, tracking_em=0.0):
    """逐字排版并裁到墨迹框（去掉字距/侧边空隙），返回 RGBA 图层"""
    font = load_font(px)
    tracking = px * tracking_em
    widths = [font.getlength(c) for c in text]
    W = int(sum(widths) + tracking * (len(text) - 1)) + px
    H = int(px * 1.6)
    layer = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x = px * 0.2
    baseline = int(H * 0.62)
    for c in text:
        d.text((x, baseline), c, font=font, fill=tuple(color) + (255,), anchor='ls')
        x += font.getlength(c) + tracking
    bbox = layer.getchannel('A').getbbox()
    return layer.crop(bbox) if bbox else layer


def pad_image(img, pad):
    out = Image.new('RGBA', (img.size[0] + pad * 2, img.size[1] + pad * 2), (0, 0, 0, 0))
    out.alpha_composite(img, (pad, pad))
    return out


def lockup(mark_px=320, text_ratio=0.50, color=WHITE, tracking_em=0.02, vertical=False):
    """标识组合（透明底）。墨迹对齐：字标视觉中心与 glyph 中心齐平"""
    word = render_word(NAME, max(12, int(mark_px * text_ratio)), color, tracking_em)
    glyph = mark(mark_px, mono=None if color == WHITE else color)
    pad = int(mark_px * 0.16)
    if vertical:
        gap = int(mark_px * 0.26)
        W = max(mark_px, word.size[0]) + pad * 2
        H = mark_px + gap + word.size[1] + pad * 2
        canvas = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        canvas.alpha_composite(glyph, ((W - mark_px) // 2, pad))
        canvas.alpha_composite(word, ((W - word.size[0]) // 2, pad + mark_px + gap))
        return canvas
    gap = int(mark_px * 0.30)
    W = mark_px + gap + word.size[0] + pad * 2
    H = max(mark_px, word.size[1]) + pad * 2
    canvas = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    canvas.alpha_composite(glyph, (pad, (H - mark_px) // 2))
    canvas.alpha_composite(word, (pad + mark_px + gap, (H - word.size[1]) // 2))
    return canvas


# ---------------- 品牌规范板 ----------------
def spec_board(path):
    W, H = 1600, 1350
    board = Image.new('RGBA', (W, H), (247, 248, 250, 255))
    d = ImageDraw.Draw(board)
    f_title, f_h, f_sm, f_tiny = load_font(54), load_font(24), load_font(20), load_font(17)
    GREY, DARK = (112, 120, 134, 255), (22, 26, 36, 255)

    d.text((72, 60), '全民影视 · 品牌标识', font=f_title, fill=DARK)
    d.text((74, 132), '屏幕框 + 播放字形 · 渐变 #3D9BFF → #7C5CFF · 圆角与 App 卡片同源', font=f_sm, fill=GREY)

    # A 主图标
    d.text((72, 190), '主图标', font=f_h, fill=DARK)
    board.alpha_composite(icon(480), (72, 232))
    d.text((72, 232 + 480 + 16), '512 × 512（缩放导出 192 / 144 / 96 / 72 / 48）', font=f_tiny, fill=GREY)

    # B 自适应
    d.text((600, 190), '自适应图标（前景 / 背景）', font=f_h, fill=DARK)
    av = adaptive_background(232, 696)
    av.alpha_composite(adaptive_foreground(232, 696))
    board.alpha_composite(av, (600, 232))
    circ = av.copy()
    cm = Image.new('L', av.size, 0)
    ImageDraw.Draw(cm).ellipse([0, 0, av.size[0] - 1, av.size[1] - 1], fill=255)
    circ.putalpha(cm)
    board.alpha_composite(circ, (600 + 232 + 26, 232))
    d.text((600, 232 + 232 + 16), '方形 / 圆形遮罩预览（glyph 收在安全区内）', font=f_tiny, fill=GREY)

    # C 各密度
    d.text((600, 540), '各密度导出', font=f_h, fill=DARK)
    x = 600
    base_y = 762
    for s in (192, 144, 96, 72, 48, 32):
        board.alpha_composite(icon(s, render=768), (x, base_y - s))
        d.text((x, base_y + 12), f'{s}', font=f_tiny, fill=GREY)
        x += s + 22

    # D 单色 + 色值
    d.text((1330, 190), '单色版', font=f_h, fill=DARK)
    mono = Image.new('RGBA', (150, 150), (24, 27, 34, 255))
    mono.alpha_composite(mark(150, mono=WHITE), (0, 0))
    board.alpha_composite(mono, (1330, 232))
    d.text((1330, 232 + 160), '纯白 / 透明底', font=f_tiny, fill=GREY)

    d.text((1330, 452), '品牌色', font=f_h, fill=DARK)
    y = 494
    for c, label in ((BR_START, '#3D9BFF'), (BR_END, '#7C5CFF'), (BG_BOT, '#0B0D13')):
        d.rounded_rectangle([1330, y, 1330 + 44, y + 44], radius=12, fill=c + (255,))
        d.text((1330 + 58, y + 12), label, font=f_sm, fill=(70, 76, 90, 255))
        y += 62

    d.text((1330, 700), '最小可用尺寸 32px', font=f_tiny, fill=GREY)
    d.text((1330, 724), '安全边距 12%（glyph 占 80%）', font=f_tiny, fill=GREY)

    # E 组合（深 / 浅）
    panel_w = 1456
    dark = Image.new('RGBA', (panel_w, 200), (16, 18, 24, 255))
    lk = lockup(132, 0.52)
    dark.alpha_composite(lk, (56, (200 - lk.size[1]) // 2))
    board.alpha_composite(dark, (72, 828))
    d.text((72, 828 + 210), '横版组合 · 深色场景（页头 / 启动页 / 应用商店）', font=f_tiny, fill=GREY)

    light = Image.new('RGBA', (panel_w, 200), (255, 255, 255, 255))
    lk2 = lockup(132, 0.52, color=INK)
    light.alpha_composite(lk2, (56, (200 - lk2.size[1]) // 2))
    board.alpha_composite(light, (72, 1070))
    d.text((72, 1070 + 210), '横版组合 · 浅色场景（官网页 / 文档）', font=f_tiny, fill=GREY)

    board.convert('RGB').save(path, quality=96)
    print('spec ->', path)


def main():
    os.makedirs(ICON_DIR, exist_ok=True)
    os.makedirs(BRAND_DIR, exist_ok=True)

    for name, s in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
        icon(s, render=768).save(f'{ICON_DIR}/icon_{name}.png')
        print(f'icon_{name}.png  {s}×{s}')
    icon(512, render=1024).save(f'{ICON_DIR}/icon_512.png')
    adaptive_foreground().save(f'{ICON_DIR}/icon_foreground.png')
    adaptive_background().save(f'{ICON_DIR}/icon_bg.png')
    print('icon_512 / icon_foreground / icon_bg')

    mark(1024).save(f'{BRAND_DIR}/logo-mark.png')
    mark(1024, mono=WHITE).save(f'{BRAND_DIR}/logo-mark-mono.png')
    mark(1024, mono=INK).save(f'{BRAND_DIR}/logo-mark-onlight.png')
    lockup(320).save(f'{BRAND_DIR}/logo-lockup.png')
    lockup(320, color=INK).save(f'{BRAND_DIR}/logo-lockup-onlight.png')
    lockup(320, vertical=True).save(f'{BRAND_DIR}/logo-lockup-vertical.png')
    print('brand: mark / mono / onlight / lockup / vertical')

    icon(256, render=768).save(os.path.join(ROOT, 'static', 'logo.png'))
    print('static/logo.png 256×256')

    spec_dir = os.path.join(ROOT, 'design')
    os.makedirs(spec_dir, exist_ok=True)
    spec_board(os.path.join(spec_dir, 'logo-spec.png'))


if __name__ == '__main__':
    main()

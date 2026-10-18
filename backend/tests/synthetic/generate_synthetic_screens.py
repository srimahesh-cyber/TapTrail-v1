#!/usr/bin/env python3
"""Generate realistic synthetic Android Settings screens for testing TapTrail.

These screens are 100% synthetic, containing standard Android AOSP Settings UI
layouts, and are used for testing Gemma 4 screen analysis, target identification,
and coordinate mapping without using any real personal or sensitive screen captures.
"""

import os
from PIL import Image, ImageDraw, ImageFont

OUTPUT_DIR = os.path.dirname(os.path.abspath(__file__))

# Standard mobile resolution (1080 x 2400 - modern 20:9 aspect ratio)
WIDTH = 1080
HEIGHT = 2400

# Color palette (Material 3 Android Settings style)
BG_COLOR = (246, 248, 250)        # #F6F8FA light surface
TOP_BAR_BG = (255, 255, 255)      # Pure white header
TEXT_PRIMARY = (31, 31, 31)       # Near black
TEXT_SECONDARY = (90, 94, 98)     # Muted gray
DIVIDER = (230, 233, 238)         # Subtle line
ACCENT_BLUE = (10, 87, 208)       # Android/Google Blue
ICON_BG = (222, 238, 254)         # Soft blue circle for icons
CARD_BG = (255, 255, 255)


def draw_status_bar(draw: ImageDraw.ImageDraw):
    """Draw realistic Android status bar."""
    # Time (e.g. 9:41) on left
    draw.text((60, 45), "9:41", fill=TEXT_PRIMARY)
    # Icons on right: Wi-Fi, Signal, Battery
    draw.rectangle([WIDTH - 200, 45, WIDTH - 160, 75], outline=TEXT_PRIMARY, width=2)
    draw.text((WIDTH - 150, 45), "100%", fill=TEXT_PRIMARY)


def draw_top_bar(draw: ImageDraw.ImageDraw, title: str, has_back: bool = False):
    """Draw top action bar / search bar."""
    draw_status_bar(draw)

    y_start = 140
    if has_back:
        # Back arrow
        draw.polygon([(70, y_start + 40), (100, y_start + 20), (100, y_start + 60)], fill=TEXT_PRIMARY)
        draw.line([(95, y_start + 40), (130, y_start + 40)], fill=TEXT_PRIMARY, width=4)
        draw.text((160, y_start + 20), title, fill=TEXT_PRIMARY)
    else:
        # Large title "Settings"
        draw.text((60, y_start + 20), title, fill=TEXT_PRIMARY)

        # Search bar
        search_y = y_start + 120
        draw.rounded_rectangle([50, search_y, WIDTH - 50, search_y + 110], radius=55, fill=(238, 242, 246))
        draw.text((120, search_y + 35), "Search settings", fill=TEXT_SECONDARY)


def draw_setting_item(
    draw: ImageDraw.ImageDraw,
    y: int,
    title: str,
    subtitle: str = "",
    has_toggle: bool = False,
    is_toggle_on: bool = False,
    highlight: bool = False,
) -> tuple[float, float, float, float]:
    """Draw a standard settings list item row and return normalized bounding box."""
    height = 160
    box_left = 50
    box_right = WIDTH - 50
    box_top = y
    box_bottom = y + height

    # Background card
    fill_color = (240, 246, 255) if highlight else CARD_BG
    draw.rounded_rectangle([box_left, box_top, box_right, box_bottom], radius=24, fill=fill_color)

    # Circular Icon placeholder
    icon_center_x = box_left + 60
    icon_center_y = box_top + height // 2
    draw.ellipse([icon_center_x - 36, icon_center_y - 36, icon_center_x + 36, icon_center_y + 36], fill=ICON_BG)

    # Text
    text_x = box_left + 125
    if subtitle:
        draw.text((text_x, box_top + 38), title, fill=TEXT_PRIMARY)
        draw.text((text_x, box_top + 92), subtitle, fill=TEXT_SECONDARY)
    else:
        draw.text((text_x, box_top + 60), title, fill=TEXT_PRIMARY)

    # Optional Toggle
    if has_toggle:
        toggle_w = 90
        toggle_h = 50
        toggle_x = box_right - 130
        toggle_y = box_top + (height - toggle_h) // 2
        bg = ACCENT_BLUE if is_toggle_on else (189, 193, 198)
        draw.rounded_rectangle([toggle_x, toggle_y, toggle_x + toggle_w, toggle_y + toggle_h], radius=25, fill=bg)
        # Knob
        knob_x = toggle_x + toggle_w - 25 if is_toggle_on else toggle_x + 25
        draw.ellipse([knob_x - 18, toggle_y + 7, knob_x + 18, toggle_y + 43], fill=(255, 255, 255))

    # Normalized bounding box [x1, y1, x2, y2]
    norm_bbox = (
        round(box_left / WIDTH, 4),
        round(box_top / HEIGHT, 4),
        round(box_right / WIDTH, 4),
        round(box_bottom / HEIGHT, 4),
    )
    return norm_bbox


def generate_main_settings():
    """Screen 0: Android Settings main screen."""
    img = Image.new("RGB", (WIDTH, HEIGHT), BG_COLOR)
    draw = ImageDraw.Draw(img)
    draw_top_bar(draw, "Settings", has_back=False)

    start_y = 420
    spacing = 180

    items = [
        ("Network & internet", "Mobile, Wi-Fi, hotspot"),
        ("Connected devices", "Bluetooth, pairing"),
        ("Apps", "Recent apps, default apps"),
        ("Notifications", "Notification history, conversations"),
        ("Battery", "84% - About 1 day left"),
        ("Storage", "45% used - 70 GB free"),
        ("Sound & vibration", "Volume, haptics, Do Not Disturb"),
        ("Display", "Dark theme, font size, night light"),
        ("Wallpaper & style", "Colors, app grid"),
        ("Security & privacy", "App security, screen lock"),
    ]

    bboxes = {}
    for i, (title, sub) in enumerate(items):
        y = start_y + (i * spacing)
        if y + spacing > HEIGHT - 100:
            break
        bbox = draw_setting_item(draw, y, title, sub)
        bboxes[title] = bbox

    out_path = os.path.join(OUTPUT_DIR, "settings_main.jpg")
    img.save(out_path, "JPEG", quality=92)
    print(f"Generated {out_path}")
    print(f"Target 'Network & internet' normalized bbox: {bboxes['Network & internet']}")
    return out_path, bboxes


def generate_network_settings():
    """Screen 1: Network & internet settings screen."""
    img = Image.new("RGB", (WIDTH, HEIGHT), BG_COLOR)
    draw = ImageDraw.Draw(img)
    draw_top_bar(draw, "Network & internet", has_back=True)

    start_y = 280
    spacing = 180

    items = [
        ("Internet", "Google_Guest_5G", False, False),
        ("SIMs", "Google Fi (Active)", False, False),
        ("Airplane mode", "", True, False),
        ("Hotspot & tethering", "Wi-Fi hotspot, USB, Bluetooth", False, False),
        ("Data Saver", "Off", False, False),
        ("VPN", "None", False, False),
        ("Private DNS", "Automatic", False, False),
    ]

    bboxes = {}
    for i, (title, sub, has_toggle, is_on) in enumerate(items):
        y = start_y + (i * spacing)
        if y + spacing > HEIGHT - 100:
            break
        bbox = draw_setting_item(draw, y, title, sub, has_toggle, is_on)
        bboxes[title] = bbox

    out_path = os.path.join(OUTPUT_DIR, "settings_network.jpg")
    img.save(out_path, "JPEG", quality=92)
    print(f"Generated {out_path}")
    print(f"Target 'Hotspot & tethering' normalized bbox: {bboxes['Hotspot & tethering']}")
    return out_path, bboxes


def generate_hotspot_settings():
    """Screen 2: Hotspot & tethering settings screen."""
    img = Image.new("RGB", (WIDTH, HEIGHT), BG_COLOR)
    draw = ImageDraw.Draw(img)
    draw_top_bar(draw, "Hotspot & tethering", has_back=True)

    start_y = 280
    spacing = 180

    items = [
        ("Wi-Fi hotspot", "Off", True, False),
        ("USB tethering", "USB not connected", True, False),
        ("Bluetooth tethering", "Not sharing phone's internet", True, False),
        ("One-time mobile data limit", "Disabled", False, False),
    ]

    bboxes = {}
    for i, (title, sub, has_toggle, is_on) in enumerate(items):
        y = start_y + (i * spacing)
        if y + spacing > HEIGHT - 100:
            break
        bbox = draw_setting_item(draw, y, title, sub, has_toggle, is_on)
        bboxes[title] = bbox

    out_path = os.path.join(OUTPUT_DIR, "settings_hotspot.jpg")
    img.save(out_path, "JPEG", quality=92)
    print(f"Generated {out_path}")
    print(f"Target 'Wi-Fi hotspot' normalized bbox: {bboxes['Wi-Fi hotspot']}")
    return out_path, bboxes


if __name__ == "__main__":
    generate_main_settings()
    generate_network_settings()
    generate_hotspot_settings()
    print("All synthetic test screenshots successfully generated!")

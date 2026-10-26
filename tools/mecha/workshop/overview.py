#!/usr/bin/env python3
"""Render the four workshop families together; never modifies shipped assets."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
FAMILIES = [('foundry', 'Foundry', 'Furnaces, bellows and working tools'),
            ('orbital', 'Orbital', 'Pressure suits, radios and tether reels'),
            ('tidal', 'Tidal', 'Portholes, pressure vessels and salvage claws'),
            ('domestic', 'Domestic', 'Tea service and household repair')]
ORDER = ('legs', 'body', 'arms', 'head')


def main():
    board = Image.new('RGB', (1280, 850), '#101418')
    draw = ImageDraw.Draw(board)
    title = ImageFont.load_default(size=36)
    label = ImageFont.load_default(size=24)
    small = ImageFont.load_default(size=17)
    draw.text((36, 30), 'Rivet / parts workshop', font=title, fill='#F2B134')
    draw.text((36, 82), '32 original modular parts. Eight complete builds. Same animation rig.', font=small, fill='#C5CDD2')
    for index, (family, name, description) in enumerate(FAMILIES):
        x, y = 28 + (index % 2) * 624, 126 + (index // 2) * 352
        draw.rounded_rectangle((x, y, x + 600, y + 330), radius=16, fill='#1B2129')
        draw.text((x + 20, y + 17), name, font=label, fill='#EDF2ED')
        draw.text((x + 20, y + 49), description, font=small, fill='#A5B1BA')
        for variant in (1, 2):
            sprite = Image.new('RGBA', (64, 64))
            for slot in ORDER:
                with Image.open(ROOT / 'assets' / family / f'{family}_{slot}_{variant}.png') as sheet:
                    sprite.alpha_composite(sheet.convert('RGBA').crop((0, 0, 64, 64)))
            enlarged = sprite.resize((224, 224), Image.Resampling.NEAREST)
            board.paste(enlarged, (x + 45 + (variant - 1) * 278, y + 83), enlarged)
    target = ROOT / 'assets' / 'overview.png'
    board.save(target)
    print(target)


if __name__ == '__main__':
    main()

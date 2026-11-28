#!/usr/bin/env python3
"""Builds app/src/main/assets/gamedb.tsv from libretro-database No-Intro .dat files.

Each line is: system <tab> key <tab> name
  system: nds, gba, gbc or gb
  key:    the 4-character game code from the cartridge header (nds, gba, gbc) or the
          CRC32 of the whole ROM as 8 upper-case hex digits (every system)
  name:   the No-Intro name, which is also the file name used by libretro-thumbnails

Usage: make_gamedb.py OUT.tsv SYSTEM=FILE.dat [SYSTEM=FILE.dat ...]
"""
import re
import sys

NAME = re.compile(r'^\s*name "(.*)"\s*$')
SERIAL = re.compile(r'^\s*serial "([^"]*)"\s*$')
CRC = re.compile(r'\bcrc ([0-9A-Fa-f]{8})\b')
# Releases that should not win over the normal one when several share a game code.
LESSER = re.compile(r'\((Beta|Proto|Demo|Sample|Kiosk|Debug|Alt|Rev [0-9A-Z]+|Pirate|Unl|Aftermarket)[^)]*\)', re.I)


def entries(path):
    """Yields (name, serial, crcs) for each game block of a .dat file."""
    name, serial, crcs, depth = None, None, [], 0
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            s = line.strip()
            if s.startswith('game (') or s == 'game (':
                name, serial, crcs, depth = None, None, [], 1
                continue
            if depth == 0:
                continue
            if s == ')':
                depth = 0
                if name:
                    yield name, serial, crcs
                continue
            m = NAME.match(line)
            if m and name is None:
                name = m.group(1)
                continue
            m = SERIAL.match(line)
            if m and serial is None:
                serial = m.group(1)
            if s.startswith('rom ('):
                crcs += CRC.findall(s)


def main():
    out_path = sys.argv[1]
    rows = {}
    for arg in sys.argv[2:]:
        system, path = arg.split('=', 1)
        for name, serial, crcs in entries(path):
            keys = [c.upper() for c in crcs]
            # Only plain 4-character codes match what the cartridge header holds.
            if serial and re.fullmatch(r'[0-9A-Z]{4}', serial) and system != 'gb':
                keys.append(serial)
            for key in keys:
                old = rows.get((system, key))
                better = old is None or (LESSER.search(old) and not LESSER.search(name)) \
                    or (bool(LESSER.search(old)) == bool(LESSER.search(name)) and len(name) < len(old))
                if better:
                    rows[(system, key)] = name
    with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
        for (system, key), name in sorted(rows.items()):
            f.write(f'{system}\t{key}\t{name}\n')
    print(f'gamedb: {len(rows)} keys -> {out_path}')


if __name__ == '__main__':
    main()

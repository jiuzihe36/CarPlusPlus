#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Derive the R8 keep rules that the injected ICCOA SDK dex needs.

Why this exists
---------------
The CarWith / CarLink protocol SDK ships as a prebuilt dex (``app/libs/icar-sdk.dex``)
that is appended to the APK *after* R8 has run (see ``injectIccoaDex`` in
``app/build.gradle.kts``). R8 never sees it, so:

* it cannot know which app-side classes the SDK calls at runtime, and
* it will happily remove or rename any class that no *app* code references, even when
  the SDK needs it.

That is not hypothetical: the release build crashed at startup with
``NoClassDefFoundError: androidx.collection.ArrayMap`` because R8 had stripped it — no app
code uses it, the SDK does. The debug build was unaffected because R8 is disabled there.

How it works
------------
For a dex, ``type_ids`` holds *every* type the dex mentions (referenced or defined) and
``class_defs`` holds the ones it *defines*. So:

    external      = type_ids(sdk) - class_defs(sdk)
    program       = class_defs(unminified app dexes, i.e. the debug APK)
    needed        = external & program

``needed`` is exactly the set of classes that live in an app dex, are reachable only from
the SDK, and therefore must survive R8 untouched. The classes are grouped by package so
the emitted rules stay maintainable.

Usage
-----
    python scripts/r8_keep_scan.py app/libs/icar-sdk.dex app/build/outputs/apk/github/debug/*.apk

Prints ready-to-paste ``-keep`` rules. Re-run after the SDK dex is updated.
"""
import struct
import sys
import zipfile


def uleb128(b, off):
    result = 0
    shift = 0
    while True:
        byte = b[off]
        off += 1
        result |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            break
        shift += 7
    return result, off


def dex_tables(b):
    """Return (all type descriptors, defined class descriptors) for one dex image."""
    class_defs_size, class_defs_off = struct.unpack_from('<II', b, 96)
    type_ids_size, type_ids_off = struct.unpack_from('<II', b, 64)
    string_ids_size, string_ids_off = struct.unpack_from('<II', b, 56)
    if type_ids_size == 0:
        return set(), set()

    type_ids = struct.unpack_from('<%dI' % type_ids_size, b, type_ids_off)
    string_ids = struct.unpack_from('<%dI' % string_ids_size, b, string_ids_off)

    def string_at(idx):
        off = string_ids[idx]
        _, p = uleb128(b, off)
        end = b.index(b'\x00', p)
        return b[p:end].decode('utf-8', 'replace')

    types = {string_at(t) for t in type_ids}
    defined = set()
    for i in range(class_defs_size):
        class_idx = struct.unpack_from('<I', b, class_defs_off + i * 32)[0]
        defined.add(string_at(type_ids[class_idx]))
    return types, defined


def iter_dex(path):
    """Yield (name, bytes) for a dex file or for every classes*.dex inside an archive."""
    if path.lower().endswith(('.apk', '.zip')):
        with zipfile.ZipFile(path) as z:
            for n in sorted(z.namelist(), key=lambda s: (len(s), s)):
                if n.startswith('classes') and n.endswith('.dex'):
                    yield n, z.read(n)
    else:
        with open(path, 'rb') as f:
            yield path, f.read()


def collect(path):
    """(all types, defined classes, {sha: name}) for every dex in `path`."""
    types, defined, by_hash = set(), set(), {}
    for name, blob in iter_dex(path):
        t, d = dex_tables(blob)
        types |= t
        defined |= d
        by_hash[(len(blob), hash(blob))] = name
    return types, defined, by_hash


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    sdk_path, app_path = argv[1], argv[2]

    sdk_types, sdk_defined, _ = collect(sdk_path)
    app_types, app_defined, _ = collect(app_path)
    # The debug APK already carries the injected SDK dex; drop whatever it defines so the
    # intersection below cannot be satisfied by the SDK's own classes.
    app_defined -= sdk_defined

    external = {t for t in sdk_types if t not in sdk_defined}
    needed = sorted(external & app_defined)

    print('# SDK dex      : %s' % sdk_path)
    print('#   types       : %d (%d defined, %d referenced)' % (len(sdk_types), len(sdk_defined), len(external)))
    print('# app dexes     : %s' % app_path)
    print('#   app classes : %d' % len(app_defined))
    print('# SDK-referenced classes that only R8 can remove: %d' % len(needed))
    print()

    if not needed:
        print('# (nothing to keep)')
        return 0

    packages = {}
    for d in needed:
        pkg = d[1:].rsplit('/', 1)[0] if '/' in d[1:] else ''
        packages.setdefault(pkg.replace('/', '.'), []).append(d)
    print('# --- one rule per package -----------------------------------------------')
    for pkg, members in sorted(packages.items()):
        print('-keep class %s.** { *; }    # %d classes: %s'
              % (pkg, len(members), ', '.join(sorted(m[1:-1].rsplit('/', 1)[-1] for m in members)[:6])
                 + (' ...' if len(members) > 6 else '')))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))

#!/usr/bin/env python3
"""Stage verified APKs and update the catalog atomically. Does not push to GitHub."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path(os.environ.get('ANDROID_HOME', '/opt/craft-android/sdk')) / 'build-tools/35.0.0'
SIGNER = 'f28bb27cd24137bb5f89af5dc377c6f67dbf214e9b9f5125b7fb14af1bce536e'
SELF = 'cz.jirkas.crafthub'
DESCRIPTIONS = {
    'cadcraft': 'Návrhy a technické kreslení ve 2D i 3D.',
    'deckcraft': 'Tvorba a přehrávání prezentací.',
    'designcraft': 'Sazba, publikace a vícestránkové návrhy.',
    'effectcraft': 'Pohyblivá grafika a vizuální efekty.',
    'filmcraft': 'Střih videa a práce s časovou osou.',
    'gridcraft': 'Tabulky, výpočty a práce s daty.',
    'lightcraft': 'Úpravy RAW fotografií a fotografická knihovna.',
    'pdfcraft': 'Čtení a úpravy PDF dokumentů.',
    'photocraft': 'Úpravy fotografií, malování a vrstvy.',
    'soundcraft': 'Nahrávání, střih a mixování zvuku.',
    'vectorcraft': 'Vektorová grafika a ilustrace.',
    'wordcraft': 'Textové dokumenty a jejich formátování.',
    'crafthub': 'Instalace, aktualizace a spouštění celé sady.',
}

def inspect(path):
    verified = subprocess.check_output([str(TOOLS / 'apksigner'), 'verify', '--print-certs', str(path)], text=True)
    signers = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)', verified)
    if signers != [SIGNER]:
        raise ValueError(f'Untrusted APK signing certificate: {path.name}')
    badging = subprocess.check_output([str(TOOLS / 'aapt2'), 'dump', 'badging', str(path)], text=True)
    def field(pattern):
        match = re.search(pattern, badging)
        if not match:
            raise ValueError(f'Missing APK metadata {pattern}: {path.name}')
        return match.group(1)
    package = field(r"package: name='([^']+)'")
    short = package.rsplit('.', 1)[-1]
    if package != SELF and (not package.startswith('com.artcraft.') or short not in DESCRIPTIONS):
        raise ValueError(f'Unknown package: {package}')
    if 'application-debuggable' in badging or 'launchable-activity:' not in badging:
        raise ValueError(f'Not a launchable release APK: {path.name}')
    size = path.stat().st_size
    if not 0 < size < 100 * 1024**2:
        raise ValueError(f'APK exceeds the plain Git limit: {path.name}')
    if package != SELF and "native-code: 'arm64-v8a'" not in badging:
        raise ValueError(f'Expected ARM64 APK: {path.name}')
    code = int(field(r"versionCode='(\d+)'") )
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    entry = {
        'packageId': package, 'name': field(r"application-label:'([^']+)'"),
        'description': DESCRIPTIONS[short], 'versionCode': code,
        'versionName': field(r"versionName='([^']+)'"),
        'minSdk': int(field(r"(?:minSdkVersion|sdkVersion):'(\d+)'")),
        'abi': 'any' if package == SELF else 'arm64-v8a',
        'apk': f'apk/{"craft-hub" if package == SELF else short}-{code}.apk',
        'size': size, 'sha256': digest, 'signerSha256': SIGNER,
    }
    return entry

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('inputs', nargs='+', type=Path, help='APK files or directories containing APK files')
    args = parser.parse_args()
    current = json.loads((ROOT / 'catalog.json').read_text()) if (ROOT / 'catalog.json').is_file() else {'apps': []}
    entries = {entry['packageId']: entry for entry in current['apps']}
    files = [p for path in args.inputs for p in (sorted(path.glob('*.apk')) if path.is_dir() else [path])]
    if not files:
        raise SystemExit('No APK inputs')
    staged = []
    for path in files:
        entry = inspect(path)
        old = entries.get(entry['packageId'])
        if old and (entry['versionCode'] < old['versionCode'] or (entry['versionCode'] == old['versionCode'] and entry['sha256'] != old['sha256'])):
            raise SystemExit(f"Increase versionCode before replacing {entry['name']}; existing versions are immutable.")
        entries[entry['packageId']] = entry
        staged.append((path, entry))
    # Validate every input before changing the published catalog or its immutable APK paths.
    for path, entry in staged:
        target = ROOT / entry['apk']
        target.parent.mkdir(exist_ok=True)
        if target.exists():
            with target.open('rb') as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != entry['sha256']:
                    raise SystemExit(f'Would overwrite an immutable APK: {target}')
        elif path.resolve() != target.resolve():
            shutil.copyfile(path, target)
        print(f"{entry['name']} {entry['versionName']} ({entry['versionCode']})")
    catalog = {'schemaVersion': 1, 'updatedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
               'apps': sorted(entries.values(), key=lambda e: e['name'].casefold())}
    temporary = ROOT / 'catalog.json.tmp'
    temporary.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(ROOT / 'catalog.json')
    # Exclude self metadata from the embedded offline catalog to avoid a circular APK hash.
    offline = dict(catalog, apps=[e for e in catalog['apps'] if e['packageId'] != SELF])
    if offline['apps']:
        offline_path = ROOT / 'launcher/assets/offline-catalog.json'
        # Stable offline metadata means staging the launcher does not change its own build inputs.
        offline.pop('updatedAt', None)
        offline_path.write_text(json.dumps(offline, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (ROOT / 'SHA256SUMS.txt').write_text(''.join(f"{e['sha256']}  {e['apk']}\n" for e in catalog['apps']))

if __name__ == '__main__':
    main()

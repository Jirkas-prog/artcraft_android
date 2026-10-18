#!/usr/bin/env python3
"""Build a release APK with the Android SDK and JDK; no Gradle or app dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get('ANDROID_HOME', '/opt/craft-android/sdk'))
TOOLS = SDK / 'build-tools/35.0.0'

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--version-code', type=int)
    parser.add_argument('--version-name')
    options = parser.parse_args()
    version = json.loads((ROOT / 'launcher/version.json').read_text())
    code = options.version_code or version['versionCode']
    name = options.version_name or version['versionName']
    if not 1 <= code <= 2147483647 or not __import__('re').fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:[-.][A-Za-z0-9]+)*', name):
        raise SystemExit('Invalid version')
    key = Path(os.environ.get('CRAFT_SIGNING_KEY', '/opt/craft-android/builder/keys/artcraft-builder.jks'))
    password = Path(os.environ.get('CRAFT_SIGNING_PASSWORD_FILE', '/opt/craft-android/builder/keys/.keystore-password'))
    if not key.is_file() or not password.is_file():
        raise SystemExit('Set CRAFT_SIGNING_KEY and CRAFT_SIGNING_PASSWORD_FILE. Never commit signing material.')
    if not (ROOT / 'launcher/assets/offline-catalog.json').is_file():
        raise SystemExit('First run tools/publish_catalog.py with the suite APK directory.')
    output = ROOT / 'build'
    output.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='craft-hub-build-') as tmp:
        work = Path(tmp)
        generated, classes, dex = (work / part for part in ('generated', 'classes', 'dex'))
        for folder in (generated, classes, dex):
            folder.mkdir()
        android = SDK / 'platforms/android-35/android.jar'
        run(TOOLS / 'aapt2', 'compile', '--dir', ROOT / 'launcher/res', '-o', work / 'resources.zip')
        run(TOOLS / 'aapt2', 'link', '-I', android, '--manifest', ROOT / 'launcher/AndroidManifest.xml',
            '--java', generated, '--min-sdk-version', '29', '--target-sdk-version', '35',
            '--version-code', code, '--version-name', name, '-A', ROOT / 'launcher/assets',
            '-o', work / 'unsigned.apk', work / 'resources.zip')
        config = generated / 'cz/jirkas/crafthub/BuildConfig.java'
        config.write_text(f'package cz.jirkas.crafthub; public final class BuildConfig {{ public static final String VERSION_NAME = "{name}"; public static final int VERSION_CODE = {code}; }}\n')
        sources = list((ROOT / 'launcher/src').rglob('*.java')) + list(generated.rglob('*.java'))
        run('javac', '--release', '8', '-encoding', 'UTF-8', '-classpath', android, '-d', classes, *sources)
        run('jar', 'cf', work / 'classes.jar', '-C', classes, '.')
        run(TOOLS / 'd8', '--release', '--min-api', '29', '--lib', android, '--output', dex, work / 'classes.jar')
        with zipfile.ZipFile(work / 'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as archive:
            for path in dex.glob('*.dex'):
                archive.write(path, path.name)
        run(TOOLS / 'zipalign', '-f', '-P', '16', '4', work / 'unsigned.apk', work / 'aligned.apk')
        signed = work / 'signed.apk'
        run(TOOLS / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'file:' + str(password),
            '--out', signed, work / 'aligned.apk')
        run(TOOLS / 'apksigner', 'verify', '--verbose', signed)
        destination = output / f'craft-hub-{code}.apk'
        shutil.copyfile(signed, destination)
        with destination.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        print(f'APK: {destination}\nSHA256: {digest}')

if __name__ == '__main__':
    main()

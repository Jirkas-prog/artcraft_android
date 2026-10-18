"""Verify suite APK metadata, dependency closure and 16 KB ELF LOAD alignment."""
import importlib.util
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sdk = Path(os.environ.get('ANDROID_HOME', '/opt/craft-android/sdk'))
ndk = Path(os.environ.get('ANDROID_NDK_HOME', str(sdk / 'ndk/28.2.13676358')))
llvm = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
system = {p.name for p in (llvm / 'sysroot/usr/lib/aarch64-linux-android/28').glob('*.so')}
spec = importlib.util.spec_from_file_location('publisher', ROOT / 'tools/publish_catalog.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
expected = json.loads((ROOT / 'android-port/release.json').read_text())
results = []
for apk in sorted(Path(sys.argv[1]).glob('*.apk')):
    entry = publisher.inspect(apk)
    app = entry['packageId'].split('.')[-1]
    if app not in expected:
        continue
    assert (entry['versionCode'], entry['versionName']) == (expected[app]['versionCode'], expected[app]['versionName']), apk
    alignments, needed = [], set()
    with zipfile.ZipFile(apk) as archive:
        libs = [n for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')]
        assert libs and all(n.startswith('lib/arm64-v8a/') for n in libs), apk
        for name in libs:
            data = archive.read(name)
            assert data[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<H', data, 18)[0] == 183, name
            offset = struct.unpack_from('<Q', data, 32)[0]
            size, count = struct.unpack_from('<HH', data, 54)
            for i in range(count):
                pos = offset + i * size
                if struct.unpack_from('<I', data, pos)[0] == 1:
                    alignments.append(struct.unpack_from('<Q', data, pos + 48)[0])
            with tempfile.TemporaryDirectory(prefix='craft-elf-check-') as tmp:
                path = Path(tmp) / Path(name).name
                path.write_bytes(data)
                dynamic = subprocess.check_output([str(llvm / 'bin/llvm-readelf'), '-d', str(path)], text=True)
                needed.update(re.findall(r'Shared library: \[([^\]]+)\]', dynamic))
        assert alignments and min(alignments) >= 16384, (apk, alignments)
        assert not (needed - {Path(n).name for n in libs} - system), (apk, needed)
    results.append(dict(app=app, versionCode=entry['versionCode'], versionName=entry['versionName'],
                        sha256=entry['sha256'], abi='arm64-v8a', signerSha256=entry['signerSha256'],
                        elf16kb=True, nativeDependenciesResolved=True))
    print('Verified', app, entry['versionName'], flush=True)
assert {r['app'] for r in results} == set(expected), 'The complete 12-app release is required'
output = ROOT / 'android-port/verification.json'
output.write_text(json.dumps(results, indent=2) + '\n')
print('Verified all 12 signed release APKs')

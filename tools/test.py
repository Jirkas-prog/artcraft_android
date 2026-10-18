#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
json_jar = Path(os.environ.get('ANDROID_JSON_JAR', '/usr/share/java/com.android.json.jar'))
if not json_jar.is_file():
    raise SystemExit('Install the test-only dependency: sudo apt-get install libandroid-json-java')
with tempfile.TemporaryDirectory(prefix='craft-core-test-') as folder:
    build = Path(folder)
    config = build / 'BuildConfig.java'
    config.write_text('package cz.jirkas.crafthub; public final class BuildConfig { public static final String VERSION_NAME = "test"; }\n')
    src = root / 'launcher/src/cz/jirkas/crafthub'
    subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8', '-classpath', str(json_jar), '-d', str(build),
                    str(src / 'Catalog.java'), str(src / 'Net.java'), str(config), str(root / 'tests/CoreTest.java')], check=True)
    subprocess.run(['java', '-ea', '-classpath', str(build) + os.pathsep + str(json_jar),
                    'cz.jirkas.crafthub.CoreTest', str(root / 'catalog.json')], check=True)

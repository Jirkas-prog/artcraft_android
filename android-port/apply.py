#!/usr/bin/env python3
"""Apply the tablet gesture port after artcraft-android generates its launcher/vendors."""
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve().parent


def replace_once(path, before, after):
    text = path.read_text()
    if after in text:
        return
    if text.count(before) != 1:
        raise RuntimeError(f"Unexpected source revision: {path}")
    path.write_text(text.replace(before, after))


def apply(root, app):
    release = json.loads((HERE / 'release.json').read_text())[app]
    source = root / 'source' / app
    patch = HERE / 'patches' / f'{app}.patch'
    if patch.exists():
        cmd = ['git', '-C', str(source), 'apply']
        if subprocess.run(cmd + ['--reverse', '--check', str(patch)], capture_output=True).returncode:
            subprocess.run(cmd + ['--check', str(patch)], check=True)
            subprocess.run(cmd + [str(patch)], check=True)
    vendor = source / 'vendor'
    replace_once(vendor / 'winit/src/platform_impl/android/mod.rs',
                 'force: Some(Force::Normalized(pointer.pressure() as f64)),',
                 'force: matches!(pointer.tool_type(), ToolType::Stylus | ToolType::Eraser)\n'
                 '                                            .then(|| Force::Normalized(pointer.pressure() as f64)),')
    replace_once(vendor / 'egui-winit/src/lib.rs',
                 '        // If we\'re not yet translating a touch or we\'re translating this very',
                 '        // Android fingers are recognized by the shared shell; pens remain immediate.\n'
                 '        #[cfg(target_os = "android")]\n'
                 '        if touch.force.is_none() { return; }\n'
                 '        // If we\'re not yet translating a touch or we\'re translating this very')
    launcher = source / 'apps' / f'{app}-android'
    lib = launcher / 'src/lib.rs'
    text = lib.read_text()
    if 'mod android_touch;' not in text:
        text += '\nmod android_touch;\n'
        lib.write_text(text)
    signature = 'fn raw_input_hook(&mut self, ctx: &egui::Context, raw: &mut egui::RawInput) {'
    if app == 'vectorcraft':
        replace_once(lib, signature.replace(' ctx:', ' _ctx:'), signature)
    replace_once(lib, signature, signature + '\n        android_touch::filter(ctx, raw);')
    shutil.copyfile(HERE / 'gestures/android_touch.rs', launcher / 'src/android_touch.rs')
    manifest = launcher / 'Cargo.toml'
    text = manifest.read_text()
    # cargo-apk derives Android versions from this wrapper's package SemVer.
    # Override the wrapper only; upstream library versions remain unchanged.
    text = re.sub(r'^version_(?:name|code)\s*=.*\n', '', text, flags=re.M)
    text, count = re.subn(r'^version(?:\.workspace)?\s*=.*$', f'version = "{release["versionName"]}"', text, count=1, flags=re.M)
    if count != 1:
        raise RuntimeError(f'Missing wrapper version: {manifest}')
    manifest.write_text(text)
    print(f'Tablet gestures applied: {app} ({release["versionCode"]})')


if __name__ == '__main__':
    apply(Path(sys.argv[1]).resolve(), sys.argv[2])

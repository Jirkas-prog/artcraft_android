"""Build the QA instrumentation, outside the published APK catalog."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import zipfile

here=Path(__file__).resolve().parent
sdk=Path(os.environ.get('ANDROID_HOME','/opt/craft-android/sdk'))
bt=sdk/'build-tools/35.0.0'
android=sdk/'platforms/android-35/android.jar'
out=here.parents[1]/'build/gesture-qa.apk'
out.parent.mkdir(exist_ok=True)
def run(*args): subprocess.run([str(a) for a in args],check=True)
with tempfile.TemporaryDirectory(prefix='craft-gesture-qa-') as t:
    work=Path(t)
    for d in ['classes','dex']: (work/d).mkdir()
    run(bt/'aapt2','link','-I',android,'--manifest',here/'AndroidManifest.xml','-o',work/'unsigned.apk')
    run('javac','--release','8','-classpath',android,'-d',work/'classes',here/'GestureProbe.java')
    run('jar','cf',work/'classes.jar','-C',work/'classes','.')
    run(bt/'d8','--release','--min-api','29','--lib',android,'--output',work/'dex',work/'classes.jar')
    with zipfile.ZipFile(work/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
        z.write(work/'dex/classes.dex','classes.dex')
    run(bt/'zipalign','-f','4',work/'unsigned.apk',work/'aligned.apk')
    run('keytool','-genkeypair','-keystore',work/'qa.jks','-storepass','qa-emulator-only','-keypass','qa-emulator-only','-alias','qa','-keyalg','RSA','-dname','CN=Local Emulator QA','-validity','2')
    run(bt/'apksigner','sign','--ks',work/'qa.jks','--ks-pass','pass:qa-emulator-only','--out',out,work/'aligned.apk')
print(out)

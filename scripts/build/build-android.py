"""Build Android with explicit output/cache paths; never place artifacts in the source tree."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tasks', nargs='*', default=['assembleDebug'])
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--signing-config', type=Path, help='Private release signing properties outside source control')
    parser.add_argument('--dev-ca', type=Path, help='Trust this local development CA for localhost in debug builds only')
    args = parser.parse_args()
    if hasattr(os, 'uname') and os.uname().sysname == 'Darwin':
        if not Path('/Volumes/SSD').is_mount():
            parser.error('External SSD is not mounted; no system disk fallback')
    env = os.environ.copy()
    if args.signing_config:
        signing = args.signing_config.resolve()
        if not signing.is_file():
            parser.error('Release signing configuration does not exist')
        env['FUNNYWORK_ANDROID_SIGNING_PROPERTIES'] = str(signing)
    storage = ROOT / '.local/agent'
    output = storage / 'apps/android-build'
    sdk = Path(env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT') or '/Volumes/SSD/System/Android/sdk')
    if not (sdk / 'platforms/android-36/android.jar').is_file():
        parser.error('Configure ANDROID_HOME with an installed Android 36 SDK')
    if not shutil.which('java'):
        parser.error('JDK 17 is required')
    env['ANDROID_HOME'] = env['ANDROID_SDK_ROOT'] = str(sdk)
    env.setdefault('GRADLE_USER_HOME', str(storage / 'packages/gradle'))
    env.setdefault('ANDROID_USER_HOME', str(storage / 'packages/android-home'))
    env.setdefault('ANDROID_AVD_HOME', str(storage / 'apps/android-avd'))
    env['FUNNYWORK_ANDROID_BUILD_DIR'] = str(output)
    env['FUNNYWORK_ANDROID_DEBUG_KEYSTORE'] = str(storage / 'packages/android/debug.keystore')
    if args.dev_ca:
        if any('debug' not in task.lower() and task != 'clean' for task in args.tasks):
            parser.error('--dev-ca requires explicit debug tasks; release and aggregate tasks are not allowed')
        ca = args.dev_ca.resolve()
        certificate = ca.read_bytes() if ca.is_file() else b''
        if not certificate.lstrip().startswith(b'-----BEGIN CERTIFICATE-----') or len(certificate) > 1000000:
            parser.error('--dev-ca must name a PEM CA certificate, never a private key')
        if b'PRIVATE KEY' in certificate:
            parser.error('--dev-ca must not contain a private key')
        network = storage / 'apps/android-dev-network'
        (network / 'res/raw').mkdir(parents=True, exist_ok=True)
        (network / 'res/xml').mkdir(parents=True, exist_ok=True)
        (network / 'res/raw/funnywork_dev_ca.pem').write_bytes(certificate)
        (network / 'res/xml/funnywork_dev_network.xml').write_text('''<network-security-config>
  <base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system" /></trust-anchors></base-config>
  <domain-config cleartextTrafficPermitted="false">
    <domain includeSubdomains="false">localhost</domain><domain includeSubdomains="false">127.0.0.1</domain>
    <trust-anchors><certificates src="system" /><certificates src="@raw/funnywork_dev_ca" /></trust-anchors>
  </domain-config>
</network-security-config>''')
        (network / 'AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application android:networkSecurityConfig="@xml/funnywork_dev_network" />
</manifest>''')
        env['FUNNYWORK_ANDROID_DEV_NETWORK'] = str(network)
    else:
        env.pop('FUNNYWORK_ANDROID_DEV_NETWORK', None)
    for key in ('GRADLE_USER_HOME', 'ANDROID_USER_HOME', 'ANDROID_AVD_HOME'):
        path = Path(env[key]).resolve()
        if ROOT.parts[:3] == ('/', 'Volumes', 'SSD') and not path.is_relative_to('/Volumes/SSD'):
            parser.error(f'{key} must remain on the external volume')
        path.mkdir(parents=True, exist_ok=True)
    (storage / 'packages/android').mkdir(parents=True, exist_ok=True)
    (storage / 'tmp').mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix='android-', dir=storage / 'tmp'))
    for key in ('TMPDIR', 'TMP', 'TEMP'):
        env[key] = str(tmp)
    env['JAVA_TOOL_OPTIONS'] = (env.get('JAVA_TOOL_OPTIONS', '') + f' -Djava.io.tmpdir="{tmp}"').strip()
    debug_key = Path(env['FUNNYWORK_ANDROID_DEBUG_KEYSTORE'])
    if not debug_key.exists():
        keytool = (str(Path(env['JAVA_HOME']) / 'bin' / ('keytool.exe' if os.name == 'nt' else 'keytool'))
                   if env.get('JAVA_HOME') else shutil.which('keytool'))
        if not keytool:
            parser.error('JDK keytool is required to create the isolated debug signing key')
        # Standard Android debug identity only. Release signing never uses this key.
        subprocess.run([str(keytool), '-genkeypair', '-keystore', str(debug_key), '-storepass', 'android',
                        '-alias', 'androiddebugkey', '-keypass', 'android', '-keyalg', 'RSA', '-keysize', '2048',
                        '-validity', '10000', '-dname', 'CN=Android Debug,O=Android,C=US'], env=env, check=True)
        if os.name != 'nt':
            debug_key.chmod(0o600)
    command = [str(ROOT / 'apps/android' / ('gradlew.bat' if os.name == 'nt' else 'gradlew')),
               '--no-daemon', '--console=plain', '--project-cache-dir', str(output / 'project-cache')]
    if args.offline:
        command.append('--offline')
    command.extend(args.tasks or ['assembleDebug'])
    result = subprocess.run(command, cwd=ROOT / 'apps/android', env=env)
    if result.returncode:
        print(f'Build failed. Temporary files retained: {tmp}')
    else:
        shutil.rmtree(tmp)
        print(f'Android build output: {output}')
    return result.returncode

if __name__ == '__main__':
    raise SystemExit(main())

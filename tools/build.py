"""Gradle Compose build; project-local caches and password-file signing."""
from pathlib import Path
from datetime import datetime
import hashlib
import os
import secrets
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '3.4.7'
VERSION_CODE = 31
# Capture compatibility uses the independently controlled native companion.
ROOT_COMPONENT_VERSION = '3.4.2'
UI_BASELINE_VERSION = '3.4.5'
APP_BASELINE_VERSION = '3.4.6'
ARTIFACT_NAME = 'ScreenshotFreedomA17'
SDK = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', r'D:\AndroidSDK')))
JDK = Path(os.environ.get('JAVA_HOME', r'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'))
TOOLS = SDK / 'build-tools' / '37.0.0'
BUILD = ROOT / '.build' / (datetime.now().strftime('%Y%m%d-%H%M%S-') + secrets.token_hex(3))
DIST = ROOT / 'dist'
ENV = dict(os.environ, JAVA_HOME=str(JDK), ANDROID_HOME=str(SDK),
           ANDROID_SDK_ROOT=str(SDK), GRADLE_USER_HOME=str(ROOT / '.gradle-cache'))
ENV['PATH'] = str(JDK / 'bin') + os.pathsep + ENV.get('PATH', '')


def run(*command):
    subprocess.run([str(value) for value in command], check=True, cwd=ROOT, env=ENV)


def gradle(*tasks):
    wrapper = ROOT / 'gradle/wrapper/gradle-wrapper.jar'
    if not wrapper.is_file():
        raise FileNotFoundError('Missing checked-in Gradle wrapper: ' + str(wrapper))
    run(JDK / 'bin/java.exe', '-classpath', wrapper,
        'org.gradle.wrapper.GradleWrapperMain', '--gradle-user-home', ROOT / '.gradle-cache',
        '--project-dir', ROOT, '--no-daemon', '--console=plain', *tasks)


def prepare_native():
    """Build the root-side PIE program and stage assets; it is not a JNI library."""
    builder = ROOT / 'tools/native_build.py'
    if not builder.is_file():
        raise FileNotFoundError('Missing native build entry: ' + str(builder))
    run(sys.executable, builder)
    program = ROOT / 'native/build/arm64-v8a/libsf17fix.so'
    if not program.is_file():
        raise FileNotFoundError(str(program))
    target = ROOT / 'app/src/main/assets/native/arm64-v8a/libsf17fix.so'
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(program, target)
    assets = ROOT / 'app/src/main/assets/root-component'
    assets.mkdir(parents=True, exist_ok=True)
    for name in ['module.prop', 'service.sh', 'uninstall.sh']:
        shutil.copyfile(ROOT / 'native/root-module' / name, assets / name)


def package_root_component():
    target = DIST / f'{ARTIFACT_NAME}-{ROOT_COMPONENT_VERSION}-root-module.zip'
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        files = [(ROOT / 'native/root-module' / name, name)
                 for name in ['module.prop', 'service.sh', 'uninstall.sh', 'customize.sh']]
        files.append((ROOT / 'native/build/arm64-v8a/libsf17fix.so', 'bin/sf17fix'))
        for path, name in files:
            entry = zipfile.ZipInfo(name)
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.create_system = 3
            entry.external_attr = (0o100755 if name.endswith('.sh') or name == 'bin/sf17fix' else 0o100644) << 16
            archive.writestr(entry, path.read_bytes())
    return target


def verify_unchanged_system_component(app_only=False):
    """Keep capture hooks and the native companion sealed during app updates."""
    import json
    import re
    baseline_version = APP_BASELINE_VERSION if app_only else UI_BASELINE_VERSION
    baseline_dir = ROOT / 'releases' / baseline_version
    baseline_source = baseline_dir / f'{ARTIFACT_NAME}-{baseline_version}-source.zip'
    if baseline_source.is_file():
        record = json.loads((baseline_dir / 'release-record.json').read_text(encoding='utf-8'))
        if hashlib.sha256(baseline_source.read_bytes()).hexdigest() != record['artifacts'][baseline_source.name]:
            raise RuntimeError('Preserved component source differs from its sealed release')
        prefix = ARTIFACT_NAME + '/'
        allowed = {'app/src/main/java/net/hawyn/screenshotfreedoma17/MainActivity.kt'}
        if app_only:
            # This release changes only the app's incoming framework IPC gate.
            # Never exempt capture hooks, native assets, or root service files.
            allowed.update({'app/src/main/AndroidManifest.xml',
                            'app/src/main/java/net/hawyn/screenshotfreedoma17/TrustedXposedProvider.java'})
        def functional(name):
            return (name.startswith('app/src/') or
                    (name.startswith('native/') and 'build' not in Path(name).parts) or
                    name == 'tools/native_build.py')
        with zipfile.ZipFile(baseline_source) as archive:
            frozen = {name[len(prefix):]: archive.read(name) for name in archive.namelist()
                      if name.startswith(prefix) and functional(name[len(prefix):])}
        current = {p.relative_to(ROOT).as_posix(): p for base in ['app/src', 'native', 'tools']
                   for p in (ROOT / base).rglob('*') if p.is_file() and functional(p.relative_to(ROOT).as_posix())}
        if set(frozen) - set(current) or (set(current) - set(frozen)) - allowed:
            raise RuntimeError('App update added or removed unexpected functional files')
        hashes = {}
        for name, path in current.items():
            data = path.read_bytes()
            if name not in allowed:
                if data != frozen[name]:
                    raise RuntimeError('App update changed a protected functional file: ' + name)
                hashes[name] = hashlib.sha256(data).hexdigest()
        report = {'result': 'PASS', 'versionName': VERSION, 'versionCode': VERSION_CODE,
                  'baseline_version': baseline_version, 'allowed_app_files' if app_only else 'allowed_ui_files': sorted(allowed),
                  'byte_identical_functional_files': hashes,
                  'manual_testing': 'Capture hooks/native companion are unchanged; user will perform UI and framework compatibility tests.'}
        suffix = '-app-parity-verification.json' if app_only else '-ui-parity-verification.json'
        target = ROOT / ('verification/v' + VERSION.replace('.', '') + suffix)
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print('APP_ONLY_SYSTEM_PARITY_OK' if app_only else 'UI_ONLY_SYSTEM_PARITY_OK',
              len(hashes), 'byte-identical functional files;', baseline_version)
        return
    if app_only:
        raise FileNotFoundError('Missing sealed app baseline: ' + str(baseline_source))
    frozen = ROOT / 'releases/2.0.0/functional-files-sha256.json'
    if not frozen.is_file():
        raise FileNotFoundError('Missing preserved 2.0 functional file manifest')
    records = json.loads(frozen.read_text(encoding='utf-8'))
    preview_source = 'app/src/main/java/net/hawyn/disableflagsecure17fix/SkipTestSurface.kt'
    appearance_source = 'app/src/main/java/net/hawyn/disableflagsecure17fix/UiPreferences.kt'
    preview_only = 0
    appearance_only = 0
    for name, expected in records.items():
        path = ROOT / name
        if not path.is_file():
            raise RuntimeError('UI-only build changed a preserved functional file: ' + name)
        current = path.read_bytes()
        if hashlib.sha256(current).hexdigest() == expected:
            continue
        if name == appearance_source:
            previous = ROOT / 'releases/2.3.0/EnableScreenshot17fix-2.3.0-source.zip'
            with zipfile.ZipFile(previous) as archive:
                original = archive.read(ARTIFACT_NAME + '/' + name)
            if hashlib.sha256(original).hexdigest() != expected:
                raise RuntimeError('Preserved appearance source differs from its frozen digest')
            text = current.decode('utf-8').replace('\r\n', '\n')
            for section in ['FIELDS', 'READ', 'WRITE']:
                begin = '// GLASS_APPEARANCE_' + section + '_BEGIN'
                end = '// GLASS_APPEARANCE_' + section + '_END'
                if text.count(begin) != 1 or text.count(end) != 1:
                    raise RuntimeError('Missing or duplicate glass appearance boundary: ' + section)
                text, count = re.subn(r'(?m)^[ \t]*' + re.escape(begin) + r'\n[\s\S]*?^[ \t]*' + re.escape(end) + r'\n', '', text)
                if count != 1:
                    raise RuntimeError('Invalid glass appearance block: ' + section)
            if text != original.decode('utf-8').replace('\r\n', '\n'):
                raise RuntimeError('Existing appearance keys or behavior changed')
            appearance_only += 1
            continue
        if name != preview_source:
            raise RuntimeError('UI-only build changed a preserved functional file: ' + name)
        # Permit isolated preview/readiness observers and one display-caption change.
        # Renderer geometry, SCVH lifecycle and transactions match the preserved source.
        previous = ROOT / 'releases/2.1.0/EnableScreenshot17fix-2.1.0-source.zip'
        with zipfile.ZipFile(previous) as archive:
            original = archive.read(ARTIFACT_NAME + '/' + name)
        if hashlib.sha256(original).hexdigest() != expected:
            raise RuntimeError('Preserved test source differs from its frozen digest')
        text = current.decode('utf-8').replace('\r\n', '\n')
        begin = '    // UI_PREVIEW_FACTORY_BEGIN\n'
        end = '    // UI_PREVIEW_FACTORY_END\n\n'
        if text.count(begin) != 1 or text.count(end) != 1:
            raise RuntimeError('Missing or duplicate preview factory boundary')
        start = text.index(begin)
        finish = text.index(end) + len(end)
        stripped = text[:start] + text[finish:]
        old_caption = '"看得到这四色与 SKIP，即采集到了子图层"'
        new_caption = '"截图中看得到这四色与 SKIP，即采集到了子图层"'
        if stripped.count(new_caption) == 1 and stripped.count(old_caption) == 0:
            stripped = stripped.replace(new_caption, old_caption)
        if finish <= start or stripped != original.decode('utf-8').replace('\r\n', '\n'):
            raise RuntimeError('Existing test renderer or SCVH implementation changed')
        preview_only += 1
    program = ROOT / 'native/build/arm64-v8a/libsf17fix.so'
    asset = ROOT / 'app/src/main/assets/native/arm64-v8a/libsf17fix.so'
    if not program.is_file() or program.read_bytes() != asset.read_bytes():
        raise RuntimeError('Native build and preserved APK asset differ')
    print('UI_ONLY_SYSTEM_PARITY_OK', len(records) - preview_only - appearance_only,
          'byte-identical files;', preview_only, 'test source with isolated UI preview/readiness/caption only;',
          appearance_only, 'appearance source with isolated glass slider additions only')


def package_sources():
    DIST.mkdir(exist_ok=True)
    final = DIST / f'{ARTIFACT_NAME}-{VERSION}.apk'
    source = DIST / f'{ARTIFACT_NAME}-{VERSION}-source.zip'
    selected = []
    for name in ['.gitignore', 'README.txt', 'LICENSE.txt', 'NOTICE.txt',
                 'settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat']:
        path = ROOT / name
        if path.exists():
            selected.append(path)
    for directory in ['app/src', 'gradle', 'tools']:
        selected.extend(p for p in (ROOT / directory).rglob('*') if p.is_file()
                        and '__pycache__' not in p.parts and p.suffix != '.pyc'
                        and p.name != 'preserve_release.py')
    selected.extend(p for p in (ROOT / 'native').rglob('*') if p.is_file()
                    and 'build' not in p.relative_to(ROOT / 'native').parts)
    selected.append(ROOT / 'app/build.gradle.kts')
    selected.extend(p for p in ROOT.glob('*.lockfile') if p.is_file())
    lockfile = ROOT / 'app/gradle.lockfile'
    if lockfile.exists():
        selected.append(lockfile)
    selected.extend(p for p in (ROOT / 'deps').glob('*.json') if p.is_file())
    for name in ['V2-TestReport.txt', 'V21-ReleaseNotes.txt', 'V22-ReleaseNotes.txt', 'V23-ReleaseNotes.txt', 'V24-ReleaseNotes.txt', 'V241-ReleaseNotes.txt', 'V242-ReleaseNotes.txt', 'V243-ReleaseNotes.txt', 'V244-ReleaseNotes.txt', 'V245-ReleaseNotes.txt', 'V246-ReleaseNotes.txt', 'V3-ReleaseNotes.txt', 'V31-ReleaseNotes.txt', 'V32-ReleaseNotes.txt', 'V33-ReleaseNotes.txt', 'V34-ReleaseNotes.txt', 'V641-ReleaseNotes.txt', 'V342-ReleaseNotes.txt', 'V343-ReleaseNotes.txt', 'V344-ReleaseNotes.txt', 'V345-ReleaseNotes.txt', 'V346-ReleaseNotes.txt', 'V347-ReleaseNotes.txt']:
        report = ROOT / 'verification' / name
        if report.is_file():
            selected.append(report)
    selected.extend(p for p in (ROOT / 'verification').glob('v300-*.json')
                    if p.is_file() and p.name != 'v300-package-verification.json')
    current_tag = 'v' + VERSION.replace('.', '')
    if current_tag != 'v300':
        selected.extend(p for p in (ROOT / 'verification').glob(current_tag + '-*.json')
                        if p.is_file() and p.name != current_tag + '-package-verification.json')
    with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(set(selected)):
            archive.write(path, ARTIFACT_NAME + '/' + path.relative_to(ROOT).as_posix())
    root_module = DIST / f'{ARTIFACT_NAME}-{ROOT_COMPONENT_VERSION}-root-module.zip'
    outputs = [p for p in [final, source, root_module] if p.is_file()]
    checksums = ''.join(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n'
                        for path in outputs)
    (DIST / 'SHA256SUMS.txt').write_text(checksums, encoding='utf-8')
    return checksums


def main():
    if '--sources-only' in sys.argv:
        print(package_sources())
        return
    if '--bootstrap-only' in sys.argv:
        gradle('--version')
        return
    if '--resolve-only' in sys.argv:
        gradle(':app:dependencies', '--configuration', 'releaseRuntimeClasspath', '--write-locks')
        return
    if '--compile-only' in sys.argv:
        gradle(':app:compileReleaseKotlin', ':app:compileReleaseJavaWithJavac')
        return
    for item in [TOOLS / 'zipalign.exe', TOOLS / 'lib/apksigner.jar', JDK / 'bin/java.exe']:
        if not item.is_file():
            raise FileNotFoundError(str(item))
    password_file = ROOT / 'signing/keystore.pass'
    keystore = ROOT / 'signing/screenshot-compat.jks'
    if not keystore.is_file() or not password_file.is_file():
        raise FileNotFoundError('Restore the existing signing/screenshot-compat.jks and signing/keystore.pass before building an update')
    if '--sign-only' not in sys.argv:
        if '--ui-only' in sys.argv and '--app-only' in sys.argv:
            raise ValueError('Choose either --ui-only or --app-only')
        if '--app-only' in sys.argv:
            verify_unchanged_system_component(app_only=True)
        elif '--ui-only' in sys.argv:
            verify_unchanged_system_component()
        else:
            prepare_native()
        gradle(':app:assembleRelease', '--write-locks')
    unsigned = ROOT / 'app/build/outputs/apk/release/app-release-unsigned.apk'
    if not unsigned.is_file():
        raise FileNotFoundError(str(unsigned))
    BUILD.mkdir(parents=True)
    DIST.mkdir(exist_ok=True)
    run(TOOLS / 'zipalign.exe', '-f', '-P', '16', '4', unsigned, BUILD / 'aligned.apk')
    signed = BUILD / 'signed.apk'
    run(JDK / 'bin/java.exe', '-jar', TOOLS / 'lib/apksigner.jar', 'sign',
        '--ks', keystore, '--ks-key-alias', 'screenshotcompat',
        '--ks-pass', 'file:' + str(password_file),
        '--out', signed, BUILD / 'aligned.apk')
    run(JDK / 'bin/java.exe', '-jar', TOOLS / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', signed)
    run(TOOLS / 'zipalign.exe', '-c', '-P', '16', '4', signed)
    with zipfile.ZipFile(signed) as archive:
        expected = {
            'META-INF/xposed/java_init.list': 'net.hawyn.screenshotfreedoma17.ScreenshotCompatModule',
            'META-INF/xposed/module.prop': 'minApiVersion=102',
            'META-INF/xposed/scope.list': 'system',
        }
        for name, value in expected.items():
            if value not in archive.read(name).decode('utf-8'):
                raise RuntimeError('APK module metadata check failed: ' + name)
        if 'classes.dex' not in archive.namelist():
            raise RuntimeError('APK has no classes.dex')
    final = DIST / f'{ARTIFACT_NAME}-{VERSION}.apk'
    shutil.copyfile(signed, final)
    package_root_component()
    checksums = package_sources()
    run(JDK / 'bin/keytool.exe', '-exportcert', '-rfc', '-keystore', keystore,
        '-storepass:file', password_file, '-alias', 'screenshotcompat', '-file', DIST / 'signing-certificate.pem')
    print('BUILD_OK', final)
    print(checksums)


if __name__ == '__main__':
    main()

"""Fetch official compile-only API and reference sources; Gradle resolves runtime dependencies."""
from pathlib import Path
import hashlib
import json
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[1]
out = root / 'deps'
out.mkdir(parents=True, exist_ok=True)
base = 'https://repo.maven.apache.org/maven2/'
artifacts = {
    'api-102.0.0.aar': ('io/github/libxposed/api/102.0.0/api-102.0.0.aar', 'cfded6d221db78fadd94d90cffaee5417a2e2047'),
    'api-102.0.0-sources.jar': ('io/github/libxposed/api/102.0.0/api-102.0.0-sources.jar', None),
    'service-102.0.0-sources.jar': ('io/github/libxposed/service/102.0.0/service-102.0.0-sources.jar', None),
    'miuix-ui-android-0.9.4-sources.jar': ('top/yukonga/miuix/kmp/miuix-ui-android/0.9.4/miuix-ui-android-0.9.4-sources.jar', None),
    'miuix-core-android-0.9.4-sources.jar': ('top/yukonga/miuix/kmp/miuix-core-android/0.9.4/miuix-core-android-0.9.4-sources.jar', None),
    'miuix-blur-android-0.9.4-sources.jar': ('top/yukonga/miuix/kmp/miuix-blur-android/0.9.4/miuix-blur-android-0.9.4-sources.jar', None),
}
resolved = {}
for name, (relative, sha1) in artifacts.items():
    url = base + relative
    path = out / name
    if not path.exists():
        data = urllib.request.urlopen(url, timeout=60).read()
        if sha1 and hashlib.sha1(data).hexdigest() != sha1:
            raise RuntimeError('Official artifact checksum differs: ' + name)
        path.write_bytes(data)
    data = path.read_bytes()
    if sha1 and hashlib.sha1(data).hexdigest() != sha1:
        raise RuntimeError('Cached artifact checksum differs: ' + name)
    resolved[name] = {'url': url, 'sha256': hashlib.sha256(data).hexdigest()}
    print(name, len(data), 'bytes')
with zipfile.ZipFile(out / 'api-102.0.0.aar') as archive:
    (out / 'api-102.0.0.jar').write_bytes(archive.read('classes.jar'))
(out / 'dependencies.json').write_text(json.dumps({
    'gradle': '9.8.0', 'agp': '9.4.1', 'kotlin': '2.4.20',
    'compose-multiplatform': '1.12.0', 'miuix': '0.9.4',
    'libxposed-api': '102.0.0', 'libxposed-service': '102.0.0',
    'hiddenapibypass': '6.2', 'reference-artifacts': resolved,
}, indent=2) + '\n', encoding='utf-8')

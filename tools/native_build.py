#!/usr/bin/env python3
"""Build the root-side AArch64 PIE with an installed Android NDK (Windows/Linux)."""
from pathlib import Path
import hashlib
import json
import os
import platform
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def ndk_root():
    for key in ('ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT', 'NDK_HOME'):
        if os.environ.get(key):
            root = Path(os.environ[key])
            if (root / 'source.properties').is_file():
                return root
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', r'D:\AndroidSDK')))
    candidates = list((sdk / 'ndk').glob('*')) if (sdk / 'ndk').is_dir() else []
    candidates += [sdk / 'ndk-bundle']
    for root in sorted(candidates, reverse=True):
        if (root / 'source.properties').is_file():
            return root
    raise FileNotFoundError('Android NDK not found; set ANDROID_NDK_HOME')


def main():
    ndk = ndk_root()
    hosts = {'Windows': 'windows-x86_64', 'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}
    host = hosts.get(platform.system())
    if not host:
        raise RuntimeError('Unsupported build host: ' + platform.system())
    prebuilt = ndk / 'toolchains/llvm/prebuilt' / host
    suffix = '.exe' if platform.system() == 'Windows' else ''
    clang = prebuilt / 'bin' / ('clang++' + suffix)
    readelf = prebuilt / 'bin' / ('llvm-readelf' + suffix)
    strip = prebuilt / 'bin' / ('llvm-strip' + suffix)
    for tool in (clang, readelf, strip):
        if not tool.is_file():
            raise FileNotFoundError(tool)
    out = ROOT / 'native/build/arm64-v8a'
    out.mkdir(parents=True, exist_ok=True)
    binary = out / 'libsf17fix.so'
    source = ROOT / 'native/sf17fix.cpp'
    temporary = ROOT / 'native/build/compiler-tmp'
    temporary.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment.update(TEMP=str(temporary), TMP=str(temporary), TMPDIR=str(temporary))
    command = [str(clang), '--target=aarch64-linux-android26',
               '--sysroot=' + str(prebuilt / 'sysroot'), '-std=c++20', '-O2',
               '-Wall', '-Wextra', '-Werror', '-fPIE', '-pie',
               '-fstack-protector-strong', '-fvisibility=hidden', '-static-libstdc++',
               '-Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384',
               '-Wl,--build-id=sha1', '-o', str(binary), str(source), str(ROOT / 'native/policy_broker.cpp')]
    subprocess.run(command, check=True, cwd=ROOT, env=environment)
    subprocess.run([str(strip), '--strip-debug', str(binary)], check=True, cwd=ROOT, env=environment)
    dynamic = subprocess.check_output([str(readelf), '-d', str(binary)], text=True)
    header = subprocess.check_output([str(readelf), '-h', str(binary)], text=True)
    program_headers = subprocess.check_output([str(readelf), '-l', str(binary)], text=True)
    symbols = subprocess.check_output([str(readelf), '--dyn-syms', str(binary)], text=True)
    if 'AArch64' not in header or 'DYN' not in header or 'PIE' not in dynamic:
        raise RuntimeError('Output must be an AArch64 PIE executable')
    if 'libc++_shared.so' in dynamic:
        raise RuntimeError('Unexpected shared C++ runtime dependency')
    (out / 'elf-verification.txt').write_text(header + dynamic + program_headers + symbols, encoding='utf-8')
    report = {'abi': 'arm64-v8a', 'kind': 'root PIE executable, named .so for APK extraction',
              'min_api': 26, 'ndk': str(ndk), 'ndk_revision': (ndk / 'source.properties').read_text(),
              'binary': str(binary), 'sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
              'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
              'policy_broker_sha256': hashlib.sha256((ROOT / 'native/policy_broker.cpp').read_bytes()).hexdigest(),
              'policy_header_sha256': hashlib.sha256((ROOT / 'native/policy_broker.hpp').read_bytes()).hexdigest(),
              'bytes': binary.stat().st_size, 'shared_cxx_runtime': False, 'command': command}
    (ROOT / 'native/build/native-build.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print('NATIVE_BUILD_OK', binary)
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Build the pinned libtorrent C ABI for desktop or mobile, without changing product identifiers."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.native-cache'
PIN = 'ef7dbf94b9198bc58f45951adcf1f041fcbc5ea0'
TARGETS = {
    'win-x64': 'x64-windows-static', 'linux-x64': 'x64-linux', 'linux-arm64': 'arm64-linux',
    'mac-x64': 'nexonic-x64-osx', 'mac-arm64': 'nexonic-arm64-osx',
    'mac-current': 'nexonic-arm64-osx' if platform.machine() == 'arm64' else 'nexonic-x64-osx', 'ios-arm64': 'nexonic-arm64-ios',
    'ios-sim-arm64': 'nexonic-arm64-ios-simulator', 'ios-sim-x64': 'nexonic-x64-ios-simulator',
    'android-arm64': 'nexonic-arm64-android', 'android-arm': 'nexonic-arm-android', 'android-x64': 'nexonic-x64-android',
}


def run(*args, env=None):
    subprocess.run([str(a) for a in args], cwd=ROOT, env=env, check=True)


def bootstrap():
    vcpkg = CACHE / 'vcpkg'
    if not (vcpkg / '.git').exists():
        # CI restores downloads without restoring the toolchain checkout. Keep that cache intact.
        vcpkg.mkdir(parents=True, exist_ok=True)
        run('git', 'init', vcpkg)
        run('git', '-C', vcpkg, 'remote', 'add', 'origin', 'https://github.com/microsoft/vcpkg.git')
        run('git', '-C', vcpkg, 'fetch', '--depth=1', 'origin', PIN)
        run('git', '-C', vcpkg, 'checkout', '--detach', 'FETCH_HEAD')
    revision = subprocess.check_output(['git', '-C', str(vcpkg), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != PIN:
        raise SystemExit('Native toolchain revision differs from the pinned, reviewed source')
    binary = vcpkg / ('vcpkg.exe' if os.name == 'nt' else 'vcpkg')
    if not binary.exists():
        if os.name == 'nt': run('cmd', '/d', '/c', vcpkg/'bootstrap-vcpkg.bat', '-disableMetrics')
        else: run(vcpkg/'bootstrap-vcpkg.sh', '-disableMetrics')
    return vcpkg


def build(target, test):
    triplet = TARGETS[target]
    vcpkg = bootstrap()
    folder = CACHE / 'build' / target
    installed = CACHE / 'installed'
    stage = CACHE / 'sdk' / target
    mobile = target.startswith(('ios-', 'android-'))
    static = target.startswith(('mac-', 'ios-'))
    env = dict(os.environ, VCPKG_DISABLE_METRICS='1', VCPKG_BINARY_SOURCES=f'clear;files,{CACHE / "binaries"},readwrite')
    if target.startswith('android-'):
        ndk = env.get('ANDROID_NDK_HOME') or env.get('ANDROID_NDK_ROOT') or env.get('ANDROID_NDK_LATEST_HOME')
        if not ndk:
            sdk = env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT')
            candidates = sorted((Path(sdk)/'ndk').glob('*')) if sdk else []
            ndk = str(candidates[-1]) if candidates else None
        if not ndk: raise SystemExit('Android NDK is required to build the native torrent library')
        env['ANDROID_NDK_HOME'] = ndk
    command = ['cmake', '-S', str(ROOT/'native'), '-B', str(folder), '-DCMAKE_BUILD_TYPE=Release',
               f'-DCMAKE_TOOLCHAIN_FILE={vcpkg / "scripts/buildsystems/vcpkg.cmake"}', f'-DVCPKG_TARGET_TRIPLET={triplet}',
               f'-DVCPKG_INSTALLED_DIR={installed}', f'-DVCPKG_OVERLAY_TRIPLETS={ROOT / "native/triplets"}', f'-DNDM_TORRENT_STATIC={"ON" if static else "OFF"}',
               f'-DNDM_TORRENT_TESTS={"ON" if test and not mobile else "OFF"}', f'-DCMAKE_INSTALL_PREFIX={stage}']
    if os.name != 'nt': command += ['-G', 'Ninja']
    if target.startswith('android-'):
        # vcpkg's toolchain only cross-compiles the dependencies; chain-load the NDK's so this library is built for the phone too.
        abi = {'android-arm64': 'arm64-v8a', 'android-arm': 'armeabi-v7a', 'android-x64': 'x86_64'}[target]
        command += [f'-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE={Path(env["ANDROID_NDK_HOME"]) / "build/cmake/android.toolchain.cmake"}',
                    f'-DANDROID_ABI={abi}', '-DANDROID_PLATFORM=android-29', '-DANDROID_STL=c++_static']
    if target.startswith('mac-'):
        command += [f'-DCMAKE_OSX_ARCHITECTURES={"arm64" if "arm64" in triplet else "x86_64"}', '-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0']
    if target.startswith('ios-'):
        command += ['-DCMAKE_SYSTEM_NAME=iOS', '-DCMAKE_OSX_DEPLOYMENT_TARGET=17.0',
                    f'-DCMAKE_OSX_SYSROOT={"iphonesimulator" if "sim" in target else "iphoneos"}',
                    f'-DCMAKE_OSX_ARCHITECTURES={"x86_64" if target.endswith("x64") else "arm64"}']
    run(*command, env=env)
    run('cmake', '--build', folder, '--config', 'Release', '--parallel', str(min(os.cpu_count() or 2, 6)), env=env)
    if test and not mobile: run('ctest', '--test-dir', folder, '-C', 'Release', '--output-on-failure', env=env)
    run('cmake', '--install', folder, '--config', 'Release', env=env)
    notices = stage / 'notices'; notices.mkdir(parents=True, exist_ok=True)
    inventory = []
    for notice in sorted((installed/triplet/'share').glob('*/copyright')):
        name = notice.parent.name
        shutil.copyfile(notice, notices/f'{name}.txt')
        inventory.append(name)
    # Preserve libtorrent's additional embedded notices, not just its top-level COPYING file.
    upstream = vcpkg/'buildtrees/libtorrent/src'
    headers = set()
    for source in upstream.rglob('*'):
        if not source.is_file() or source.suffix not in ('.cpp', '.hpp', '.h', '.c'): continue
        text = source.read_text(errors='replace').lstrip('﻿\n\r ')
        block = text[:text.find('*/')+2] if text.startswith('/*') and '*/' in text else '\n'.join(line for line in text.splitlines()[:80] if line.startswith('//'))
        if 'opyright' in block: headers.add(block)
    if headers: (notices/'libtorrent-embedded-notices.txt').write_text('\n\n'.join(sorted(headers))+'\n')
    (stage/'native-components.json').write_text(json.dumps(dict(engine='libtorrent', version='2.0.11', toolchain=PIN, target=target, notices=inventory), indent=2)+'\n')
    if static:
        archives = sorted((installed/triplet/'lib').glob('*.a'))
        run('xcrun', 'libtool', '-static', '-o', stage/'lib/libCNexonicTorrent.a', stage/'lib/libnexonic_torrent.a', *archives)
    print('NATIVE SDK READY:', stage)
    return stage


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', choices=sorted(TARGETS))
    parser.add_argument('--test', action='store_true')
    args = parser.parse_args()
    build(args.target, args.test)


if __name__ == '__main__': main()

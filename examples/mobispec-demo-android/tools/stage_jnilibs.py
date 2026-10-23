#!/usr/bin/env python3
"""Copy the Android drivers and their libraries into app/src/main/jniLibs/arm64-v8a.

Executables are renamed lib<name>.so: the installer only extracts lib*.so files into
nativeLibraryDir, and that directory is the only app-reachable place an app may exec from.
QNN runtime libraries should be the ones the device sweeps load (/data/local/tmp/qnn6, QAIRT 2.44.0);
pull them with `adb pull /data/local/tmp/qnn6/libQnn*.so <dir>` and pass --qnn-libs <dir>. Without
that the host SDK copy (2.37.1) is staged and build-info.json says so.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

APP = Path(__file__).resolve().parents[1]
REPO = APP.parents[1]
DRIVERS = {
    'llama-speculative-eagle-mast': 'libms_mast.so',
    'llama-speculative-eagle-2': 'libms_eagle2.so',
    'llama-speculative-dflash-qnn-npu': 'libms_dflash_npu.so',
    'llama-qnn-vlm-run': 'libms_vlm_run.so',
    'llama-simple-qnn': 'libms_simple_qnn.so',
}
LIBS = ['libllama.so', 'libllama-common.so', 'libggml.so', 'libggml-base.so', 'libggml-cpu.so', 'libggml-opencl.so', 'libmtmd.so']
# libQnnHtpPrepare.so (85 MB) only compiles graphs; every demo method loads precompiled context binaries
# (P0 2026-10-23: NPU AR and VLM Ours ran token-identical without it)
QNN = ['libQnnHtp.so', 'libQnnSystem.so', 'libQnnHtpV81Stub.so', 'libQnnHtpV81Skel.so']
NDK = Path('/home/youngmin/android-ndk-r26d/toolchains/llvm/prebuilt/linux-x86_64')
STRIP = NDK / 'bin/llvm-strip'
READELF = NDK / 'bin/llvm-readelf'
# libggml-cpu.so links the NDK's OpenMP runtime dynamically (the sweeps push the same file to qnn6/)
NDK_LIBS = {'libomp.so': NDK / 'lib/clang/17/lib/linux/aarch64/libomp.so'}
# resolved by the platform: bionic, plus the vendor public libraries declared in AndroidManifest.xml
SYSTEM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libOpenCL.so', 'libcdsprpc.so'}


def sha256(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


def qnn_source(root):
    """A flat directory of pulled libraries, or a QAIRT SDK tree."""
    root = Path(root)
    out = {}
    for name in QNN:
        for cand in (root / name, root / 'lib/aarch64-android' / name, root / 'lib/hexagon-v81/unsigned' / name):
            if cand.is_file():
                out[name] = cand
                break
        else:
            sys.exit(f'{name} not found under {root}')
    return out


def git(*args):
    return subprocess.run(['git', '-C', str(REPO), *args], capture_output=True, text=True).stdout.strip()


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--build', default=str(REPO / 'build-android-demo/bin'))
    ap.add_argument('--qnn-libs', default='/home/youngmin/qairt/2.37.1-v81')
    ap.add_argument('--no-strip', action='store_true', help='keep debug info (the APK grows by ~100 MB)')
    a = ap.parse_args()

    build, dst = Path(a.build), APP / 'app/src/main/jniLibs/arm64-v8a'
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    staged = {}
    for src, name in [*((build / k, v) for k, v in DRIVERS.items()), *((build / l, l) for l in LIBS), *((v, k) for k, v in NDK_LIBS.items())]:
        if not src.is_file():
            sys.exit(f'missing {src}: build the demo targets first (see README.md)')
        shutil.copy2(src, dst / name)
        if not a.no_strip:
            subprocess.run([str(STRIP), '--strip-debug', str(dst / name)], check=True)
        staged[name] = {'from': str(src), 'sha256_source': sha256(src)}
    qnn = qnn_source(a.qnn_libs)
    for name, src in qnn.items():
        shutil.copy2(src, dst / name)
        staged[name] = {'from': str(src), 'sha256_source': sha256(src)}
    for name in staged:
        staged[name]['bytes'] = (dst / name).stat().st_size
    # every DT_NEEDED must be staged or come from the platform, or the driver dies in the linker
    unresolved = {}
    for name in staged:
        out = subprocess.run([str(READELF), '-d', str(dst / name)], capture_output=True, text=True).stdout
        for line in out.splitlines():
            if '(NEEDED)' in line:
                lib = line.split('[')[1].split(']')[0]
                if lib not in staged and lib not in SYSTEM:
                    unresolved.setdefault(lib, []).append(name)
    if unresolved:
        sys.exit(f'unresolved NEEDED libraries: {unresolved}')

    info = {
        'staged_at': time.strftime('%Y-%m-%d %H:%M:%S'),
        'git_head': git('rev-parse', 'HEAD'),
        'git_branch': git('rev-parse', '--abbrev-ref', 'HEAD'),
        # the demo tree carries uncommitted research code on top of HEAD; this pins exactly which
        'source_diff_sha256': hashlib.sha256(git('diff', 'HEAD').encode()).hexdigest(),
        'qnn_runtime': str(a.qnn_libs),
        # a flat directory is what `adb pull` of the sweep's qnn6 libraries produces
        'qnn_pulled_from_device': (Path(a.qnn_libs) / 'libQnnHtp.so').is_file(),
        'stripped': not a.no_strip,
        'files': staged,
    }
    assets = APP / 'app/src/main/assets'
    assets.mkdir(parents=True, exist_ok=True)
    (assets / 'build-info.json').write_text(json.dumps(info, indent=1) + '\n')
    total = sum(v['bytes'] for v in staged.values())
    print(f'staged {len(staged)} files, {total / 2**20:.1f} MiB -> {dst}')
    if not info['qnn_pulled_from_device']:
        print('WARNING: QNN runtime is the host SDK copy, not the 2.44.0 libraries the sweeps load on the S26', file=sys.stderr)


if __name__ == '__main__':
    main()

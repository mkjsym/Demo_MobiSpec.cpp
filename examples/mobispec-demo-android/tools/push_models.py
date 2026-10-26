#!/usr/bin/env python3
"""Place every file demo-manifest.json needs under the app's models root on the S26.

Dry run by default: prints each copy unit, where it comes from and what it costs. --apply performs it.
A unit is a whole context directory or a single file. Sources, in order: the copy the sweeps already
keep on the phone under /data/local/tmp (copied on the device, nothing crosses the adb link), then a
server copy (adb push). Units found nowhere are reported and skipped.

--apply refuses to start while a sweep holds the device lock (/data/local/tmp/.cvlock) or a study
driver is running, and keeps a free-space reserve on /data.

--direct copies nothing: it points the manifest at the sweeps' own copies under /data/local/tmp
(models_root '/', every path rewritten to its phone location) and pushes that manifest. Use it when
the app can read /data/local/tmp (P0 check); the phone keeps no second copy of ~17 GiB of models.
"""
import argparse
import json
import os
import shlex
import subprocess
import sys
from pathlib import Path

SERIAL = 'R5KL20FMLCN'
ADB = ['adb', '-H', '127.0.0.1', '-P', os.environ.get('ANDROID_ADB_SERVER_PORT', '10037'), '-s', SERIAL]
APP_ROOT = '/sdcard/Android/data/com.mobispec.demo/files'
RESERVE_GIB = 5
MAIN = Path('/home/youngmin/dev/llm/MobiSpec.cpp')

T = '/data/local/tmp'
VR = f'{T}/vlm-register-mobile-20261019'
QA = Path('/data/youngmin/qnn-artifacts/vlm-register-mobile-20261019')
# unit (manifest path) -> (copy on the phone, copy on the server)
UNITS = {
    'npu/llm/ctx_ar32': (f'{T}/qnn6/ctx_ar32', None),
    'npu/llm/ctx_dflash4b_w4ne': (f'{T}/qnn6/ctx_dflash4b_w4ne', None),
    'npu/llm/ctx_dspark4b_w4ne': (f'{T}/qnn6/ctx_dspark4b_w4ne', None),
    'npu/draft4b_embd_f16.bin': (f'{T}/qnn6/draft4b_embd_f16.bin', None),
    'npu/llm/Qwen3-4B-q4_0-outq4.gguf': (f'{T}/Qwen3-4B-q4_0-outq4.gguf', None),
    'npu/llm/Qwen3-4B-DSpark-b7-q4_0-emb.gguf': (f'{T}/Qwen3-4B-DSpark-b7-q4_0-emb.gguf', None),
    'npu/vlm/ctx_vlm_a32_c4096': (f'{VR}/ctx_vlm_a32_c4096', QA / 'ctx_vlm_a32_c4096'),
    'npu/vlm/ctx_vlm_deep_a32_c4096_v2': (f'{VR}/ctx_vlm_deep_a32_c4096_v2', QA / 'ctx_vlm_deep_a32_c4096_v2'),
    'npu/vlm/ctx-none-20k-seed42': (f'{VR}/ctx-none-20k-seed42', QA / 'export/ctx-none-20k-seed42'),
    'npu/vlm/ctx-full-20k-seed42-c4096': (f'{VR}/ctx-full-20k-seed42-c4096', QA / 'export/ctx-full-20k-seed42-c4096'),
    'npu/vlm/ctx-none-20k-seed42-c4096': (f'{VR}/ctx-none-20k-seed42-c4096', QA / 'export/ctx-none-20k-seed42-c4096'),
    # exported for the agent demo (2026-10-26) into its own folders, on the server and on the phone
    'npu/vlm/ctx-deep-dynreg8-20k-seed42-c4096': (f'{T}/mobispec-demo/models/ctx-deep-dynreg8-20k-seed42-c4096',
                                                  Path('/data/youngmin/demo-models/export-ours-c4096/ctx-deep-dynreg8-20k-seed42-c4096')),
    'npu/vlm/ctx-none-20k-seed42-inject256': (f'{VR}/ctx-none-20k-seed42-inject256', QA / 'export/ctx-none-20k-seed42-inject256'),
    'npu/vlm/ctx-deep-dynreg8-20k-seed42': (f'{VR}/ctx-deep-dynreg8-20k-seed42', QA / 'export/ctx-deep-dynreg8-20k-seed42'),
    'npu/vlm/ctx-full-20k-seed42-inject256': (f'{VR}/ctx-full-20k-seed42-inject256', QA / 'export/ctx-full-20k-seed42-inject256'),
    'npu/vlm/ctx-deep-dynreg8-20k-seed42-inject256': (f'{VR}/ctx-deep-dynreg8-20k-seed42-inject256', QA / 'export/ctx-deep-dynreg8-20k-seed42-inject256'),
    'npu/vlm/deep-dynreg8-20k-seed42-registers-f32.bin': (f'{VR}/deep-dynreg8-20k-seed42-registers-f32.bin',
                                                          QA / 'export/deep-dynreg8-20k-seed42/registers-f32.bin'),
    'npu/vlm/target_embeddings.f16': (f'{T}/vlm-dflash-20260913/target_embeddings.f16',
                                      Path('/home/youngmin/dev/llm/specinfer_QNN/experiments/vlm-dflash-20260913/target_embeddings.f16')),
    'npu/vlm/Qwen3-VL-4B-Instruct-Q4_0.gguf': (f'{T}/lcpp/Qwen3-VL-4B-Instruct-Q4_0.gguf', None),
    'npu/vlm/mmproj-Qwen3VL-4B-F16.gguf': (f'{T}/vlm-dflash-20260913/mmproj-Qwen3VL-4B-F16.gguf', None),
    'npu/vlm/mmproj-Qwen3VL-4B-Q8_0.gguf': (f'{T}/mobispec-demo/models/mmproj-Qwen3VL-4B-Q8_0.gguf',
                                           Path('/data/llamacpp_bench/models/mmproj/mmproj-Qwen3VL-4B-Q8_0.gguf')),
    # 6-8 month GGUFs (device cleanup group G4, kept): their directory varies, so they are searched for
    'gpu/vicuna_q4_0_output4.gguf': ('find:vicuna_q4_0_output4.gguf', None),
    'gpu/EAGLE_q4_0_output4.gguf': ('find:EAGLE_q4_0_output4.gguf', None),
    'gpu/llama-38i_q4_0_output4.gguf': ('find:llama-38i_q4_0_output4.gguf', None),
    'gpu/EAGLE-llama38i_q4_0_output4.gguf': ('find:EAGLE-llama38i_q4_0_output4.gguf', Path('/data/EAGLE-llama38i_q4_0_output4.gguf')),
}


def adb(*args, check=True):
    r = subprocess.run([*ADB, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f'adb {" ".join(args)} failed: {r.stderr.strip()}')
    return r.stdout


def sh(cmd, check=True):
    return adb('shell', cmd, check=check)


def locate(dev):
    """'find:<name>' -> the shallowest copy on the phone (identical copies sit in several people's directories)."""
    if not (dev and dev.startswith('find:')):
        return dev
    found = sh(f'find {T} -name {shlex.quote(dev[5:])} -type f 2>/dev/null', check=False).split()
    return min(found, key=lambda f: (f.count('/'), f)) if found else None


def preset_unit(rel, image_names):
    """Fixture tensors and the page image of one VLM preset (only the selected pages are copied)."""
    name = Path(rel).name
    if name in image_names:
        return f'{VR}/images/{image_names[name]}', None
    return f'{VR}/fixtures/{name}', QA / 'fixtures-m1' / name


def plan_units(manifest, scenarios):
    validation = {json.loads(l)['id']: json.loads(l) for l in
                  (MAIN / 'docs/results/raw/vlm-register-doc20k-20261016/data/validation.jsonl').read_text().splitlines()}
    inputs = {x['id']: x for x in json.loads((MAIN / 'docs/results/raw/vlm-register-mobile-20261019/inputs-128.json').read_text())['inputs']}
    files, image_names = {}, {}
    for sc in manifest['scenarios']:
        if scenarios and sc['id'] not in scenarios:
            continue
        for meth in sc['methods']:
            for f in meth['files']:
                files[f['path']] = f.get('bytes')
        for p in sc.get('prompts', []):
            if p.get('image'):
                image_names[Path(p['image']).name] = inputs[p['id']]['domain'] + '-' + Path(validation[p['id']]['image']).name
    units = {}
    for rel, size in files.items():
        if rel.startswith('npu/vlm/presets/'):
            dev, host = preset_unit(rel, image_names)
            units[rel] = [dev, host, size or 0]
            continue
        unit = next((u for u in UNITS if rel == u or rel.startswith(u + '/')), None)
        if unit is None:
            units[rel] = [None, None, size or 0]
            continue
        dev, host = UNITS[unit]
        units.setdefault(unit, [dev, host, 0])[2] += size or 0
    return units


def direct_manifest(manifest, units):
    """Rewrite every models-root path to the phone copy of its unit; all units must exist on the phone."""
    where, missing = {}, []
    for rel, (dev, _host, _size) in units.items():
        dev = locate(dev)
        if not dev or sh(f'[ -e {shlex.quote(dev)} ] && echo OK', check=False).strip() != 'OK':
            missing.append(rel)
        else:
            where[rel] = dev
    if missing:
        sys.exit(f'not on the phone: {missing}')
    # longest unit first, so 'npu/vlm/ctx-x-inject256' is not taken for a file of 'npu/vlm/ctx-x'
    order = sorted(where, key=len, reverse=True)

    def phone(rel):
        for u in order:
            if rel == u or rel.startswith(u + '/'):
                return where[u] + rel[len(u):]
        sys.exit(f'no unit for {rel}')

    def sub(text):
        if '${MODELS}/' not in text:
            return text
        head, rel = text.split('${MODELS}/', 1)
        return head + phone(rel)

    for sc in manifest['scenarios']:
        if not any(u for u in units if any(f['path'] == u or f['path'].startswith(u + '/') for m in sc['methods'] for f in m['files'])):
            continue
        sc['models_root'] = '/'
        for m in sc['methods']:
            m['args'] = [sub(a) for a in m['args']]
            m['env'] = {k: sub(v) for k, v in m['env'].items()}
            for f in m['files']:
                f['path'] = phone(f['path']).lstrip('/')
        for pr in sc.get('prompts', []):
            if pr.get('image'):
                pr['image'] = phone(pr['image']).lstrip('/')
            if pr.get('fixture'):   # a file-name prefix (QNN_VLM_LOAD appends the suffixes), not a unit of its own
                pr['fixture'] = f"{VR}/fixtures/{Path(pr['fixture']).name}".lstrip('/')
    return manifest


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('manifest')
    ap.add_argument('--scenario', action='append', help='limit to these scenario ids')
    ap.add_argument('--apply', action='store_true')
    ap.add_argument('--direct', action='store_true', help='no copies: point the manifest at /data/local/tmp and push it')
    ap.add_argument('--no-push', action='store_true', help='with --direct: write the direct manifest only')
    a = ap.parse_args()
    manifest = json.loads(Path(a.manifest).read_text())
    units = plan_units(manifest, a.scenario)
    if a.direct:
        out = Path(a.manifest).with_suffix('.direct.json')
        out.write_text(json.dumps(direct_manifest(manifest, units), indent=1, ensure_ascii=False) + '\n')
        if a.no_push:
            print(f'{out} written ({len(units)} units read in place)')
            return
        sh(f'mkdir -p {APP_ROOT}')
        adb('push', str(out), f'{APP_ROOT}/demo-manifest.json')
        print(f'{out} pushed to {APP_ROOT}/demo-manifest.json ({len(units)} units read in place)')
        return

    total = sum(u[2] for u in units.values())
    print(f'{len(units)} copy units, {total / 2**30:.2f} GiB counted (units sized 0 are known only on the phone)')
    for rel, (dev, host, size) in sorted(units.items()):
        src = dev or (str(host) if host else 'NO SOURCE')
        print(f'  {size / 2**20:9.1f} MiB  {rel}  <-  {src}{"  (server fallback)" if dev and host else ""}')
    if not a.apply:
        print('dry run; pass --apply to copy (only when no sweep is using the phone)')
        return

    if sh(f'[ -d {T}/.cvlock ] && echo LOCKED', check=False).strip() == 'LOCKED':
        sys.exit(f'a sweep holds {T}/.cvlock; not touching the phone')
    if sh('pidof llama-speculative-dflash-qnn-npu llama-qnn-vlm-run', check=False).strip():
        sys.exit('a study driver is running on the phone; not touching it')
    free_kb = int(sh("df -k /data | tail -1 | awk '{print $4}'").strip())
    if free_kb / 2**20 - total / 2**30 < RESERVE_GIB:
        sys.exit(f'/data has {free_kb / 2**20:.1f} GiB free; {total / 2**30:.1f} GiB needed plus a {RESERVE_GIB} GiB reserve')

    failed = []
    for rel, (dev, host, _) in sorted(units.items()):
        dst = f'{APP_ROOT}/models/{rel}'
        sh(f'mkdir -p {shlex.quote(str(Path(dst).parent))}')
        dev = locate(dev)
        # a re-run replaces the unit; cp -r into an existing directory would nest it instead
        if dev and sh(f'[ -e {shlex.quote(dev)} ] && rm -rf {shlex.quote(dst)} && cp -r {shlex.quote(dev)} {shlex.quote(dst)} && echo OK',
                      check=False).strip() == 'OK':
            print('copied on device', rel)
        elif host and Path(host).exists():
            adb('push', str(host), dst)
            print('pushed', rel)
        else:
            failed.append(rel)
            print('NO SOURCE', rel, file=sys.stderr)
    adb('push', a.manifest, f'{APP_ROOT}/demo-manifest.json')
    print(f'manifest pushed; {len(failed)} unit(s) missing' + (f': {failed}' if failed else ''))


if __name__ == '__main__':
    main()

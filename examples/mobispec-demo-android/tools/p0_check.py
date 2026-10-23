#!/usr/bin/env python3
"""P0 device checks: run a manifest method inside the app and from the adb shell, then compare.

  app <scenario> <prompt> <method>   start the run through the app's intent hook, wait for its RESULT line,
                                     sample the driver's RSS meanwhile, fetch the saved record
  adb <scenario> <prompt> <method>   run the app's packaged binary from the adb shell with the same
                                     arguments and environment (events to a file, as the app sees them)
  compare <scenario> <prompt> <method>   app vs adb: tokens identical, AL equal, t/s within +-7%

Results go to --out (one JSON per run). The manifest is the direct one (push_models.py --direct).
Before every run the phone must be free: no study driver, no sweep lock, battery >= 10 percent.
"""
import argparse
import base64
import json
import os
import shlex
import subprocess
import sys
import threading
import time
from pathlib import Path

SERIAL = 'R5KL20FMLCN'
ADB = ['adb', '-H', '127.0.0.1', '-P', os.environ.get('ANDROID_ADB_SERVER_PORT', '10037'), '-s', SERIAL]
PKG = 'com.mobispec.demo'
DEV = '/data/local/tmp/mobispec-demo'   # adb-side prompt files and event logs (the app keeps its own)
STUDY_DRIVERS = 'llama-speculative-dflash-qnn-npu llama-qnn-vlm-run llama-simple-qnn llama-speculative-eagle-mast'
TPS_TOLERANCE = 0.07


def sh(cmd, check=True, timeout=None):
    r = subprocess.run([*ADB, 'shell', cmd], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        sys.exit(f'adb shell failed ({r.returncode}): {cmd[:200]}\n{r.stderr.strip()}')
    return r.stdout


def phone_free():
    if sh(f'pidof {STUDY_DRIVERS}', check=False).strip():
        sys.exit('a study driver is running on the phone')
    if sh('[ -d /data/local/tmp/.cvlock ] && echo CVLOCK', check=False).strip():
        sys.exit('a sweep holds /data/local/tmp/.cvlock')
    level = int(sh("dumpsys battery | grep ' level:' | head -1", check=False).split(':')[1])
    if level < 10:
        sys.exit(f'battery {level} percent: wait for 15 percent')
    return level


def lib_dir():
    return sh(f'dirname $(pm path {PKG} | sed s/package://)').strip() + '/lib/arm64'


def load(manifest, scenario, method):
    m = json.loads(Path(manifest).read_text())
    sc = next(s for s in m['scenarios'] if s['id'] == scenario)
    return sc, next(x for x in sc['methods'] if x['id'] == method)


def sample_rss(stop, out):
    """Peak RSS (KiB) of any packaged driver process, polled every 0.5 s."""
    while not stop.is_set():
        r = subprocess.run([*ADB, 'shell', "ps -A -o RSS,ARGS | grep libms_ | grep -v grep"], capture_output=True, text=True)
        for line in r.stdout.splitlines():
            parts = line.split()
            if parts and parts[0].isdigit():
                out['peak_rss_kib'] = max(out.get('peak_rss_kib', 0), int(parts[0]))
        stop.wait(0.5)


def run_app(a):
    level = phone_free()
    subprocess.run([*ADB, 'logcat', '-c'], check=True)
    sh('input keyevent KEYCODE_WAKEUP')
    stop, rss = threading.Event(), {}
    t = threading.Thread(target=sample_rss, args=(stop, rss), daemon=True)
    t.start()
    sh(f'am start -n {PKG}/.MainActivity --es scenario {a.scenario} --es prompt {a.prompt} --es methods {a.method}')
    log = subprocess.Popen([*ADB, 'logcat', '-v', 'time', '-s', 'MobiSpecDemo:V'], stdout=subprocess.PIPE, text=True)
    result, tail, t0 = None, [], time.time()
    try:
        for line in log.stdout:
            if 'TAIL ' in line:
                tail.append(line.split('TAIL ', 1)[1].rstrip())
            if 'RESULT ' in line:
                result = json.loads(line.split('RESULT ', 1)[1])
                break
            if 'AUTORUN no' in line or time.time() - t0 > a.timeout:
                break
    finally:
        log.kill()
        stop.set(); t.join()
    if result is None:
        sys.exit('no RESULT from the app (timeout or bad ids)')
    rec = None
    if result.get('record'):
        rec = json.loads(sh(f"run-as {PKG} cat {shlex.quote(result['record'])}"))
    out = dict(kind='app', battery_start=level, **rss, result=result, tail=tail,
               ids=[i for s in (rec or {}).get('steps', []) for i in s['ids']])
    save(a, 'app', out)
    print(json.dumps({k: v for k, v in result.items() if k not in ('done', 'device')}), 'peak_rss_mib=%.0f' % (rss.get('peak_rss_kib', 0) / 1024))
    if tail:
        print('\n'.join(tail[-10:]))


def run_adb(a):
    level = phone_free()
    sc, me = load(a.manifest, a.scenario, a.method)
    prompt = next(p for p in sc['prompts'] if p['id'] == a.prompt)
    L = a.lib or lib_dir()
    root = sc.get('models_root') or '/'
    work = f'{DEV}/run'
    events = f'{work}/{a.scenario}-{a.prompt}-{a.method}-{int(time.time())}.jsonl'
    pfile = f'{work}/prompt-{a.scenario}-{a.prompt}.txt'
    text = sc['prompt_format'].replace('{prompt}', prompt['text']) if sc.get('prompt_format') else prompt['text']
    local = Path(a.out) / f'prompt-{a.scenario}-{a.prompt}.txt'
    local.parent.mkdir(parents=True, exist_ok=True)
    local.write_text(text)
    sh(f'mkdir -p {work}')
    subprocess.run([*ADB, 'push', str(local), pfile], check=True, capture_output=True)
    values = {'MODELS': root.rstrip('/'), 'LIB': L, 'PROMPT_FILE': pfile, 'N': str(sc['n_predict']),
              'FIXTURE': '/' + prompt['fixture'] if prompt.get('fixture') else '',
              'IMAGE': '/' + prompt['image'] if prompt.get('image') else ''}

    def res(s):
        for k, v in values.items():
            s = s.replace('${' + k + '}', v)
        return s
    env = {'LD_LIBRARY_PATH': f'{L}:/vendor/lib64', 'ADSP_LIBRARY_PATH': f'{L};/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp',
           'MOBISPEC_EVENTS': events, **({} if a.no_tree else {'MOBISPEC_EVENTS_TREE': '1'}), **{k: res(v) for k, v in me['env'].items()}}
    argv = [*(['taskset', me['affinity']] if me.get('affinity') else []), f'{L}/{me["bin"]}', *[res(x) for x in me['args']]]
    # export, not env(1): the app's library path contains '=' and env would read it as an assignment
    cmd = f'cd {work} && export ' + ' '.join(f'{k}={shlex.quote(v)}' for k, v in env.items()) + ' && ' + shlex.join(argv)
    stop, rss = threading.Event(), {}
    t = threading.Thread(target=sample_rss, args=(stop, rss), daemon=True)
    t.start()
    t0 = time.time()
    r = subprocess.run([*ADB, 'shell', cmd + ' > /dev/null 2> ' + events + '.log; echo EXIT=$?'], capture_output=True, text=True, timeout=a.timeout)
    stop.set(); t.join()
    wall = time.time() - t0
    exit_code = int(r.stdout.strip().rsplit('EXIT=', 1)[-1]) if 'EXIT=' in r.stdout else -1
    lines = sh(f'cat {events}', check=False).splitlines()
    ids, done, marks = [], {}, {}
    for line in lines:
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if e.get('ev') == 'step':
            ids += e['ids']
        elif e.get('ev') == 'done':
            done = {k: v for k, v in e.items() if k not in ('ev', 't_us')}
        elif e.get('ev') in ('loaded', 'decode_start'):
            marks[e['ev']] = e['t_us']
    tail = sh(f'tail -n 15 {events}.log', check=False).splitlines() if exit_code else []
    out = dict(kind='adb', battery_start=level, exit=exit_code, wall_s=wall, **rss, done=done, marks=marks, ids=ids, tail=tail, command=cmd)
    save(a, 'adb', out)
    print(json.dumps(dict(exit=exit_code, wall_s=round(wall, 1), tokens=len(ids), **{k: done.get(k) for k in ('tps', 'al', 'decode_us')})),
          'peak_rss_mib=%.0f' % (rss.get('peak_rss_kib', 0) / 1024))
    if tail:
        print('\n'.join(tail))


def save(a, kind, out):
    d = Path(a.out) / a.scenario / a.prompt / a.method
    d.mkdir(parents=True, exist_ok=True)
    (d / f'{kind}-{int(time.time())}.json').write_text(json.dumps(out, indent=1) + '\n')


def latest(a, kind):
    files = sorted((Path(a.out) / a.scenario / a.prompt / a.method).glob(f'{kind}-*.json'))
    return json.loads(files[-1].read_text()) if files else None


def compare(a):
    app, adb_ = latest(a, 'app'), latest(a, 'adb')
    if not app or not adb_:
        sys.exit('need one app and one adb run first')
    da, dd = app['result']['done'], adb_['done']
    k = min(len(app['ids']), len(adb_['ids']))
    diff = next((i for i in range(k) if app['ids'][i] != adb_['ids'][i]), None)
    rel = da['tps'] / dd['tps'] - 1 if da.get('tps') and dd.get('tps') else None
    ok = diff is None and len(app['ids']) == len(adb_['ids']) and da.get('al') == dd.get('al') and rel is not None and abs(rel) <= TPS_TOLERANCE
    print(json.dumps(dict(method=a.method, tokens_app=len(app['ids']), tokens_adb=len(adb_['ids']), first_diff=diff,
                          al_app=da.get('al'), al_adb=dd.get('al'), tps_app=da.get('tps'), tps_adb=dd.get('tps'),
                          tps_rel=round(rel, 4) if rel is not None else None, pass_=ok)))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('what', choices=['app', 'adb', 'compare'])
    ap.add_argument('scenario')
    ap.add_argument('prompt')
    ap.add_argument('method')
    ap.add_argument('--manifest', default=str(Path(__file__).resolve().parents[1] / 'demo-manifest.direct.json'))
    ap.add_argument('--out', default='/data/youngmin/demo-models/p0')
    ap.add_argument('--timeout', type=int, default=900)
    ap.add_argument('--lib', help='library directory for the adb run instead of the app\'s (e.g. a copy without one library)')
    ap.add_argument('--no-tree', action='store_true', help='no per-step trees in the adb run (the app asks for them by default)')
    a = ap.parse_args()
    {'app': run_app, 'adb': run_adb, 'compare': compare}[a.what](a)


if __name__ == '__main__':
    main()

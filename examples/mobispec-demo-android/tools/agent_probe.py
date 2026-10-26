#!/usr/bin/env python3
"""Host probe for the NPU-VLM agent (plan docs/impl-specs/android-demo-agentic-plan-20261026.md, A1).

The phone runs the app's packaged driver in serve mode (MOBISPEC_SERVE=/dev/stdin, fed over adb); the host
observes and acts with odbench's adb modules (read-only import) and builds the prompt with agent_spec.py,
the same text the app will send.

  serve-check <method> <episode>   one-shot vs served requests on two recorded steps (A, B, A after B):
                                   token IDs and AL must match
  episode <task> <method>          a live episode: observe, prompt, served request, act, verify, restore
  replay <episode> <method>        the recorded steps (same prompt bytes and screenshot) through another method:
                                   per-step token identity and timing
  report <episode>...              per-step time split and task totals

Run with the android_world env's python (odbench imports android_world). Before every episode the phone
must be free: no study driver, no sweep lock, battery >= 10 percent (resume at 15).
"""
import argparse
import base64
import io
import json
import os
import queue
import shlex
import subprocess
import sys
import threading
import time
from collections import Counter
from pathlib import Path

ODBENCH = '/home/youngmin/workspace/qwen3_vl_androidworld'
sys.path.insert(0, ODBENCH)
sys.path.insert(0, str(Path(__file__).resolve().parent))
import agent_spec   # noqa: E402

SERIAL = 'R5KL20FMLCN'
PORT = os.environ.get('ANDROID_ADB_SERVER_PORT', '10037')
ADB = ['adb', '-H', '127.0.0.1', '-P', PORT, '-s', SERIAL]
PKG = 'com.mobispec.demo'
WORK = '/data/local/tmp/mobispec-demo/agent'
SCENARIO = 'npu-qwen3vl-agent'
STUDY_DRIVERS = 'llama-speculative-dflash-qnn-npu llama-qnn-vlm-run llama-simple-qnn llama-speculative-eagle-mast'
OUT = Path('/data/youngmin/demo-models/agent')


def sh(cmd, check=True, timeout=120):
    r = subprocess.run([*ADB, 'shell', cmd], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        sys.exit(f'adb shell failed ({r.returncode}): {cmd[:200]}\n{r.stderr.strip()}')
    return r.stdout


def phone_free():
    if sh(f'pidof {STUDY_DRIVERS} libms_vlm_run.so libms_dflash_npu.so', check=False).strip():
        sys.exit('a driver is running on the phone')
    if sh('[ -d /data/local/tmp/.cvlock ] && echo CVLOCK', check=False).strip():
        sys.exit('a sweep holds /data/local/tmp/.cvlock')
    level = int(sh("dumpsys battery | grep ' level:' | head -1").split(':')[1])
    if level < 10:
        sys.exit(f'battery {level} percent: wait for 15 percent')
    return level


def method_of(manifest, mid):
    sc = next(s for s in json.loads(Path(manifest).read_text())['scenarios'] if s['id'] == SCENARIO)
    return sc, next(m for m in sc['methods'] if m['id'] == mid)


class Server:
    """One served driver on the phone. Requests go to its stdin; events come back through `tail -f`."""

    def __init__(self, method, tag, n_predict, serve=True):
        L = sh(f'dirname $(pm path {PKG} | sed s/package://)').strip() + '/lib/arm64'
        self.events = f'{WORK}/{tag}.jsonl'
        self.log = f'{WORK}/{tag}.log'
        env = {'LD_LIBRARY_PATH': f'{L}:/vendor/lib64', 'ADSP_LIBRARY_PATH': f'{L};/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp',
               'MOBISPEC_EVENTS': self.events, **{k: v.replace('${N}', str(n_predict)) for k, v in method['env'].items()}}
        if not serve:
            env.pop('MOBISPEC_SERVE')
        self.argv = [*(['taskset', method['affinity']] if method.get('affinity') else []), f'{L}/{method["bin"]}',
                     *[a.replace('${N}', str(n_predict)) for a in method['args']]]
        self.env = env

    def start(self, extra_args=()):
        # export, not env(1): the app's library path contains '='
        cmd = (f'mkdir -p {WORK} && cd {WORK} && : > {self.events} && export '
               + ' '.join(f'{k}={shlex.quote(v)}' for k, v in self.env.items())
               + ' && exec ' + shlex.join([*self.argv, *extra_args]) + f' > {self.log} 2>&1')
        self.t_start = time.time()
        self.proc = subprocess.Popen([*ADB, 'shell', cmd], stdin=subprocess.PIPE, text=True)
        time.sleep(1.0)
        self.tail = subprocess.Popen([*ADB, 'shell', f'tail -n +1 -f {self.events}'], stdout=subprocess.PIPE, text=True)
        self.q = queue.Queue()
        threading.Thread(target=lambda: [self.q.put(l) for l in self.tail.stdout], daemon=True).start()
        return self

    def next_event(self, timeout):
        t0 = time.time()
        while time.time() - t0 < timeout:
            try:
                line = self.q.get(timeout=1.0)
            except queue.Empty:
                if self.proc.poll() is not None:
                    raise RuntimeError(f'driver exited ({self.proc.returncode}):\n' + sh(f'tail -n 20 {self.log}', check=False))
                continue
            try:
                return json.loads(line)
            except ValueError:
                continue
        raise RuntimeError(f'no event for {timeout} s')

    def wait_loaded(self, timeout=180):
        while True:
            e = self.next_event(timeout)
            if e['ev'] == 'loaded':
                self.load_s = time.time() - self.t_start
                return e

    def collect(self, timeout=600):
        """Events of one request (or of the one-shot run) up to its done."""
        marks, ids, raw = {}, [], b''
        while True:
            e = self.next_event(timeout)
            if e['ev'] in ('request', 'decode_start'):
                marks[e['ev']] = e['t_us']
            elif e['ev'] == 'step':
                ids += e['ids']
                raw += base64.b64decode(e['b64'])
            elif e['ev'] == 'done':
                return {'ids': ids, 'text': raw.decode('utf-8', 'replace'), 'marks': marks,
                        'done': {k: v for k, v in e.items() if k != 'ev'}}

    def request(self, rid, prompt_remote, image_remote, n_predict):
        t0 = time.time()
        self.proc.stdin.write(f'{rid}\t{prompt_remote}\t{image_remote}\t{n_predict}\n')
        self.proc.stdin.flush()
        r = self.collect()
        r['host_ms'] = (time.time() - t0) * 1000
        return r

    def close(self):
        try:
            self.proc.stdin.close()
            self.proc.wait(timeout=60)
        except (subprocess.TimeoutExpired, BrokenPipeError):
            self.proc.kill()
        self.tail.kill()


# ---------------------------------------------------------------- screen

def screen_elements(obs, skip, band):
    """Useful elements, clipped to the band between the system bars (top, bottom) so a tap lands on the app."""
    import dataclasses
    from android_world.env.representation_utils import BoundingBox
    from odbench.observe import is_useful
    out = []
    for e in obs.ui_elements:
        if e.bbox_pixels is not None:
            b = e.bbox_pixels
            e = dataclasses.replace(e, bbox_pixels=BoundingBox(b.x_min, b.x_max, max(b.y_min, band[0]), min(b.y_max, band[1])))
        if not is_useful(e) or (e.package_name or '') in skip:
            continue
        out.append({'text': e.text, 'desc': e.content_description, 'cls': e.class_name, 'pkg': e.package_name,
                    'clickable': e.is_clickable, 'editable': e.is_editable, 'scrollable': e.is_scrollable,
                    'checked': e.is_checked, 'selected': e.is_selected, 'el': e})
    return out


def foreground(elements):
    pkgs = [e['pkg'] for e in elements if e['pkg']]
    return Counter(pkgs).most_common(1)[0][0] if pkgs else 'unknown'


def system_bars(height):
    """(status bar bottom, navigation bar top) in pixels, from the window manager's inset frames."""
    def frame(kind):
        out = sh(f'dumpsys window | grep -m1 "type={kind} frame="', check=False)
        try:
            a, b = out.split('frame=[')[1].split(']')[:2]
            return [int(v) for v in a.split(',')] + [int(v) for v in b.lstrip('[').split(',')]
        except (IndexError, ValueError):
            return None
    s, n = frame('statusBars'), frame('navigationBars')
    return (s[3] if s else 0), (n[1] if n and n[1] > height // 2 else height)


def screenshot(png, band, scale):
    from PIL import Image
    im = Image.open(io.BytesIO(png)).convert('RGB')
    im = im.crop((0, band[0], im.width, band[1]))
    im = im.resize((round(im.width * scale), round(im.height * scale)), Image.BILINEAR)
    buf = io.BytesIO()
    im.save(buf, 'PNG')
    return buf.getvalue()


def signature(elements):
    return '|'.join(f"{e['text'] or ''}~{e['desc'] or ''}" for e in elements)


# ---------------------------------------------------------------- tasks

def setup(task):
    for cmd in task.get('restore', []):
        sh(cmd, check=False)
    for p in task['setup'].get('stop', []):
        sh(f'am force-stop {p}', check=False)
    for p in task['setup'].get('clear', []):
        sh(f'pm clear {p}', check=False)
    sh('input keyevent KEYCODE_HOME')
    time.sleep(1.0)


def verify(task, answer):
    v = task['verify']
    if 'answer_any' in v:
        flat = (answer or '').replace(',', '').lower()
        return any(s.replace(',', '').lower() in flat for s in v['answer_any'])
    if 'answer_battery' in v:
        level = int(sh("dumpsys battery | grep ' level:' | head -1").split(':')[1])
        nums = [int(''.join(c for c in w if c.isdigit())) for w in (answer or '').split() if any(c.isdigit() for c in w)]
        return any(abs(n - level) <= v['answer_battery'] for n in nums)
    if 'setting' in v:
        ns, key, want = v['setting']
        return sh(f'settings get {ns} {key}').strip() == want
    raise ValueError(v)


def act_dict(action):
    from android_world.env import json_action
    return json_action.JSONAction(**{k: v for k, v in action.items() if k in
                                     ('action_type', 'index', 'text', 'app_name', 'direction', 'goal_status')})


# ---------------------------------------------------------------- commands

def run_episode(a):
    from odbench import act
    from odbench.device import Device
    from odbench.observe import observe
    task = next(t for t in agent_spec.TASKS if t['id'] == a.task)
    _sc, method = method_of(a.manifest, a.method)
    cfg = agent_spec.SCREEN
    battery = phone_free()
    out = OUT / a.task / f'{a.method}-{time.strftime("%Y%m%d-%H%M%S")}'
    out.mkdir(parents=True)
    dev = Device(serial=SERIAL, adb_port=PORT)
    dev._prior_path = str(OUT / f'.session_{SERIAL}.json')   # odbench's snapshot, kept out of its directory
    sh('input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard', check=False)
    dev.prepare_session()
    band = system_bars(dev.screen_size[1])
    server = Server(method, f'{a.task}-{a.method}', cfg['n_predict']).start()
    steps, history, verdict, answer, prev_sig = [], [], 'max_steps', None, None
    try:
        server.wait_loaded()
        warm = warmup(server, out)
        print(f'{a.method} loaded in {server.load_s:.1f} s, warm-up request {warm / 1e3:.1f} s')
        setup(task)
        t_episode = time.time()
        for i in range(task['max_steps']):
            t0 = time.time()
            obs = observe(dev, with_screenshot=True)
            elements = screen_elements(obs, cfg['skip_packages'], band)
            sig = signature(elements)
            if prev_sig is not None and sig == prev_sig and history:
                history[-1] += '  (this did NOT change the screen)'
            prev_sig = sig
            fg = foreground(elements)
            prompt = agent_spec.build_prompt(task['goal'], fg, elements, history)
            stem = f'step{i + 1:02d}'
            (out / f'{stem}.txt').write_text(prompt)
            (out / f'{stem}.png').write_bytes(screenshot(obs.screenshot_png, band, cfg['scale']))
            (out / f'{stem}-full.png').write_bytes(obs.screenshot_png)
            subprocess.run([*ADB, 'push', str(out / f'{stem}.txt'), str(out / f'{stem}.png'), f'{WORK}/'],
                           check=True, capture_output=True)
            observe_ms = (time.time() - t0) * 1000
            r = server.request(i + 1, f'{WORK}/{stem}.txt', f'{WORK}/{stem}.png', cfg['n_predict'])
            reason, action = agent_spec.parse_reply(r['text'])
            t1 = time.time()
            act_err, terminal = None, None
            if action is not None and action['action_type'] == 'open_app' and act.resolve_package(dev, action['app_name']) == fg:
                act_err = f"{agent_spec.APP_LABELS.get(fg, fg)} is already open"   # tell the agent instead of a silent no-op
            elif action is not None:
                try:
                    terminal = act.execute(dev, act_dict(action), [e['el'] for e in elements])
                except act.ActionError as e:
                    act_err = str(e)
            act_ms = (time.time() - t1) * 1000
            steps.append({'step': i + 1, 'elements': len(elements), 'foreground': fg, 'prompt_chars': len(prompt),
                          'observe_ms': round(observe_ms), 'act_ms': round(act_ms), 'host_infer_ms': round(r['host_ms']),
                          'done': r['done'], 'ids': r['ids'], 'text': r['text'], 'reason': reason, 'action': action,
                          'act_error': act_err})
            d = r['done']
            print(f"  step {i + 1:2d} {agent_spec.summarise(action):<44.44} vis {d.get('vision_us', 0) / 1e3:6.0f}  "
                  f"pf {d['prefill_us'] / 1e3:6.0f}  dec {d['decode_us'] / 1e3:6.0f} ms  {int(d['tokens']):3d} tok  "
                  f"AL {d['al']:.2f}  obs {observe_ms:5.0f}  act {act_ms:5.0f}" + (f'  [{act_err}]' if act_err else ''))
            note = f' [FAILED: {act_err}]' if act_err else ''
            history.append(f'{agent_spec.summarise(action)}{note} - {reason}')
            if action and action['action_type'] == 'answer':
                answer = str(action['text'])   # the model may write a number
            if terminal is not None:
                verdict = f'agent_{terminal}'
                break
        episode_s = time.time() - t_episode
        ok = verify(task, answer)
    finally:
        server.close()
        for cmd in task.get('restore', []):
            sh(cmd, check=False)
        dev.restore_session()
    rec = {'task': task, 'method': a.method, 'verdict': verdict, 'success': ok, 'answer': answer, 'battery_start': battery,
           'load_s': server.load_s, 'episode_s': episode_s, 'band': band, 'steps': steps}
    (out / 'episode.json').write_text(json.dumps(rec, indent=1, ensure_ascii=False) + '\n')
    print(f'{a.task} {a.method}: {"SUCCESS" if ok else "FAIL"} ({verdict}, {len(steps)} steps, answer {answer!r}) -> {out}')


def warmup(server, out):
    """One untimed request before the episode: the first GPU encode compiles its kernels (~11 s in the 2026-10-08
    study) and the first NPU calls map their graphs, which no step of the episode should carry."""
    from PIL import Image
    buf = io.BytesIO()
    Image.new('RGB', (352, 736), (128, 128, 128)).save(buf, 'PNG')
    (out / 'warmup.png').write_bytes(buf.getvalue())
    (out / 'warmup.txt').write_text('Describe the screen.' + agent_spec.MEDIA_MARKER)
    p, i = push_step([out / 'warmup.txt', out / 'warmup.png'])
    return server.request(0, p, i, 4)['host_ms']


def recorded(ep):
    rec = json.loads((Path(ep) / 'episode.json').read_text())
    return rec, [(Path(ep) / f"step{s['step']:02d}.txt", Path(ep) / f"step{s['step']:02d}.png") for s in rec['steps']]


def push_step(files):
    subprocess.run([*ADB, 'push', *map(str, files), f'{WORK}/'], check=True, capture_output=True)
    return [f'{WORK}/{Path(f).name}' for f in files]


def run_replay(a):
    rec, inputs = recorded(a.episode)
    _sc, method = method_of(a.manifest, a.method)
    n = agent_spec.SCREEN['n_predict']
    phone_free()
    server = Server(method, f'replay-{a.method}', n).start()
    rows = []
    try:
        server.wait_loaded()
        for (txt, png), s in zip(inputs, rec['steps']):
            p, i = push_step([txt, png])
            r = server.request(s['step'], p, i, n)
            k = min(len(r['ids']), len(s['ids']))
            diff = next((j for j in range(k) if r['ids'][j] != s['ids'][j]), None if len(r['ids']) == len(s['ids']) else k)
            rows.append({'step': s['step'], 'first_diff': diff, 'tokens': len(r['ids']), 'done': r['done'], 'ids': r['ids'], 'text': r['text']})
            d, d0 = r['done'], s['done']
            print(f"  step {s['step']:2d} {'same' if diff is None else f'DIFF@{diff}':>8}  {len(r['ids']):3d} tok  "
                  f"dec {d['decode_us'] / 1e3:6.0f} vs {d0['decode_us'] / 1e3:6.0f} ms  pf {d['prefill_us'] / 1e3:6.0f} vs "
                  f"{d0['prefill_us'] / 1e3:6.0f}  AL {d['al']:.2f}")
    finally:
        server.close()
    out = Path(a.episode) / f'replay-{a.method}-{time.strftime("%Y%m%d-%H%M%S")}.json'
    out.write_text(json.dumps({'method': a.method, 'source': rec['method'], 'steps': rows}, indent=1, ensure_ascii=False) + '\n')
    same = sum(r['first_diff'] is None for r in rows)
    print(f'{same}/{len(rows)} steps token-identical to {rec["method"]} -> {out}')


def run_serve_check(a):
    """Steps A and B of a recorded episode: one-shot A, one-shot B, then one served driver on A, B, A."""
    rec, inputs = recorded(a.episode)
    _sc, method = method_of(a.manifest, a.method)
    n = agent_spec.SCREEN['n_predict']
    A, B = inputs[0], inputs[min(1, len(inputs) - 1)]
    phone_free()
    pa, pb = push_step(A), push_step(B)
    one = []
    for p in (pa, pb):
        # --no-escape: -f otherwise unescapes the prompt (\\" and \\n in element texts), which the served path never does
        s = Server(method, f'oneshot-{a.method}', n, serve=False).start(['-f', p[0], '--image', p[1], '--no-escape'])
        try:
            one.append(s.collect())
        finally:
            s.close()
    s = Server(method, f'served-{a.method}', n).start()
    try:
        s.wait_loaded()
        served = [s.request(1, *pa, n), s.request(2, *pb, n), s.request(3, *pa, n)]
    finally:
        s.close()
    ok = True
    for name, got, want in (('A', served[0], one[0]), ('B after A', served[1], one[1]), ('A after B', served[2], one[0])):
        same = got['ids'] == want['ids'] and got['done']['al'] == want['done']['al']
        ok &= same
        print(f"  {name:10s} {'same' if same else 'DIFFERENT'}: {len(got['ids'])} vs {len(want['ids'])} tok, "
              f"AL {got['done']['al']:.3f} vs {want['done']['al']:.3f}, setup {got['done'].get('setup_us', 0) / 1e3:.0f} vs "
              f"{want['done'].get('setup_us', 0) / 1e3:.0f} ms")
    out = Path(a.episode) / f'serve-check-{a.method}-{time.strftime("%Y%m%d-%H%M%S")}.json'
    out.write_text(json.dumps({'method': a.method, 'one_shot': one, 'served': served, 'pass': ok}, indent=1, ensure_ascii=False) + '\n')
    print(('PASS' if ok else 'FAIL') + f' -> {out}')


def run_report(a):
    for ep in a.episodes:
        rec = json.loads((Path(ep) / 'episode.json').read_text())
        tot = Counter()
        for s in rec['steps']:
            d = s['done']
            tot['vision'] += d.get('vision_us', 0) / 1e6
            tot['prep'] += d.get('prep_us', 0) / 1e6
            tot['prefill'] += d['prefill_us'] / 1e6
            tot['decode'] += d['decode_us'] / 1e6
            tot['tokens'] += d['tokens']
            tot['act'] += s['act_ms'] / 1e3
            tot['observe'] += s['observe_ms'] / 1e3
        model = tot['prep'] + tot['prefill'] + tot['decode']
        print(f"{rec['task']['id']:26s} {rec['method']:8s} {'ok ' if rec['success'] else 'FAIL'} {len(rec['steps']):2d} steps  "
              f"prep(vision) {tot['prep']:5.1f} ({tot['vision']:4.1f})  prefill {tot['prefill']:5.1f}  decode {tot['decode']:5.1f} s "
              f"({tot['tokens']:.0f} tok, {tot['tokens'] / max(tot['decode'], 1e-9):.1f} t/s)  model {model:5.1f} s  "
              f"act {tot['act']:5.1f}  host-observe {tot['observe']:5.1f}  episode {rec['episode_s']:5.1f} s")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--manifest', default=str(Path(__file__).resolve().parents[1] / 'demo-manifest.direct.json'))
    sub = ap.add_subparsers(dest='what', required=True)
    p = sub.add_parser('episode'); p.add_argument('task'); p.add_argument('method')
    p = sub.add_parser('replay'); p.add_argument('episode'); p.add_argument('method')
    p = sub.add_parser('serve-check'); p.add_argument('method'); p.add_argument('episode')
    p = sub.add_parser('report'); p.add_argument('episodes', nargs='+')
    a = ap.parse_args()
    {'episode': run_episode, 'replay': run_replay, 'serve-check': run_serve_check, 'report': run_report}[a.what](a)


if __name__ == '__main__':
    main()

"""The NPU-VLM agent: prompt, screen formatting and task set, in one place.

tools/agent_probe.py runs it from the host; gen_manifest.py copies it into the manifest for the app, whose
Kotlin port has to produce the same prompt bytes for the same screen. Derived from odbench
(~/workspace/qwen3_vl_androidworld/odbench/agent.py, its M3A-style vision mode: one screenshot plus the
element list, index actions). The drivers have no grammar-constrained decoding, so the action menu stays in
the prompt and the reply is one JSON object.
"""

MEDIA_MARKER = '<__media__>'   # mtmd's default marker: the screenshot goes here

# The action type leads each line: with odbench's 'Go back: {...}' lines the 4B model wrote the description as the
# action type ("go_back", "Go to the home screen"; A1, 2026-10-26).
ACTION_MENU = '''\
- status (finish): {"action_type": "status", "goal_status": "complete"}
- status (give up): {"action_type": "status", "goal_status": "infeasible"}
- answer (answer a question): {"action_type": "answer", "text": "<answer>"}
- click (tap an element): {"action_type": "click", "index": <index>}
- long_press (long press an element): {"action_type": "long_press", "index": <index>}
- input_text (type into a field): {"action_type": "input_text", "text": "<text>", "index": <index>}
- keyboard_enter (press enter): {"action_type": "keyboard_enter"}
- navigate_home (go to the home screen): {"action_type": "navigate_home"}
- navigate_back (go back): {"action_type": "navigate_back"}
- scroll (scroll the screen): {"action_type": "scroll", "direction": "<up|down|left|right>"}
- open_app (open an app): {"action_type": "open_app", "app_name": "<name>"}
- wait (wait for the screen to update): {"action_type": "wait"}'''

GUIDELINES = '''\
- Prefer `open_app` over hunting through the launcher.
- `input_text` already taps the field first, so do not tap it separately.
- Only use an index that appears in the element list above.
- If the goal is already satisfied, finish with the status action.
- If an action did not change the screen, try a different approach rather than repeating it.
- Never issue `open_app` for the app that is already in the foreground.
- If the goal asks for information, give it with the `answer` action; do not finish with `status` instead.'''

TEMPLATE = '''\
You are an agent operating an Android phone. You are given the user's goal, the current screenshot, a list of \
the UI elements currently on screen, and what you have done so far. Choose exactly one action.

Available actions:
{menu}

The app currently in the foreground is: {foreground}
(If that is already the app you need, do NOT open it again - work inside it.)

The current user goal is: {goal}

What you have done so far:
{history}

UI elements on the current screen:
{elements}

Guidelines:
{guidelines}

Reply with one JSON object and nothing else, the reason first and then the fields of one action above:
{{"reason": "<one short sentence>", "action_type": "<type>", ...}}
''' + MEDIA_MARKER

# Screens and decode budget. The image budget matches odbench's on-device runs (--image-max-tokens 256).
# Elements are clipped to the band between the system bars and the screenshot is cropped to it: an item half under
# the 3-button navigation bar was tapped at its full center and hit Recents (A1, 2026-10-26).
SCREEN = {'scale': 0.33, 'crop': 'system_bars', 'image_max_tokens': 256, 'n_predict': 96,
          'skip_packages': ['com.android.systemui', 'com.mobispec.demo']}


# The foreground line names the app as the phone shows it: told only 'com.android.settings', the agent kept
# re-opening '설정' from inside Settings (A1, 2026-10-26).
APP_LABELS = {'com.sec.android.app.launcher': '홈 화면', 'com.android.settings': '설정',
              'com.sec.android.app.popupcalculator': '계산기', 'com.sec.android.app.clockpackage': '시계',
              'com.samsung.android.app.contacts': '연락처', 'com.samsung.android.calendar': '캘린더',
              'com.sec.android.app.myfiles': '내 파일', 'com.sec.android.gallery3d': '갤러리',
              'com.samsung.android.app.notes': 'Samsung Notes', 'com.android.chrome': 'Chrome'}


def foreground_line(pkg):
    return f'{APP_LABELS[pkg]} ({pkg})' if pkg in APP_LABELS else pkg


def quote(s):
    """The only escaping the prompt uses (the Kotlin port mirrors it): backslash, double quote, newline."""
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n') + '"'


def element_line(i, e):
    """e: dict with text, desc, cls, clickable, editable, scrollable, checked, selected."""
    bits = []
    if e.get('text'):
        bits.append('text=' + quote(e['text']))
    if e.get('desc'):
        bits.append('desc=' + quote(e['desc']))
    if e.get('cls'):
        bits.append('class=' + e['cls'].split('.')[-1])
    flags = [f for f in ('clickable', 'editable', 'scrollable', 'checked', 'selected') if e.get(f)]
    if flags:
        bits.append('|'.join(flags))
    return f'UI element {i}: ' + ', '.join(bits)


def summarise(action):
    """History form of an action dict: click(index=5), input_text(index=3, text="hi")."""
    if not action:
        return 'no action (parse failure)'
    args = []
    for k in ('index', 'text', 'app_name', 'direction', 'goal_status'):
        v = action.get(k)
        if v is None or v == '':
            continue
        args.append(f'{k}={quote(v) if isinstance(v, str) else v}')
    return f"{action['action_type']}({', '.join(args)})" if args else action['action_type']


def build_prompt(goal, foreground, elements, history):
    lines = '\n'.join(f'Step {i + 1}: {h}' for i, h in enumerate(history)) or '(none yet)'
    return TEMPLATE.format(menu=ACTION_MENU, foreground=foreground_line(foreground), goal=goal, history=lines,
                           elements='\n'.join(element_line(i, e) for i, e in enumerate(elements)),
                           guidelines=GUIDELINES)


def parse_reply(text):
    """(reason, action dict or None): the first balanced {...} that parses, with its required fields."""
    import json
    depth, start = 0, None
    for i, c in enumerate(text):
        if c == '{':
            if depth == 0:
                start = i
            depth += 1
        elif c == '}' and depth:
            depth -= 1
            if depth == 0:
                try:
                    d = json.loads(text[start:i + 1])
                except ValueError:
                    continue
                if isinstance(d, dict):
                    return str(d.get('reason', '')), valid_action(d)
    return '', None


# Names the model writes for an action it means unambiguously (lowercased, spaces and hyphens as underscores).
ACTION_ALIASES = {'go_back': 'navigate_back', 'back': 'navigate_back', 'go_home': 'navigate_home', 'home': 'navigate_home',
                  'go_to_home_screen': 'navigate_home', 'go_to_the_home_screen': 'navigate_home', 'tap': 'click'}


def action_type(name):
    key = name.strip().lower().replace(' ', '_').replace('-', '_') if isinstance(name, str) else ''
    return ACTION_ALIASES.get(key, key)


REQUIRED = {'click': ('index',), 'long_press': ('index',), 'input_text': ('text', 'index'), 'scroll': ('direction',),
            'open_app': ('app_name',), 'status': ('goal_status',), 'answer': ('text',), 'keyboard_enter': (),
            'navigate_home': (), 'navigate_back': (), 'wait': ()}


def valid_action(d):
    kind = action_type(d.get('action_type'))
    if kind not in REQUIRED or any(d.get(k) in (None, '') for k in REQUIRED[kind]):
        return None
    if 'index' in REQUIRED[kind] and not isinstance(d['index'], int):
        return None
    out = {k: d[k] for k in ('action_type', 'index', 'text', 'app_name', 'direction', 'goal_status') if k in d}
    out['action_type'] = kind
    return out


# Stock S26 apps only; nothing touches user data. Goals are Korean like the phone's UI (with an English goal the agent
# scrolled past '폰 정보' looking for 'About phone') and name the menu to open, as a user instructing a phone would:
# without that the 4B agent found '폰 정보' in some runs and not in others (A1, 2026-10-26). setup: packages to force-stop / to clear (app state the
# task changes), verify: how the device state or the answer is checked, restore: commands that put the start state
# back (run before the episode too, so a task never starts already done).
TASKS = [
    {'id': 'calc-multiply', 'title': '계산기로 123 x 45', 'max_steps': 15,
     'goal': '계산기 앱에서 1, 2, 3, ×, 4, 5, = 버튼을 차례로 눌러 123 × 45를 계산하고 결과를 답해 줘.',
     'setup': {'stop': ['com.sec.android.app.popupcalculator'], 'clear': ['com.sec.android.app.popupcalculator']},
     'verify': {'answer_any': ['5535']}},
    {'id': 'settings-android-version', 'title': '설정에서 Android 버전 찾기', 'max_steps': 10,
     'goal': "설정 앱에서 '폰 정보'를 열고 '소프트웨어 정보'에서 Android 버전을 찾아 답해 줘.",
     'setup': {'stop': ['com.android.settings']}, 'verify': {'answer_any': ['16']}},
    {'id': 'settings-model-name', 'title': '설정에서 모델명 찾기', 'max_steps': 10,
     'goal': "설정 앱에서 '폰 정보'를 열고 이 폰의 모델명을 찾아 답해 줘.",
     'setup': {'stop': ['com.android.settings']}, 'verify': {'answer_any': ['Galaxy S26', 'SM-S948N']}},
    {'id': 'settings-battery', 'title': '설정에서 배터리 잔량 확인', 'max_steps': 8,
     'goal': "설정 앱에서 '배터리'를 열고 현재 배터리 잔량을 퍼센트로 답해 줘.",
     'setup': {'stop': ['com.android.settings']}, 'verify': {'answer_battery': 2}},
    {'id': 'settings-dark-mode', 'title': '다크 모드 켜기', 'max_steps': 10,
     'goal': "설정 앱에서 '디스플레이'를 열고 '다크' 모드를 켜 줘.",
     'setup': {'stop': ['com.android.settings']},
     'verify': {'setting': ['secure', 'ui_night_mode', '2']}, 'restore': ['cmd uimode night no']},
]


def fixture():
    """Cases for the app's unit test (app/src/test/resources/agent-prompts.json): the Kotlin port must match."""
    els = [{'text': '설정', 'cls': 'android.widget.TextView', 'pkg': 'com.sec.android.app.launcher', 'clickable': True},
           {'text': 'Say "hi"\nnow', 'desc': 'back\\slash', 'cls': 'Button', 'checked': True, 'selected': True},
           {'desc': 'Search', 'cls': 'android.widget.EditText', 'editable': True, 'scrollable': True},
           {'text': '', 'desc': None, 'cls': None}]
    cases = [{'goal': TASKS[0]['goal'], 'foreground': 'com.sec.android.app.launcher', 'elements': els, 'history': []},
             {'goal': "설정 앱에서 '디스플레이'를 열고 '다크' 모드를 켜 줘.", 'foreground': 'com.android.settings', 'elements': els[:2],
              'history': ['open_app(app_name="Settings") - open it', 'click(index=1)  (this did NOT change the screen)']}]
    for c in cases:
        c['prompt'] = build_prompt(c['goal'], c['foreground'], c['elements'], c['history'])
    replies = ['{"reason": "Open the calculator.", "action_type": "open_app", "app_name": "Calculator"}',
               'Sure: {"reason": "tap 5", "action_type": "click", "index": 7} and more {"x": 1}',
               '{"reason": "done", "action_type": "answer", "text": 5535}',
               '{"reason": "r", "action_type": "input_text", "text": "\\uC548\\uB155", "index": 2, "clear_text": true}',
               '{"reason": "no index", "action_type": "click"}', '{"action_type": "click", "index": "3"}',
               '{"reason": "fly", "action_type": "fly"}', '{"reason": "x" "action_type": "wait"} {"action_type": "navigate_back"}',
               'no json here', '{"reason": "nested", "extra": {"a": 1}, "action_type": "scroll", "direction": "down"}',
               '{"reason": "back", "action_type": "go_back"}', '{"reason": "home", "action_type": "Go to the home screen"}',
               '{"reason": "t", "action_type": "Tap", "index": 2}', '{"reason": "x", "action_type": 5}']
    out = []
    for r in replies:
        reason, action = parse_reply(r)
        out.append({'text': r, 'reason': reason, 'action': action, 'summary': summarise(action)})
    return {'agent': {'template': TEMPLATE, 'menu': ACTION_MENU, 'guidelines': GUIDELINES, 'screen': SCREEN,
                      'app_labels': APP_LABELS},
            'tasks': TASKS, 'cases': cases, 'replies': out}


if __name__ == '__main__':
    import json
    import sys
    from pathlib import Path
    out = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / 'app/src/test/resources/agent-prompts.json')
    out.write_text(json.dumps(fixture(), indent=1, ensure_ascii=False) + '\n')
    print(out)

#!/usr/bin/env python3
"""python3 tools/test_agent_spec.py: prompt and reply format of the agent (the Kotlin port must match)."""
import unittest

import agent_spec as s


class AgentSpecTest(unittest.TestCase):
    def test_reply(self):
        self.assertEqual(s.parse_reply('{"reason": "open it", "action_type": "open_app", "app_name": "Settings"}'),
                         ('open it', {'action_type': 'open_app', 'app_name': 'Settings'}))
        self.assertEqual(s.parse_reply('Sure. {"reason": "r", "action_type": "click", "index": 4} done')[1],
                         {'action_type': 'click', 'index': 4})
        self.assertIsNone(s.parse_reply('{"reason": "r", "action_type": "click"}')[1])          # no index
        self.assertIsNone(s.parse_reply('{"reason": "r", "action_type": "click", "index": "4"}')[1])
        self.assertIsNone(s.parse_reply('{"reason": "r", "action_type": "fly"}')[1])
        self.assertEqual(s.parse_reply('{"a": {"b": 1}, "action_type": "navigate_back"}')[1], {'action_type': 'navigate_back'})
        self.assertEqual(s.parse_reply('no json'), ('', None))

    def test_screen(self):
        e = {'text': 'Say "hi"\nnow', 'desc': None, 'cls': 'android.widget.Button', 'clickable': True, 'checked': True}
        self.assertEqual(s.element_line(3, e), 'UI element 3: text="Say \\"hi\\"\\nnow", class=Button, clickable|checked')
        self.assertEqual(s.summarise({'action_type': 'input_text', 'index': 2, 'text': '123'}), 'input_text(index=2, text="123")')
        self.assertEqual(s.summarise(None), 'no action (parse failure)')

    def test_prompt(self):
        p = s.build_prompt('Do it.', 'com.android.settings', [{'text': 'Wi-Fi', 'clickable': True}], [])
        self.assertTrue(p.endswith(s.MEDIA_MARKER))
        self.assertEqual(p.count(s.MEDIA_MARKER), 1)
        self.assertIn('UI element 0: text="Wi-Fi", clickable', p)
        self.assertIn('(none yet)', p)
        self.assertIn('{"reason": "<one short sentence>", "action_type": "<type>", ...}', p)
        self.assertIn('The app currently in the foreground is: 설정 (com.android.settings)', p)
        self.assertIn('foreground is: com.example.x\n', s.build_prompt('g', 'com.example.x', [], []))

    def test_tasks(self):
        ids = [t['id'] for t in s.TASKS]
        self.assertEqual(len(ids), len(set(ids)))
        for t in s.TASKS:
            self.assertTrue(t['goal'] and t['max_steps'] > 0 and t['verify'])


if __name__ == '__main__':
    unittest.main()

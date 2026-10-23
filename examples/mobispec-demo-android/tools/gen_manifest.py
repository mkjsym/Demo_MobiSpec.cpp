#!/usr/bin/env python3
"""Write demo-manifest.json for the Android demo app.

Every method below carries the arguments and environment of the device run its reference number
came from, so a live run in the app is the same configuration as the published cell:
  GPU MAST          examples/speculative-eagle-mast/guide.md, "Recommended settings" (S26 rows)
  NPU LLM           docs/results/raw/sweeps/w4ab13.sh, treew4ab.sh (w4a16 drafts, 13 categories)
  NPU VLM           tools/research/run_vlm_register_mobile.py command() (M2/M4/M5 conditions, the paper's device table)
Paths are relative to the models root on the phone (${MODELS}); tools/push_models.py places them.
Reference numbers come from the main checkout: GPU and NPU LLM from docs/impl-specs/android-demo-app-references.json,
VLM from the study cells. Without them the methods carry no reference.
"""
import argparse
import datetime
import glob
import json
import os
import sys
from pathlib import Path

# the study records live in the main checkout (docs/results/raw is not part of the demo worktree)
REPO = Path(os.environ.get('MOBISPEC_REPO', '/home/youngmin/dev/llm/MobiSpec.cpp'))
SPEC_BENCH = Path('/home/youngmin/dev/llm/specinfer_QNN/examples/spec-bench-data/question.jsonl')
VLM_STUDY = REPO / 'docs/results/raw/vlm-register-mobile-20261019'
VLM_VALIDATION = REPO / 'docs/results/raw/vlm-register-doc20k-20261016/data/validation.jsonl'
VLM_ARTIFACTS = Path('/data/youngmin/qnn-artifacts/vlm-register-mobile-20261019')
REFERENCES = REPO / 'docs/impl-specs/android-demo-app-references.json'   # '<scenario>/<method>' -> {tps, al, xar, note}

# Spec-Bench category order of the sweep prompt files sbprompts3/{c}_0.txt (4 = coding, 11 = math)
CATEGORIES = ['writing', 'roleplay', 'reasoning', 'math', 'coding', 'extraction', 'stem', 'humanities',
              'translation', 'summarization', 'qa', 'math_reasoning', 'rag']

# VLM presets: the study's five representative pages (inputs-rep5.json, user 2026-10-23: AL and generation
# TPS of the six paper arms near the 128-page pooled values, all three domains), so a preset is neither
# a best nor a worst case for any arm.
VLM_PRESETS = json.loads((VLM_STUDY / 'inputs-rep5.json').read_text())['ids']
# The paper's device table: AR/None from M2, Full from M4 and Ours from M5. Full and Ours prefill the
# drafter through their 256-row inject-only graphs (the fair setting).
VLM_CONDITIONS = {'AR': 'ar', 'AR-deep32': 'ar-deep', 'none-20k-T31': 'none', 'full-20k-T31-pre256': 'full',
                  'deep-dynreg8-20k-T23-fold-pre256': 'ours'}
VLM_STAGES = ('m2', 'm4', 'm5')

VICUNA_FORMAT = ("A chat between a curious user and an artificial intelligence assistant. The assistant gives "
                 "helpful, detailed, and polite answers to the user's questions. USER: {prompt} ASSISTANT:")
LLAMA3_FORMAT = "<|start_header_id|>user<|end_header_id|>\n\n{prompt}<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"

BIN = {   # packaged names (tools/stage_jnilibs.py) of the drivers built in build-android-demo
    'mast': 'libms_mast.so', 'eagle2': 'libms_eagle2.so', 'dflash_npu': 'libms_dflash_npu.so',
    'vlm_run': 'libms_vlm_run.so', 'simple_qnn': 'libms_simple_qnn.so',
}


def files(*paths, local=None):
    out = []
    for p in paths:
        entry = {'path': p}
        src = (local or {}).get(p)
        if src and Path(src).is_file():
            entry['bytes'] = Path(src).stat().st_size
        out.append(entry)
    return out


def ctx_files(rel, local_dir=None):
    """A QNN context directory: every shard plus params.json, sized from the server copy if present."""
    if local_dir and Path(local_dir).is_dir():
        names = sorted(f.name for f in Path(local_dir).iterdir() if f.is_file())
        return [{'path': f'{rel}/{n}', 'bytes': (Path(local_dir) / n).stat().st_size} for n in names]
    return [{'path': f'{rel}/params.json'}]


# P0 2026-10-23 (Vicuna, Spec-Bench coding Q1): MAST and AR agree for 44 tokens, then 'def' vs '#'
GPU_LOSSLESS_NOTE = ("The GPU has no AR run on the verify kernels: verification scores a batch of tree tokens, AR one token "
                     "at a time, and OpenCL rounds the two differently, so at a near-tie the greedy texts can part. "
                     "Both are greedy decodes of the same model.")


def gpu_scenario(sid, title, subtitle, target, draft, fmt, ctx, extra, cast, blurb):
    model = ['-m', f'${{MODELS}}/gpu/{target}', '-md', f'${{MODELS}}/gpu/{draft}', '-c', str(ctx), '-fa', 'off',
             '-ngl', '99', '-ngld', '99', '--temp', '0.0', '--top-p', '1.0', '--min-p', '0.0', '-s', '1234',
             '-n', '${N}', *extra, '-f', '${PROMPT_FILE}']
    fl = files(f'gpu/{target}', f'gpu/{draft}')
    return {
        'id': sid, 'title': title, 'subtitle': subtitle, 'backend': 'GPU', 'drafter': 'GPU', 'kind': 'LLM',
        'n_predict': 128, 'headline': 'decode', 'prompt_format': fmt, 'custom_prompt': True, 'lossless_note': GPU_LOSSLESS_NOTE,
        'prompts': spec_bench_prompts(),
        'methods': [
            {'id': 'ar', 'title': 'AR', 'role': 'ar', 'target': 'gpu', 'bin': BIN['eagle2'],
             'args': [*model, '--spec-draft-n-max', '0'], 'files': fl,
             'blurb': 'Target-only greedy decoding through the EAGLE-2 driver (same tokenizer and sampler).'},
            {'id': 'mast', 'title': 'MAST', 'role': 'sd', 'target': 'gpu', 'bin': BIN['mast'],
             'args': [*model, '--mode', 'eagle2', '--spec-draft-n-max', '100', '--spec-draft-n-min', '1',
                      '--top-k', '5', '--n-depth', '5', '--rerank-k', '7', '--cast', *cast], 'files': fl,
             'blurb': blurb},
        ],
    }


def spec_bench_prompts():
    rows = [json.loads(l) for l in SPEC_BENCH.read_text().splitlines()]
    first = {}
    for r in rows:
        first.setdefault(r['category'], r)
    return [{'id': f'sb{i:02d}-{c}', 'title': c.replace('_', ' '), 'category': c, 'text': first[c]['turns'][0]}
            for i, c in enumerate(CATEGORIES)]


def npu_llm_scenario():
    M = '${MODELS}/npu/llm'
    target = ['--qnn', '--multi-context', '--ctx-dir', f'{M}/ctx_ar32', '--tokenizer', f'{M}/Qwen3-4B-q4_0-outq4.gguf',
              '--params', f'{M}/ctx_ar32/params.json', '--log-level', '1', '-n', '${N}', '-c', '1024']
    common_files = [*ctx_files('npu/llm/ctx_ar32'), *files('npu/llm/Qwen3-4B-q4_0-outq4.gguf', 'npu/draft4b_embd_f16.bin')]

    def sd(mid, title, ctx, extra_env, blurb, markov=False):
        env = {'DFLASH_CHAT': '1', 'DFLASH_DRAFT_EMBD': '${MODELS}/npu/draft4b_embd_f16.bin',
               'DFLASH_DRAFT_CTX': f'{M}/{ctx}', 'DFLASH_DRAFT_PARAMS': f'{M}/{ctx}/params.json', **extra_env}
        fl = [*common_files, *ctx_files(f'npu/llm/{ctx}')]
        if markov:
            env['DFLASH_MARKOV_GGUF'] = f'{M}/Qwen3-4B-DSpark-b7-q4_0-emb.gguf'
            fl += files('npu/llm/Qwen3-4B-DSpark-b7-q4_0-emb.gguf')
        return {'id': mid, 'title': title, 'role': 'sd', 'target': 'a32', 'bin': BIN['dflash_npu'],
                'args': [*target, '--temp', '0.0', '-f', '${PROMPT_FILE}'], 'env': env, 'files': fl,
                'blurb': blurb}

    return {
        'id': 'npu-qwen3-4b', 'title': 'Qwen3-4B on the NPU: AR vs DFlash / PCTree / DARTree',
        'subtitle': 'Hexagon NPU target (6 shards) and Hexagon NPU w4a16 block drafter',
        'backend': 'NPU', 'drafter': 'NPU', 'kind': 'LLM', 'n_predict': 128, 'headline': 'decode',
        'prompt_format': None, 'custom_prompt': True, 'prompts': spec_bench_prompts(),
        'methods': [
            {'id': 'ar', 'title': 'AR', 'role': 'ar', 'target': 'a32', 'bin': BIN['simple_qnn'],
             'args': [*target, '-f', '${PROMPT_FILE}'], 'env': {'QNN_CHAT': '1'}, 'files': common_files,
             'blurb': 'Target-only decoding on the NPU (the AR baseline of every published xAR).'},
            {'id': 'ref-a32', 'title': 'AR (A32 graph)', 'role': 'ref', 'target': 'a32', 'bin': BIN['vlm_run'],
             'args': [*target, '--num-shards', '6', '--temp', '0', '-f', '${PROMPT_FILE}'],
             'env': {'QNN_A32_ONLY': '1'}, 'files': common_files,
             'blurb': 'Greedy reference on the verification graph, for the token-for-token lossless check.'},
            sd('dflash', 'DFlash', 'ctx_dflash4b_w4ne', {},
               'Block drafter proposes 7 tokens in one NPU call; chain verification.'),
            sd('pctree', 'PCTree', 'ctx_dspark4b_w4ne',
               {'DFLASH_PCTREE': '23', 'DFLASH_PCK': '4', 'DFLASH_PCTOPN': '256', 'DFLASH_PCEXACT': '0'},
               'Per-parent re-scored tree from one block call, budget 23.', markov=True),
            sd('dartree', 'DARTree', 'ctx_dspark4b_w4ne', {'DFLASH_DARTREE': '31', 'DFLASH_DARW': '8'},
               'Global (parent, candidate) layer selection, budget 31.', markov=True),
        ],
    }


def vlm_results(repeat='r0'):
    """Device results of the conditions the demo runs, per page, from the M2/M4/M5 raw cells."""
    out = {}
    for stage in VLM_STAGES:
        for f in glob.glob(str(VLM_STUDY / stage / 'run' / f'*-{repeat}' / 'result.json')):
            r = json.loads(Path(f).read_text())
            cond = r.get('condition')
            if cond in VLM_CONDITIONS:
                out.setdefault(r['case']['id'], {})[VLM_CONDITIONS[cond]] = r
    return out


def vlm_page_refs(results):
    refs = {}
    for cid, conds in results.items():
        for name, r in conds.items():
            d = r.get('derived', {})
            refs.setdefault(cid, {})[name] = {k: round(d[s], 3) if d.get(s) else None
                                              for k, s in (('tps', 'tps'), ('generation_tps', 'generation_tps'), ('al', 'al'))}
    return refs


def vlm_reference_means():
    """Pooled ratios (as tools/research/analyze_vlm_register_mobile.py pooled()) over both repeats of the
    pages that have every demo condition, AR-exact in all of them."""
    reps = [vlm_results('r0'), vlm_results('r1')]
    pages = [cid for cid, c in reps[0].items() if len(c) == len(VLM_CONDITIONS)
             and all(r.get('ar_equal', True) for n, r in c.items() if not n.startswith('ar'))]
    out = {}
    for n in VLM_CONDITIONS.values():
        cells = [rep[cid][n]['metrics'] for rep in reps for cid in pages if n in rep.get(cid, {})]
        tok = sum(m['tokens'] for m in cells)
        out[n] = {'tps': tok * 1e6 / sum(m['decode_us'] for m in cells), 'al': tok / sum(m['steps'] for m in cells),
                  'generation_tps': sum(m.get('generation_tokens', 0) for m in cells) * 1e6 / sum(m.get('generation_us', 0) for m in cells),
                  'cells': len(cells)}
    return len(pages), out


def npu_vlm_scenario():
    M = '${MODELS}/npu/vlm'
    rows = {json.loads(l)['id']: json.loads(l) for l in VLM_VALIDATION.read_text().splitlines()}
    inputs = {x['id']: x for x in json.loads((VLM_STUDY / 'inputs-128.json').read_text())['inputs']}
    results = vlm_results()
    refs = vlm_page_refs(results)
    n_pages, means = vlm_reference_means()
    stamp = datetime.datetime.now().strftime('%Y-%m-%d %H:%M')

    def ref(name, ar_name):
        m, a = means[name], means[ar_name]
        r = {'tps': round(m['generation_tps'], 2), 'note': f"S26, {n_pages} AR-exact document pages x {m['cells'] // max(n_pages, 1)} "
             f"repeats, pooled ({stamp}); generation tok/s (first to last token); decode {m['tps']:.2f} tok/s"}
        if name != ar_name:
            r.update(al=round(m['al'], 3), xar=round(m['generation_tps'] / a['generation_tps'], 2))
        return r
    prompts, fixture_files = [], []
    for cid in VLM_PRESETS:
        x, r = inputs[cid], rows[cid]
        ext = Path(r['image']).suffix
        prompts.append({'id': cid, 'title': f"{x['domain']} {x['image_tokens_host']} img tok", 'category': x['domain'],
                        'text': r['prompt'], 'fixture': f'npu/vlm/presets/{cid}', 'image': f'npu/vlm/presets/{cid}{ext}',
                        'refs': refs.get(cid, {})})
        for suffix in ('.json', '.tokens.i32', '.positions.i32', '.vlm_embd.f32',
                       '.vlm_deepstack_0.f32', '.vlm_deepstack_1.f32', '.vlm_deepstack_2.f32'):
            local = VLM_ARTIFACTS / 'fixtures-m1' / f'{cid}{suffix}'
            fixture_files.append({'path': f'npu/vlm/presets/{cid}{suffix}', **({'bytes': local.stat().st_size} if local.is_file() else {})})
        fixture_files.append({'path': f'npu/vlm/presets/{cid}{ext}'})

    env0 = {'DFLASH_CHAT': '1', 'EAGLE3_CHAT': '1', 'QNN_A32_ONLY': '1', 'QNN_VLM_A32_ONLY': '1',
            'QNN_DIRECT_LOGITS': '1', 'QNN_VERIFY_PROFILE': '0', 'QNN_VLM_EMBD': f'{M}/target_embeddings.f16',
            'QNN_VLM_LOAD': '${FIXTURE}', 'OMP_NUM_THREADS': '4', 'OMP_WAIT_POLICY': 'PASSIVE', 'KMP_BLOCKTIME': '0'}
    shared = [*files('npu/vlm/target_embeddings.f16', 'npu/vlm/Qwen3-VL-4B-Instruct-Q4_0.gguf',
                     local={'npu/vlm/target_embeddings.f16': '/home/youngmin/dev/llm/specinfer_QNN/experiments/vlm-dflash-20260913/target_embeddings.f16'}),
              *fixture_files]
    targets = {'a32': 'ctx_vlm_a32_c4096', 'deep32': 'ctx_vlm_deep_a32_c4096_v2'}

    def argv(target):
        t = f'{M}/{targets[target]}'
        return ['--qnn', '--multi-context', '--ctx-dir', t, '--params', f'{t}/params.json', '--tokenizer',
                f'{M}/Qwen3-VL-4B-Instruct-Q4_0.gguf', '--num-shards', '6', '--log-level', '1', '--kv-defrag',
                '-f', '${PROMPT_FILE}', '-n', '${N}', '-c', '4096', '-t', '2', '--temp', '0', '--image-max-tokens', '3072',
                '--image', '${IMAGE}']

    def tfiles(target):
        return ctx_files(f'npu/vlm/{targets[target]}', VLM_ARTIFACTS / targets[target])

    def ar(mid, title, target, ref, blurb):
        return {'id': mid, 'title': title, 'role': 'ar', 'target': target, 'bin': BIN['vlm_run'], 'affinity': '3f',
                'args': argv(target), 'env': env0, 'files': [*shared, *tfiles(target)], 'reference': ref, 'blurb': blurb}

    def sd(mid, title, target, arm, ctx, summary, ddtree, extra, ref, blurb, prefill_ctx=None):
        env = {**env0, 'DFLASH_DRAFT_CTX': f'{M}/{ctx}', 'DFLASH_DRAFT_PARAMS': f'{M}/{ctx}/params.json',
               'DFLASH_DRAFT_EMBD': '${MODELS}/npu/draft4b_embd_f16.bin', 'DFLASH_DDTREE': str(ddtree), 'DFLASH_DDK': '4',
               'DFLASH_BLOCK': '8', 'DFLASH_NINJECT': '16', 'DFLASH_NHIDDEN': '5', 'DFLASH_MASKED_ROW_OUTPUT': '0',
               'DFLASH_VLM_SUMMARY': summary, **extra}
        fl = [*shared, *tfiles(target), *ctx_files(f'npu/vlm/{ctx}', VLM_ARTIFACTS / 'export' / ctx),
              *files('npu/draft4b_embd_f16.bin')]
        if prefill_ctx:
            env.update(DFLASH_DRAFT_PREFILL_CTX=f'{M}/{prefill_ctx}', DFLASH_DRAFT_PREFILL_PARAMS=f'{M}/{prefill_ctx}/params.json')
            fl += ctx_files(f'npu/vlm/{prefill_ctx}', VLM_ARTIFACTS / 'export' / prefill_ctx)
        if 'DFLASH_VLM_REGISTERS' in extra:
            fl += files(f'npu/vlm/{arm}-registers-f32.bin',
                        local={f'npu/vlm/{arm}-registers-f32.bin': str(VLM_ARTIFACTS / 'export' / arm / 'registers-f32.bin')})
        return {'id': mid, 'title': title, 'role': 'sd', 'target': target, 'bin': BIN['dflash_npu'], 'affinity': '3f',
                'args': argv(target), 'env': env, 'files': fl, 'reference': ref, 'blurb': blurb}

    return {
        'id': 'npu-qwen3vl-4b', 'title': 'Qwen3-VL-4B document QA on the NPU: AR vs None / Full / Ours',
        'subtitle': 'NPU target (6 shards, 4,096 context) and NPU block drafter; vision features precomputed per page',
        'backend': 'NPU', 'drafter': 'NPU', 'kind': 'VLM', 'n_predict': 128, 'headline': 'generation',
        'prompt_format': None, 'custom_prompt': False, 'prompts': prompts,
        'methods': [
            ar('ar', 'AR', 'a32', ref('ar', 'ar'), 'Target-only decoding on the 32-row graph (reference of None and Full).'),
            ar('ar-deep', 'AR (deep target)', 'deep32', ref('ar-deep', 'ar-deep'),
               'Target-only decoding on the deep-register target graph (reference of Ours).'),
            sd('none', 'None', 'a32', 'none-20k-seed42', 'ctx-none-20k-seed42', 'none', 31, {},
               ref('none', 'ar'),
               'Drafter sees the text only (no image rows): a short drafter prefill, lower acceptance.'),
            sd('full', 'Full', 'a32', 'full-20k-seed42', 'ctx-full-20k-seed42-c4096', 'full', 31, {},
               ref('full', 'ar'),
               'Drafter reads every image row: highest acceptance, but it prefills the whole page first '
               '(256-row inject graph, the fair setting of M4).', prefill_ctx='ctx-full-20k-seed42-inject256'),
            sd('ours', 'Ours (dynamic registers)', 'deep32', 'deep-dynreg8-20k-seed42', 'ctx-deep-dynreg8-20k-seed42',
               'dynreg', 23,
               {'DFLASH_VLM_REGISTERS': f'{M}/deep-dynreg8-20k-seed42-registers-f32.bin', 'DFLASH_VLM_REGISTER_ROWS': '8',
                'DFLASH_VLM_REGISTER_FOLD': '1'},
               ref('ours', 'ar-deep'),
               'The target writes 8 register rows at every verify call; the drafter reads them instead of the page.',
               prefill_ctx='ctx-deep-dynreg8-20k-seed42-inject256'),
        ],
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--out', default='demo-manifest.json')
    ap.add_argument('--models-root', help='absolute models root on the phone, overriding the app default')
    a = ap.parse_args()
    scenarios = [
        gpu_scenario('gpu-vicuna-7b', 'Vicuna-7B on the GPU: AR vs MAST', 'Adreno GPU target and Adreno GPU EAGLE-1/2 draft, cost-gated tree',
                     'vicuna_q4_0_output4.gguf', 'EAGLE_q4_0_output4.gguf', VICUNA_FORMAT, 2048, [],
                     ['--cast-lambda', '0.06', '--cast-cw', '3.3', '--cast-cd', '6.7'],
                     'Keeps a draft node only if its cumulative probability pays for the width or depth it costs.'),
        gpu_scenario('gpu-llama3-8b', 'Llama-3-8B on the GPU: AR vs MAST (spare)',
                     'Adreno GPU target and EAGLE-1/2 draft with a full-vocabulary head (--no-mmap on the device)',
                     'llama-38i_q4_0_output4.gguf', 'EAGLE-llama38i_q4_0_output4.gguf',
                     LLAMA3_FORMAT, 4096, ['--no-mmap'], ['--cast-lambda', '0.06', '--cast-cw', '3.2', '--cast-cd', '15.8'],
                     'Same driver; the full-vocabulary EAGLE head makes each draft level expensive on this pair.'),
        npu_llm_scenario(),
        npu_vlm_scenario(),
    ]
    refs = json.loads(REFERENCES.read_text())['references'] if REFERENCES.is_file() else {}
    if not refs:
        print(f'no {REFERENCES}: GPU and NPU LLM methods carry no reference numbers', file=sys.stderr)
    for sc in scenarios:
        if a.models_root:
            sc['models_root'] = a.models_root
        for m in sc['methods']:
            m.setdefault('env', {})
            if 'reference' not in m and f"{sc['id']}/{m['id']}" in refs:
                m['reference'] = refs[f"{sc['id']}/{m['id']}"]
    out = {'version': 1, 'generated': datetime.datetime.now().strftime('%Y-%m-%d %H:%M'), 'scenarios': scenarios}
    Path(a.out).write_text(json.dumps(out, indent=1, ensure_ascii=False) + '\n')
    n = sum(len(s['methods']) for s in scenarios)
    print(f'{a.out}: {len(scenarios)} scenarios, {n} methods', file=sys.stderr)


if __name__ == '__main__':
    main()

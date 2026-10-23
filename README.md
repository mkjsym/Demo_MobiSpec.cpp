# Demo_MobiSpec.cpp

MobiSpec.cpp의 온디바이스 추측 디코딩(SD)을 Galaxy S26에서 보여 주는 Android 데모 앱이다.
앱은 실험에 쓰는 C++ 드라이버를 그대로 자식 프로세스로 실행한다. AR과 SD를 실시간 텍스트와 수치(tok/s, AL, x AR)로 나란히 비교한다.

| 데모 | 모델 | 비교 |
|---|---|---|
| GPU (Adreno 타깃 + Adreno 드래프트) | Vicuna-7B (예비: Llama-3-8B) | AR vs MAST |
| NPU (Hexagon 타깃 + Hexagon 드래프트) | Qwen3-4B | AR vs DFlash / PCTree / DARTree |
| NPU | Qwen3-VL-4B, 문서 프리셋 5장 | AR vs None / Full / Ours |

## 구성

경로는 MobiSpec.cpp 체크아웃과 같다. MobiSpec.cpp 트리 위에 그대로 덮어 쓰고 패치를 적용해서 쓴다.

| 경로 | 내용 |
|---|---|
| `examples/mobispec-demo-android/` | 앱(Kotlin + Compose)과 도구. 빌드, 배치, adb 실행, 기기 점검 결과는 이 디렉터리의 README에 있다. |
| `common/mobispec-events.h` | 드라이버의 이벤트 스트림(JSON Lines). `MOBISPEC_EVENTS`가 없으면 아무것도 하지 않는다. |
| `patches/driver-event-hooks.patch` | 드라이버 5개에 넣는 이벤트 훅(+66줄) |

## 패치의 기준

`patches/driver-event-hooks.patch`는 SKKU-ESLAB/MobiSpec.cpp `34cb2d8ba`에, 2026-10-23 시점의 연구용 미커밋 변경을 더한 트리를 기준으로 한다.

| 파일 | 기준 blob | HEAD `34cb2d8ba`와 |
|---|---|---|
| `examples/simple-qnn/simple-qnn.cpp` | `9acb0366b26d` | 같다 |
| `examples/speculative-eagle-2/speculative-eagle-2.cpp` | `1f57d86d2d8d` | 같다 |
| `examples/speculative-eagle-mast/speculative-eagle-mast.cpp` | `ef2931bdf772` | 같다 |
| `examples/speculative-dflash-qnn-npu/speculative-dflash-qnn-npu.cpp` | `393d286a6fb4` | 연구 변경 포함 |
| `examples/speculative-dflash-qnn/vlm-run.cpp` | `bd794bf94633` | 연구 변경 포함 |

```sh
cd MobiSpec.cpp
cp -r ../Demo_MobiSpec.cpp/common ../Demo_MobiSpec.cpp/examples .
git apply ../Demo_MobiSpec.cpp/patches/driver-event-hooks.patch
```

## 들어 있지 않은 것

- 네이티브 바이너리(`jniLibs/`): `tools/stage_jnilibs.py`가 빌드 트리에서 만든다.
- Qualcomm QNN 런타임 라이브러리: 라이선스 때문에 넣지 않는다. 기기의 QAIRT 2.44.0 사본을 pull해서 스테이징한다.
- 모델과 QNN 컨텍스트 바이너리: 앱은 폰의 `/data/local/tmp`에 있는 실험 사본을 직접 읽는다.
- `demo-manifest.json`: `tools/gen_manifest.py`가 MobiSpec.cpp의 실험 기록에서 만든다. 화면에 보이는 참고 수치도 여기서 온다.

## 기기 점검 요약 (2026-10-23, S26)

- 앱 프로세스에서 HTP(FastRPC)와 벤더 OpenCL이 모두 동작한다.
- 앱 실행과 adb 셸 실행을 12개 방법에서 비교했다. 모두 토큰 ID와 AL이 같았고, t/s 차이는 3.1% 이내였다.
- NPU의 SD 출력은 같은 검증 그래프의 AR과 토큰 단위로 같다.
- GPU에는 검증 커널로 도는 AR이 없어서, MAST 텍스트가 AR과 도중에 달라질 수 있다. 앱은 그 위치와 이유를 함께 표시한다.

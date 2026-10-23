# MobiSpec 데모 앱 (Android)

S26에서 실험에 쓰는 C++ 드라이버를 그대로 실행해 AR과 SD를 실시간 텍스트와 수치로 비교하는 앱이다.
계획과 결정 사항은 `docs/impl-specs/android-demo-app-plan-20261021.md`(메인 체크아웃)에 있다.

| 데모 | 모델 | 비교 |
|---|---|---|
| GPU (Adreno 타깃 + Adreno 드래프트) | Vicuna-7B (예비: Llama-3-8B) | AR vs MAST |
| NPU (Hexagon 타깃 + Hexagon 드래프트) | Qwen3-4B | AR vs DFlash / PCTree / DARTree |
| NPU | Qwen3-VL-4B, 문서 프리셋 5장(연구의 대표 페이지) | AR vs None / Full / Ours |

## 구조

- 앱은 드라이버를 자식 프로세스로 실행한다.
  - 드라이버는 `lib<이름>.so`로 APK에 들어가고, 설치 시 `nativeLibraryDir`에 풀린 뒤 거기서 실행된다.
    Android 10부터 앱 데이터 디렉터리에서는 exec할 수 없기 때문이다.
- 드라이버는 `MOBISPEC_EVENTS=<FIFO>`가 있을 때만 JSON Lines 이벤트를 쓴다(`common/mobispec-events.h`).
  - 이벤트 종류는 `start`, `loaded`, `decode_start`, `step`, `done`이다.
  - `step` 이벤트는 토큰 ID, 토큰별 바이트 길이, base64 텍스트를 담는다.
    `MOBISPEC_EVENTS_TREE=1`이면 MAST가 검증한 트리와 수락 경로도 담는다.
  - env가 없으면 드라이버 동작은 실험 빌드와 같다.
- 실행할 기법과 그 인자·env·필요 파일은 `demo-manifest.json`에 있다. 이 파일은 `tools/gen_manifest.py`가 실험 스크립트의 설정으로 만든다.
- 비교 방식:
  - AR과 SD는 순차로 실행한다.
  - 레이스 화면은 두 결과를 기록된 타임스탬프 그대로 함께 재생한다.
    같은 프롬프트의 AR 기록이 있으면 SD를 실시간으로 돌리면서 옆에 AR 기록을 재생할 수도 있다.
  - 무손실 배지는 같은 검증 그래프에서 돌린 AR과 토큰 ID를 비교한다.

## 빌드 (서버)

```sh
# 1. 드라이버 5개 (데모 worktree 루트에서; build-android-demo는 이미 구성돼 있다)
cmake --build build-android-demo -j 16 --target llama-speculative-dflash-qnn-npu llama-qnn-vlm-run \
    llama-simple-qnn llama-speculative-eagle-mast llama-speculative-eagle-2

# 2. jniLibs 스테이징 (QNN 런타임은 폰의 2.44를 pull해서 쓰는 것이 맞다. 아래 P0 참고)
cd examples/mobispec-demo-android
python3 tools/stage_jnilibs.py --qnn-libs /data/youngmin/demo-models/qnn-2.44-s26   # 폰 qnn6에서 pull한 2.44.0

# 3. 매니페스트와 APK
python3 tools/gen_manifest.py --out demo-manifest.json
export JAVA_HOME=/home/youngmin/anaconda3/envs/android_world/lib/jvm ANDROID_HOME=/home/youngmin/Android/Sdk \
       GRADLE_USER_HOME=/data/youngmin/gradle-home
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## 폰에 올리기

```sh
python3 tools/push_models.py demo-manifest.json --direct   # 복사 없이 /data/local/tmp의 실험 사본을 가리키는 매니페스트를 만들어 푸시
adb -H 127.0.0.1 -P 10037 -s R5KL20FMLCN install -r app/build/outputs/apk/debug/app-debug.apk
```

- 앱 프로세스는 `/data/local/tmp`의 모델을 직접 읽을 수 있다(P0 확인). 그래서 기본은 `--direct`다.
  - 매니페스트의 `models_root`는 `/`이고, 모든 경로는 실험 사본의 위치다.
  - 같은 파일이 여러 곳에 있으면(`find:`) 가장 얕은 경로를 쓴다.
- `--apply`(앱 폴더 `/sdcard/Android/data/com.mobispec.demo/files/models/`로 복사)는 실험 사본이 없어질 때를 위한 대안이다. 약 17 GiB가 든다.
- 화면은 폰이 연결된 Windows PC에서 scrcpy로 미러링한다.

## adb로 실행하기 (점검용)

```sh
adb shell am start -n com.mobispec.demo/.MainActivity --es scenario npu-qwen3-4b --es prompt sb04-coding --es methods dflash [--ez race true]
adb logcat -s MobiSpecDemo     # 실행마다 RESULT 한 줄(JSON), 실패하면 그 앞에 TAIL 줄
python3 tools/p0_check.py app|adb|compare <scenario> <prompt> <method>   # 앱 실행과 adb 셸 실행 비교
```

- 앱은 실행할 때 화면을 켜고 잠금 화면 위에 뜬다.
  화면이 꺼져 있거나 잠금 화면 뒤에 있으면 앱이 cached 상태가 되고, 플랫폼 freezer가 드라이버까지 함께 멈춘다(`do_freezer_trap`).

## P0 결과 (2026-10-23, S26)

NPU 점검은 부팅 82에서, GPU 점검은 정상 재부팅(부팅 83) 후 5분을 기다린 뒤에 했다.

| 점검 | 결과 |
|---|---|
| 앱 프로세스에서 HTP(FastRPC) | 된다. 자식 프로세스가 `/dev/fastrpc-cdsp`를 열고 HTP 메모리를 할당한다. |
| 앱 프로세스에서 벤더 OpenCL | 된다(Adreno 840, GPU 시나리오 실행). |
| `/data/local/tmp` 직접 읽기 | 된다. 모델을 복사할 필요가 없다. |
| 앱과 adb 셸 실행 비교 (NPU LLM 5개, VLM 5개, GPU Vicuna 2개) | 12개 모두 토큰 동일, AL 동일, t/s 차이 3.1% 이내 |
| 무손실 | 모든 SD 출력이 같은 그래프 AR과 동일. NPU LLM의 A1 AR(속도 기준)은 A32 그래프와 12번째 토큰부터 다르다(그래프 차이). |
| RSS (드라이버) | NPU LLM 0.94~0.98 GiB, VLM 1.3~1.6 GiB(이미지 토큰 425개), 2.9~3.2 GiB(3,038개, Full이 최대), GPU Vicuna 3.5~3.7 GiB(mmap), Llama-3-8B 1.1~1.2 GiB(`--no-mmap`, 가중치는 GPU 버퍼) |
| `libQnnHtpPrepare.so` 제거 | NPU AR과 VLM Ours가 없이도 토큰 동일하게 돈다. APK에서 뺐다(네이티브 124 → 43 MiB). |
| QNN 런타임 | 폰의 2.44.0 라이브러리를 pull해서(해시 확인) 넣었다. |

- GPU에서 MAST 출력은 AR과 44번째 토큰부터 다르다(Vicuna, 코드 블록의 빈 줄 뒤에서 AR `def`, MAST `#`).
  - GPU에는 검증 커널로 도는 AR이 없다. 검증은 트리 토큰 배치를, AR은 한 토큰씩을 계산하고, OpenCL이 둘을 다르게 반올림한다. 그래서 argmax가 거의 동점인 곳에서 갈라진 것으로 본다(추정).
  - 레이스 화면은 차이를 그대로 보여 주고, 매니페스트의 `lossless_note`로 이유를 붙인다.
- 실기 화면은 홈, VLM 시나리오(페이지 이미지 포함), GPU 레이스(실행 중과 종료 후)를 스크린샷으로 확인했다.
  - 텍스트 창이 작아서 레이스 화면 아래쪽(그래프, 요약, 노트)을 줄였다. 실행 중에도 x AR을 보여 준다.
- 설치 후 처음 실행할 때 16KB 페이지 정렬 경고 대화상자가 뜬다(디버그 앱, NDK r26d 링크).
  이 폰의 페이지 크기는 4 KB라서 동작에는 영향이 없다.
- 성능 수치(GPU MAST의 x AR, 연구 기록과의 비교)는 비공개 계획 문서(메인 체크아웃 `docs/impl-specs/android-demo-app-plan-20261021.md`)에 둔다.
- 발견해서 고친 것:
  - `libggml-cpu.so`가 `libomp.so`를 동적으로 링크한다. 스테이징에 NDK의 `libomp.so`를 추가했다(폰 qnn6의 것과 같은 파일). 이제 스테이징이 모든 NEEDED를 검사한다.
  - 기록 JSON의 NaN(thermal headroom): org.json이 `toString()`에서 null을 돌려줘 저장 단계에서 앱이 죽었다. 유한값만 저장한다.
  - 이 폰은 thermal headroom을 주지 않는다. 쿨다운은 thermal status(moderate 이상)와 배터리 온도(기본 38 °C)도 함께 본다.
- 하지 않은 것:
  - **같은 부팅에서 GPU와 NPU 드래프트 교대 시험은 하지 않았다.**
    `examples/speculative-dflash-qnn-npu/README.md`에 이미 재부팅 재현 기록이 있다(블록을 크게 나눠도 재부팅).
    부팅 잠금을 유지하고, 백엔드를 바꿀 때는 재부팅한다.

## 앱 화면

- **홈**: 기기 상태(배터리, 온도, thermal headroom, 여유 메모리, 부팅 잠금), 시나리오 목록, 실행 설정.
- **시나리오**: 프롬프트(Spec-Bench 13개 카테고리 / VLM 프리셋 / 직접 입력)와 기법을 고른다.
  - 실행 버튼은 네 가지다: 실시간 레이스, AR 기록 대비 레이스, 전체 기법 보드, AR 단독.
  - 기법마다 참고값(문서 수치)을 보여주고, VLM 프리셋은 그 페이지의 실측값도 보여준다.
- **레이스**:
  - AR과 SD 텍스트 창을 나란히 둔다. 드래프트가 맞힌 토큰은 음영으로 표시해, 한 스텝에 나온 토큰 묶음이 보인다.
  - 토큰-시간 그래프, MAST 트리 뷰, tok/s, xAR, AL, e2e 배수, 무손실 배지를 보여준다.
- **보드**: 기법을 쿨다운을 넣어 차례로 실행하고, 막대 그래프와 표로 보여준다.
- **로그**: 실제 커맨드라인, 드라이버가 보고한 수치, 로그 끝부분.

지표 정의:

- LLM은 드라이버가 보고하는 decode tok/s를 쓴다(논문 표와 같다).
- VLM은 generation tok/s(첫 토큰부터 마지막 토큰까지)를 대표값으로 쓴다.
  Full의 드래프터 prefill이 이 구간에 들어가기 때문이다. 연구 분석과 같은 정의다.

## 보호 장치

- 부팅 세션 잠금: 한 부팅에서 처음 쓴 드래프터 백엔드(GPU/NPU)만 실행을 허용한다.
  NPU 드래프트와 GPU 드래프트를 번갈아 쓰다가 폰이 재부팅된 기록이 있다.
- 배터리 10% 미만이면 실행을 막는다.
- thermal headroom이 설정값을 넘으면 실행 전에 쿨다운하고, 보드는 실행 사이에 최소 간격을 둔다.
- 실험 하네스가 폰을 쓰는 동안에는 앱을 실행하지 않는다(운영 규칙).

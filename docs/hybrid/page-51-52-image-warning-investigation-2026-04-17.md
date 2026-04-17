# 단지설계실무편람(상권) p51/p52 이미지 경고 조사 및 대응 (2026-04-17)

## 1) 증상

특정 페이지(51, 52) 처리 중 아래 경고가 반복 발생했다.

```
org.verapdf.wcag.algorithms.semanticalgorithms.consumers.ContrastRatioConsumer getIntegerBBoxValueForProcessing
경고: The resulting target buffered image width is <= 0. Fall back to 1
```

대상 파일:

- `samples/pdf/단지설계실무편람(상권).pdf`


## 2) 오류 위치 확인

로그 클래스와 코드 경로를 추적한 결과, 경고는 이미지 서브렌더링 시점에 발생했다.

- 경고 발생 클래스:
  - `org.verapdf.wcag.algorithms.semanticalgorithms.consumers.ContrastRatioConsumer`
- 호출 경로:
  - `ImagesUtils.createImageFile(...)`
  - `contrastRatioConsumer.getPageSubImage(imageBox)` 호출 중 내부에서 경고 발생

즉, 문서 추출(파싱) 단계보다 출력(이미지 생성) 단계에서 문제가 관측되었다.


## 3) 원인 파악을 위한 교차 검증 (pdfplumber)

원인 확인을 위해 동일 페이지를 `pdfplumber`로 읽어 구조를 확인했다.

설치:

```powershell
.\.venv\Scripts\python.exe -m pip install pdfplumber
```

51페이지 관측값 요약:

- `text_len = 488` (텍스트 객체 존재)
- `images = 71,398`
- `tiny_images(<=1 px^2) = 69,094`
- `max_image_area/page_area = 0.012` (최대 이미지가 페이지의 약 1.2%)
- `big_images(>=30% page) = 0`

해석:

- 이 페이지는 "페이지 전체를 덮는 단일 이미지" 형태가 아님
- 벡터 선/도형 + 텍스트 + 초소형 이미지 조각(XObject) 다수로 구성된 벡터 기반 PDF에 가까움
- 따라서 "도면 전체를 큰 이미지 1개로 인식"하지 못하는 것이 정상적인 결과에 가깝다.


## 4) 적용한 대처

### 4-1. 경고 페이지 감지 및 수집

- `ImagesUtils`에서 `ContrastRatioConsumer` 경고(`width/height <= 0`)를 핸들러로 감지
- 실패 페이지(`failedPages`)를 수집

### 4-2. 즉시 중단(early stop)

- 첫 실패 페이지 감지 시 Java 이미지 렌더 루프를 즉시 중단
- 로그:
  - `Stopping Java image rendering early at page ...`

### 4-3. 페이지 단위 fallback

- `DocumentProcessor`에서 실패 페이지만 추려 hybrid backend로 재처리
- 재처리 설정:
  - backend: `docling-fast`
  - mode: `full`
  - fallback to Java: `true`


## 5) 검증 결과

non-hybrid 실행(`--hybrid off`)에서 다음 흐름이 확인되었다.

1. `Image rendering issue on page 51 ...`
2. `Stopping Java image rendering early ...`
3. `Reprocessed with hybrid backend`

검증 로그 파일:

- `samples/out/patch-check/run.log`

검색 예시:

```powershell
Select-String -Path ".\samples\out\patch-check\run.log" -Pattern `
  "Image rendering issue","Stopping Java image rendering early","Reprocessed with hybrid backend"
```


## 6) 추가 확인: full OCR 실행

51/52 페이지를 full OCR로 재실행했다.

- 실행 조건:
  - client: `--hybrid docling-fast --hybrid-mode full`
  - server: `opendataloader-pdf-hybrid --force-ocr --device cuda`

결과:

- 페이지 51: `image 2`
- 페이지 52: `image 1`
- 다수는 여전히 초소형 조각 bbox

즉 full OCR에서도 도면 전체가 단일 대형 이미지로 변환되지는 않았다.


## 7) 결론 및 후속 대처

결론:

- 본 이슈의 핵심은 "이미지 인식 실패"라기보다, 원본 PDF가 단일 래스터 이미지가 아닌 벡터/조각 이미지 구조라는 점이다.
- 경고 대응 로직(감지 -> 즉시 중단 -> 페이지 단위 hybrid fallback)은 동작 확인됨.

후속 대처(권장):

1. 도면 페이지를 "통이미지"로 다루어야 한다면, 해당 페이지를 먼저 래스터화(PNG/PDF)한 뒤 OCR/비전 파이프라인에 넣는다.
2. fallback 후 재렌더 단계에서 동일 경고가 재노출되는 로그 중복은 별도 패치로 추가 정리 가능하다.

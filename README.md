# ENERGYBLUE IXP101H1 Android HMI V4.73-alpha

스마트팜 5동 HMI Android 이식 프로젝트. 기존 V4.70 Raspberry Pi 원본은 보존하고 Android 통신/업데이트 기능을 단계별로 개발 중.

## 추가한 기능 (V4.73)
- HMI에서 Wi-Fi 또는 LAN의 IPv4 주소 자동 표시. IP 고정 설정 불필요
- 앱 실행 중 UDP 38541 포트로 `ENERGYBLUE_HMI_DISCOVER_V1` 요청에 응답 (읽기 전용; 원격 설치 API 제공하지 않음)
- Windows/Linux 노트북에서 `python tools/find_hmi.py` 실행 시 같은 LAN의 HMI 자동 검색
- HMI에서 [업데이트 검색] → [APK 다운로드] → [설치 승인] 버튼 제공
- GitHub Releases 최신 릴리스 메타데이터 조회
- `ENERGYBLUE_*.apk` 및 동일 이름에 `.sha256`을 붙인 체크섬 자산 필수
- SHA-256 일치 검증 후에만 Android 설치 승인 창 호출
- 기존 PLC COM5(ID 1,2) / COM6(센서 예약); 실제 제어 명령은 잠금 유지

## 노트북에서 검색
```sh
python tools/find_hmi.py
```
IP가 바뀌어도 검색할 수 있지만 **HMI 앱이 실행 중이고 양쪽이 같은 LAN**에 있어야 합니다.
AP isolation, Windows 방화벽, 브로드캐스트 제한 환경에서는 자동 검색이 실패할 수 있습니다.
인터넷의 원격 HMI 탐색용은 아닙니다.

## 배포 APK의 필수 조건
1. **동일한 서명 인증서**로 APK를 서명해야 기존 앱을 덮어써 설치할 수 있음. GitHub Actions 매 실행마다 생성되는 임시 debug 서명은 지속적인 업데이트에 적합하지 않음.
2. GitHub Releases에 `ENERGYBLUE_IXP101H1_V4_73.apk` 및 `ENERGYBLUE_IXP101H1_V4_73.apk.sha256` 자산 등록.
3. sha256 파일 내용에는 APK의 64자리 SHA-256 16진수 해시를 기재.
4. 설치 시 Android 사용자의 승인 또는 장비 제조사에서 허용한 전용 관리 권한 필요.
5. 업데이트 전 PLC와 제어 출력이 안전 상태인지 확인할 것. 롤백 및 설정 백업은 현재 미구현.
6. 이 코드의 최신 릴리스 조회 방식은 **공개 저장소**를 전제함.
7. HMI 모델/CPU/Android 버전별 호환성과 장치 포트 접근 권한은 현물 검증 필요.

## 빌드 및 설치
GitHub Actions → Build IXP101H1 Android APK → Artifacts에서 APK ZIP을 받아 HMI에서 설치.
현재는 **네트워크 진단/업데이트 UI 알파 버전**으로, 기존 V4.70 제어 화면·PLC 통신·센서 기능의 전체 이식은 아직 완료되지 않았음.

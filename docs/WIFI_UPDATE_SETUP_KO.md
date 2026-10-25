# IXHUB IXP101H1 Wi-Fi 업데이트 설치 절차 (V4.73 이후)

## 확인된 상태
- HMI Android 7.1.2, 기존 V4.73-alpha는 GitHub Actions **debug 서명 APK**.
- COM5는 PLC ID 1/2 통신용, COM6는 센서용 예약. 이 프로젝트는 아직 **진단용**이며 PLC 제어 비활성.
- Wi-Fi에서 HMI의 IP는 변경될 수 있다. HMI 앱 자체에서 Wi-Fi를 통해 GitHub Releases를 조회하므로 IP를 미리 알 필요가 없다.
- 노트북에서 검색하려면 `python tools/find_hmi.py` (같은 LAN, UDP 38541 브로드캐스트 허용 필수).

## 현재 HMI에서 가능한 1차 점검
1. ENERGYBLUE 앱 실행.
2. `현재 HMI IP`가 Wi-Fi 주소와 일치하는지 확인.
3. `1. 업데이트 검색`을 터치. GitHub에 Releases가 없으면 **404 오류**가 나오는 것이 현재는 예상 동작이다.
4. Android 7.1.2에서 버튼을 눌렀을 때 앱이 종료되거나 멈추지 않는지 확인.
5. 이 단계에서 PLC 명령은 보내지 않는다.

## 정식 APK를 자동으로 배포하려면 반드시 준비
- 기존 V4.73 **debug 서명과 다른 신규 서명키로는 덮어쓰기 불가능**. 이후부터는 1개의 고정된 서명키를 사용한다.
- 일회성으로 V4.73 debug APK를 삭제하고 새 고정키로 서명한 APK를 설치해야 할 수 있다. 제거 전 앱 데이터/설정 백업.
- 고정 서명키와 비밀번호는 외부에 공개하거나 git에 커밋하지 않는다.
- GitHub `Settings → Secrets and variables → Actions → New repository secret`에:
  - HMI_KEYSTORE_BASE64 (jks 파일을 base64 인코딩한 문자열)
  - HMI_STORE_PASSWORD
  - HMI_KEY_ALIAS
  - HMI_KEY_PASSWORD
- 서명키 생성 예시 (Windows, JDK 설치 후 PowerShell):
```powershell
keytool -genkeypair -v -keystore ENERGYBLUE_HMI_SIGNING.jks -alias energyblue_hmi -keyalg RSA -keysize 3072 -validity 10000 -storetype JKS
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\ENERGYBLUE_HMI_SIGNING.jks))) | Set-Clipboard
```
  첫 명령에서 지정한 비밀번호와 alias를 안전하게 저장한다. 두 번째 명령은 Base64를 클립보드로 복사한다. **비밀번호와 서명키를 채팅/공개 저장소로 전송하지 말 것.**
- GitHub Actions → **Publish HMI Wi-Fi Update Release** → Run workflow → tag `v4.74` 등 입력.
- 성공 시 Releases에 `ENERGYBLUE_IXP101H1_v4.74.apk`와 `.apk.sha256` 파일이 게시된다.
- HMI 앱 → `업데이트 검색` → `APK 다운로드` → `설치 승인`.
- 실제 설치 성공 여부는 HMI의 Android 보안정책과 같은 서명키 유지 여부에 달려 있다.

## 필수 추가 검증
- 릴리스 APK의 versionCode가 기존 설치보다 큰지 확인. versionCode가 같거나 작으면 업데이트로 인정되지 않을 수 있다.
- 같은 서명키 인증서인지 확인.
- GitHub Releases 액세스 및 다운로드 기능은 실제 HMI에서 미검증.
- SHA-256은 전송 오류 검증이지 악의적인 릴리스 교체를 막는 신뢰 보증이 아님. GitHub 계정 보안을 유지.
- 업데이트 설치 중 제어 중단과 복구 절차를 검증하기 전에는 PLC 실제 부하를 연결한 상태에서 시험하지 말 것.
- 한 번에 업데이트되는 방식이 아닌 Android 설치 확인 버튼 방식이며, 무인 자동 설치는 지원하지 않음.

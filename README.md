# ENERGYBLUE IXP101H1 Android HMI V4.72-alpha

기존 Raspberry Pi HMI V4.70을 IXHUB IXP101H1 Android로 이식하는 개발 프로젝트입니다.

- PLC: COM5 단일 RS485 버스, Slave ID 1/2
- COM6: 센서 전용으로 예약
- 실제 PLC 출력 제어는 통신 검증 전까지 사용 금지
- 이 저장소에는 현재 Android 기본 빌드 설정과 MainActivity, GitHub Actions만 업로드되어 있습니다.
- **아직 원본 ZIP의 웹 화면·설정 JSON·CSV·Python 참고 소스가 업로드되지 않았습니다.**
- 빌드된 APK가 있더라도 완전한 화면 및 PLC 제어 기능을 보장하지 않습니다.

추가 업로드 필요한 필수 파일:
- app/src/main/assets/index.html
- app/src/main/assets/house_config.json
- app/src/main/assets/DO120_Assignment.csv
- app/src/main/assets/channels.json
- app/src/main/assets/settings.json
- original_v4_70/ 참조 소스 및 README_KO.md

원본 압축파일: ENERGYBLUE_IXP101H1_ANDROID_V4_72_ALPHA_SOURCE.zip (ChatGPT 대화 파일).

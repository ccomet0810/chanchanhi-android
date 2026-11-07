# 찬찬히 3 브리지

Android의 V3 클라이언트와 추론 서비스 사이에서 `task_plan` 요청을 처리합니다. 요청과 응답은 `model-contract.mjs` 및 `task-plan.schema.json`을 기준으로 검증합니다. Android 클라이언트가 사용하는 스키마는 `../app3/src/main/assets/task-plan.schema.json`에 있습니다.

## 테스트

Node.js 24 이상에서 이 디렉터리의 명령을 실행합니다.

```sh
npm ci
npm test
```

테스트는 합성 화면과 모의 응답을 사용합니다. Linux 파일 권한 테스트 2건은 Windows에서 생략됩니다.

실제 앱 연결에는 팀이 운영하는 HTTPS 게이트웨이와 해당 게이트웨이의 연결 키가 필요합니다. 계정 세션, 인증 파일 및 배포 설정은 운영 환경에서 관리합니다.

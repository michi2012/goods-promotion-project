현재 디렉토리에 새로운 Spring Boot 프로젝트를 생성하고, 능동적으로 장애를 분석하고 보고하는 "AIOps 에이전트"를 구현해 줘. 저 /mcp 프로젝트 만들어뒀으니 거기다가 해.

[프로젝트 목표]
단순히 API 요청에 응답하는 수동적인 서버가 아니라, monitoring/prometeus 알림파일에 필요한 알림들 다 추가후, 외부 모니터링 시스템(Prometheus)의 알람 웹훅을 수신하면 스스로 Spring AI(Function Calling)를 활용해 로그와 트레이스를 뒤져 원인을 분석하고, 원인/해결법 결과를 슬랙으로 발송하는 능동형 에이전트를 구축한다.

[기술 스택]
- Java 21
- Spring Boot 3.x (Web)
- Spring AI
- RestClient (외부 API 호출용) + 가상스레드

[핵심 아키텍처 및 요구사항]
1. 웹훅 수신부 (Controller)
- 프로메테우스 Alertmanager가 보내는 JSON 형식의 알람 페이로드를 POST `/webhook/prometheus` 엔드포인트로 수신해야 해.
- 알람을 받으면 즉시 200 OK를 반환하고, 실제 분석 작업은 메인 스레드를 막지 않도록 비동기(Virtual Thread 권장)로 AI 에이전트 서비스에 넘겨야 해.

2. AI 에이전트 두뇌부 (Service + Spring AI)
- 우리가 구현한 그라파나, 프로메테우스, 로키, 템포 등 필요한 도구들은 너가 구현해. 
- ChatClient를 사용하여 프롬프트를 구성해.
- System 프롬프트: "당신은 시니어 SRE입니다. 알람 데이터를 보고 등록된 도구들을 활용해 에러 원인을 분석하고 해결 방안을 마크다운 한국어로 작성하세요."
- User 프롬프트: 전달받은 알람 JSON 페이로드를 주입해.
- Function Calling: 이전에 만들었던 3가지 조회 도구(Loki 로그 조회, Tempo 트레이스 조회, Prometheus 메트릭 조회)를 사용할 수 있도록 설정해.

3. 결과 발송부 (Service)
- AI가 최종적으로 생성한 마크다운 분석 보고서(String)를 받아서, Slack Webhook URL로 POST 요청을 보내는 서비스를 구현해.
- Slack Webhook URL은 `application.yml`에서 주입받도록 해.

[설정 (application.yml)]
- 톰캣은 띄우되(웹훅을 받아야 하므로), 콘솔에 불필요한 스프링 배너나 디버그 로그가 찍히지 않도록 깔끔하게 설정해.
- Anthropic API Key와 Slack Webhook URL은 환경 변수로 받을 수 있게 세팅해 줘.

[작업 지시]
위의 요구사항을 바탕으로 필요한 패키지 구조와 클래스들을 설계하고, 전체 코드를 작성해 줘. 클래스명은 네가 아키텍처에 맞게 가장 직관적이고 적절하게 지어주면 돼.
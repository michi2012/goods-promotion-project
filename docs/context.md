# 맥락 노트: @EnableJpaAuditing 분리

## 왜 이 방식을 선택했는가
`@WebMvcTest`는 웹 레이어 빈만 로드하고 JPA 인프라를 로드하지 않는다.
그런데 `@SpringBootApplication`이 붙은 클래스에 `@EnableJpaAuditing`이 함께 있으면,
`@WebMvcTest`가 해당 클래스를 스캔할 때 `jpaAuditingHandler` 빈 생성을 시도하고,
JPA 메타모델이 없어 `IllegalArgumentException`이 발생한다.

`@EnableJpaAuditing`을 별도 `@Configuration` 클래스에 분리하면,
`@WebMvcTest`는 `@SpringBootApplication` 클래스만 로드하고 JPA 설정 클래스는 건드리지 않아 충돌이 해소된다.

## 검토했으나 채택하지 않은 대안
### 대안 A: @WebMvcTest에 excludeAutoConfiguration 추가
- 무엇: 테스트 어노테이션에 JPA Auditing 관련 자동설정 제외 지정
- 왜 안 썼나: 테스트 파일마다 반복 필요, 근본 원인을 해결하지 않음

### 대안 B: @MockBean(JpaMetamodelMappingContext.class) 추가
- 무엇: 테스트에 JPA 메타모델 컨텍스트를 Mock으로 등록
- 왜 안 썼나: 임시방편이고, 테스트 파일마다 보일러플레이트가 생김

## 관련 파일/위치
- `serverC/src/main/java/weverse/serverC/ServerCApplication.java` — @EnableJpaAuditing 제거 대상
- `serverC/src/main/java/weverse/serverC/config/JpaAuditingConfig.java` — 신규 생성, @EnableJpaAuditing 이동 위치
- `serverC/src/test/java/weverse/serverC/controller/OrderControllerTest.java` — 수정 없음, 이 픽스로 통과되어야 함

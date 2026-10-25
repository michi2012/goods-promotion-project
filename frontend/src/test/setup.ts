import '@testing-library/jest-dom/vitest'
import { configure } from '@testing-library/dom'
import { afterEach, afterAll } from 'vitest'
import { server } from '@/mocks/server'

// 로컬 개발 중엔 백엔드 인프라(docker compose)가 같이 떠 있어 시스템 부하가 커질 수 있다 —
// findBy*/waitFor 기본 타임아웃(1000ms)보다 여유 있게 잡는다.
configure({ asyncUtilTimeout: 5000 })

// beforeAll이 아니라 설정 파일 최상위에서 즉시 호출한다. setupFiles는 테스트 파일(및 그 안에서
// import하는 openapi-fetch 클라이언트)보다 먼저 로드되므로, openapi-fetch가 `globalThis.fetch`를
// 기본값으로 캡처하기 전에 MSW가 먼저 패치해둬야 한다 — beforeAll 콜백은 모든 import가 끝난
// 뒤에야 실행되어 이미 캡처된(패치 전) fetch를 못 바꾼다.
server.listen({ onUnhandledRequest: 'error' })
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

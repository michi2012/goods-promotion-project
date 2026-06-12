import { defineConfig } from 'orval'

export default defineConfig({
  serverB: {
    input: {
      target: 'http://localhost:8081/v3/api-docs',
    },
    output: {
      mode: 'tags-split',
      target: './src/api/generated',
      schemas: './src/api/generated/model',
      client: 'react-query',
      httpClient: 'axios',
      override: {
        mutator: {
          path: './src/api/axios-instance.ts',
          name: 'axiosInstance',
        },
      },
    },
  },
})

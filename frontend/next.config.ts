import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  /* config options here */
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  // serverA는 CORS 설정이 없다. 브라우저(Client Component)가 직접 부르면 막히므로
  // same-origin 프록시로 우회한다 (Server Component는 Node에서 서버-서버로 직접 호출하므로 영향 없음).
  async rewrites() {
    return [
      {
        source: "/api-a/:path*",
        destination: "http://localhost:8080/:path*",
      },
    ];
  },
};

export default nextConfig;

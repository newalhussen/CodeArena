import type { NextConfig } from "next";

const api = process.env.API_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  poweredByHeader: false,
  // The browser talks only to this origin; /api/* is forwarded to the Spring Boot service (keeps the session cookie first-party).
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${api}/api/:path*` }];
  },
};
export default nextConfig;

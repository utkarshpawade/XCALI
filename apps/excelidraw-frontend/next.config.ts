import path from "path";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // This app lives in a monorepo; point file tracing at the workspace root so
  // Next resolves files from packages/* correctly.
  outputFileTracingRoot: path.join(__dirname, "../../"),
  // A self-contained server bundle, so the Docker image can ship without
  // node_modules. Opt-in rather than always on: tracing recreates pnpm's
  // symlinked node_modules, which fails with EPERM on Windows unless Developer
  // Mode is enabled. The Dockerfile sets this; local builds and Vercel do not.
  ...(process.env.BUILD_STANDALONE === "1" ? { output: "standalone" as const } : {}),
};

export default nextConfig;

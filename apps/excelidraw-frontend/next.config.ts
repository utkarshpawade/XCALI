import path from "path";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // This app lives in a monorepo; point file tracing at the workspace root so
  // Next resolves files from packages/* correctly.
  outputFileTracingRoot: path.join(__dirname, "../../"),
};

export default nextConfig;

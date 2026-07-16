import path from "path";
import { fileURLToPath } from "url";

const currentDir = path.dirname(fileURLToPath(import.meta.url));

/** @type {import('next').NextConfig} */
const nextConfig = {
  // This app lives in a monorepo; point file tracing at the workspace root so
  // Next resolves files from packages/* correctly.
  outputFileTracingRoot: path.join(currentDir, "../../"),
};

export default nextConfig;

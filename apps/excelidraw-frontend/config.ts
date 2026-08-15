/**
 * Backend endpoints. Both are inlined at build time by Next, so they must be
 * set in the deployment environment before running `next build`.
 */
export const HTTP_BACKEND =
  process.env.NEXT_PUBLIC_HTTP_BACKEND ?? "http://localhost:3001";

export const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? "ws://localhost:3001/ws";

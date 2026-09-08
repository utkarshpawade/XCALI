# Build context is the repository root:
#   docker build -f apps/excelidraw-frontend/Dockerfile -t excalidraw-frontend .
#
# NEXT_PUBLIC_* are inlined into the client bundle by `next build`, so they are
# build arguments rather than runtime environment variables. Changing where the
# backends live means rebuilding this image, not restarting the container.
FROM node:22-alpine AS base
RUN apk add --no-cache libc6-compat
RUN corepack enable
WORKDIR /app

FROM base AS builder
ARG NEXT_PUBLIC_HTTP_BACKEND
ARG NEXT_PUBLIC_WS_URL
ENV NEXT_PUBLIC_HTTP_BACKEND=$NEXT_PUBLIC_HTTP_BACKEND
ENV NEXT_PUBLIC_WS_URL=$NEXT_PUBLIC_WS_URL
ENV NEXT_TELEMETRY_DISABLED=1
# Opts next.config.ts into `output: "standalone"`; see the comment there.
ENV BUILD_STANDALONE=1
COPY . .
RUN pnpm install --frozen-lockfile
# turbo builds @repo/common and @repo/ui first.
RUN pnpm turbo build --filter=excelidraw-frontend

FROM base AS runner
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# `output: "standalone"` combined with outputFileTracingRoot at the repository
# root mirrors the workspace layout inside .next/standalone, so the server entry
# point keeps its full `apps/excelidraw-frontend/` path.
COPY --from=builder /app/apps/excelidraw-frontend/.next/standalone ./
COPY --from=builder /app/apps/excelidraw-frontend/.next/static ./apps/excelidraw-frontend/.next/static
COPY --from=builder /app/apps/excelidraw-frontend/public ./apps/excelidraw-frontend/public

# Next writes its runtime cache here.
RUN mkdir -p apps/excelidraw-frontend/.next/cache \
    && chown -R node:node apps/excelidraw-frontend/.next

USER node
EXPOSE 3000
CMD ["node", "apps/excelidraw-frontend/server.js"]

# ── Stage 1: Build ──
FROM node:22-slim AS builder

RUN corepack enable && corepack prepare pnpm@10.31.0 --activate

WORKDIR /app

# Copy workspace manifests first (layer cache)
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json ./
COPY packages/common/package.json packages/common/
COPY packages/server/package.json packages/server/
COPY packages/plugin/package.json packages/plugin/

RUN pnpm install --frozen-lockfile

# Copy source
COPY packages/common/ packages/common/
COPY packages/server/ packages/server/
COPY packages/plugin/ packages/plugin/

# Build server (common is a build-time dependency)
RUN pnpm run build

# Build plugin with the WebSocket URL baked in.
# In Docker/Traefik, the browser connects to the WS via the public domain.
# Default works for localhost; override with --build-arg for production.
ARG WS_URI="ws://localhost:4402"
RUN cd packages/plugin && WS_URI=${WS_URI} pnpm run build


# ── Stage 2: Runtime ──
FROM node:22-slim

RUN corepack enable && corepack prepare pnpm@10.31.0 --activate

WORKDIR /app

# Install production deps for server only
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json ./
COPY packages/common/package.json packages/common/
COPY packages/server/package.json packages/server/

# Plugin is not needed at runtime (static files only)
# Create a stub so pnpm doesn't complain about missing workspace member
RUN mkdir -p packages/plugin && echo '{"name":"mcp-plugin","version":"1.0.0"}' > packages/plugin/package.json

RUN pnpm install --frozen-lockfile --prod

# Copy built server (includes dist/data/ and dist/static/)
COPY --from=builder /app/packages/server/dist packages/server/dist

# Copy built plugin (static files served by 'serve')
COPY --from=builder /app/packages/plugin/dist /plugin-dist

# Lightweight static file server for the plugin UI
RUN npm install -g serve@14

RUN mkdir -p /app/logs

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 4400 4401 4402

ENTRYPOINT ["/entrypoint.sh"]

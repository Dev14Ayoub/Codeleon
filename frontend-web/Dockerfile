# syntax=docker/dockerfile:1.6
#
# Multi-stage build for the Codeleon React + Vite frontend.
# Stage 1: install npm deps and run `vite build` to produce static assets.
# Stage 2: serve those assets via nginx, the production-grade static server.
#
# The Caddy reverse proxy in front of the stack handles routing /api/* to
# the backend; this image only serves the SPA shell and lets the browser
# fetch the API via the same origin.

# ---------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------
FROM node:20-alpine AS build

WORKDIR /app

# Cache the npm install layer separately from the source so editing UI
# code does not re-install every package on every build.
COPY package.json package-lock.json ./
RUN npm ci

# Vite reads VITE_API_BASE_URL at build time and inlines it into the
# bundle. In production we use a relative path so the same origin
# (Caddy) routes /api/* to the backend container.
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

COPY . .
RUN npm run build

# ---------------------------------------------------------------------------
# Stage 2 — serve
# ---------------------------------------------------------------------------
FROM nginx:1.27-alpine

# Replace the default nginx config with one that knows about SPA routing
# (any unknown path falls back to index.html so React Router can take over).
COPY <<'EOF' /etc/nginx/conf.d/default.conf
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    # Long cache on hashed assets (Vite emits files with content hashes).
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # SPA fallback: any path that doesn't match a file falls through to
    # index.html so the React router can render the right view.
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Hide nginx version
    server_tokens off;
}
EOF

COPY --from=build /app/dist /usr/share/nginx/html

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -q --spider http://localhost/ || exit 1

EXPOSE 80

# syntax=docker/dockerfile:1
# Build
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
# Optional extra CA for TLS-intercepting proxies (build secret extra_ca); no-op without it.
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi; \
    npm ci
COPY . .
RUN npm run build

# Serve: static files plus /api proxied to the backend (same origin, so the session cookie just works)
FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80

# Multi-stage so the runtime image carries no compiler and no dev dependencies.
# Node 22 here matches the version pinned in .nvmrc, so the container and a local
# `npm run dev` run the same runtime.
FROM node:22-alpine AS build
WORKDIR /app

# Dependencies first, so a source-only change does not reinstall them.
COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production

COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY --from=build /app/dist ./dist

# The image ships with a non-root `node` user; use it rather than running as root.
USER node

EXPOSE 8080
CMD ["node", "dist/server.js"]

# Build context: repository root (npm workspaces: @misplease/db + @misplease/auth).
FROM node:24-alpine AS builder

WORKDIR /app

COPY package.json package-lock.json ./
COPY packages/db/package.json ./packages/db/
COPY services/auth/package.json ./services/auth/

RUN npm ci -w @misplease/db -w @misplease/auth

COPY packages/db ./packages/db
COPY services/auth ./services/auth

RUN npm run build -w @misplease/db && npm run build -w @misplease/auth

FROM node:24-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production
ENV DRIZZLE_MIGRATIONS_FOLDER=/app/packages/db/drizzle

COPY package.json package-lock.json ./
COPY packages/db/package.json ./packages/db/
COPY services/auth/package.json ./services/auth/

RUN npm ci -w @misplease/db -w @misplease/auth --omit=dev && npm cache clean --force

COPY --from=builder /app/services/auth/dist ./services/auth/dist
COPY --from=builder /app/packages/db/dist ./packages/db/dist
COPY packages/db/drizzle ./packages/db/drizzle

USER node

ENV NODE_OPTIONS="--enable-source-maps"

EXPOSE 3000

WORKDIR /app/services/auth

CMD ["node", "dist/index.js"]

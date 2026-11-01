FROM node:24.22.2-alpine AS builder

WORKDIR /app

COPY package*.json ./

RUN npm ci

COPY src ./src

COPY esbuild.config.mjs ./

COPY tsconfig.json ./

RUN npm run build

FROM node:24.22.2-alpine AS runner

WORKDIR /app

ENV NODE_ENV=production

COPY package*.json ./

RUN npm ci --omit=dev && npm cache clean --force

COPY --from=builder /app/dist ./dist

USER node

ENV NODE_OPTIONS="--enable-source-maps"

EXPOSE 3000

CMD ["node", "/app/dist/index.js"]

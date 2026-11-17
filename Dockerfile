# Reelbox web app + Xtream proxy in one small container.
#   docker build -t reelbox .
#   docker run -d -p 8080:8080 -e XTREAM_URL=http://your-server:8080 reelbox
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:22-alpine
WORKDIR /app
COPY --from=build /app/dist ./dist
COPY server/proxy.mjs ./server/proxy.mjs
ENV PORT=8080 HOST=0.0.0.0
EXPOSE 8080
USER node
CMD ["node", "server/proxy.mjs"]

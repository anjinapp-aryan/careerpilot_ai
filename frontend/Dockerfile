FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Consumed by src/lib/api.ts's axios baseURL at build time — must be an ARG
# (not just ENV) so `docker compose build --build-arg` / docker-compose.yml's
# `build.args` can actually set it; falls back to vite's own .env handling
# (unset -> src/lib/api.ts's 'http://localhost:8080' default) if omitted.
ARG VITE_API_BASE_URL
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80

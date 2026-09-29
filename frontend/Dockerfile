FROM node:22-alpine AS build

WORKDIR /app

COPY package*.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

FROM nginx:alpine

ARG APP_VERSION=2.1.0
ENV APP_VERSION=${APP_VERSION}
LABEL org.opencontainers.image.title="achernar-observer-frontend" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.source="https://github.com/eridanimelo/achernar_oberver"

COPY --from=build /app/dist/observer/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

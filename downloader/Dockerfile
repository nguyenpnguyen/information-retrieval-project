# Chromium của Debian có bản arm64 lẫn amd64: chạy thật trên Mac M-series, không phải giả lập như image puppeteer (chỉ amd64).
FROM node:24-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends chromium \
    && rm -rf /var/lib/apt/lists/*
ENV PUPPETEER_SKIP_DOWNLOAD=true PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY server.js ./
USER node
EXPOSE 8800
CMD ["node", "server.js"]

# API dompet REZO sebagai aplikasi Helipod (tanpa perlu masuk terminal VPS).
FROM node:22-alpine
WORKDIR /app
COPY workers/wallet/src/index.js ./index.js
COPY vps/server.js ./server.js
COPY vps/package.json ./package.json
ENV HOST=0.0.0.0
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:8080/health || exit 1
CMD ["node", "server.js"]

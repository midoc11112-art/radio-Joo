FROM jrottenberg/ffmpeg:7.1-alpine
RUN apk add --no-cache nodejs npm
WORKDIR /app
COPY package.json .
RUN npm install
COPY server.js .
EXPOSE 8080
CMD ["node", "server.js"]

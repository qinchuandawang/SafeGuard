# SafeGuard 管理后台前端 - 多阶段构建
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:22-alpine
RUN npm install -g serve
WORKDIR /app
COPY --from=build /app/dist/ .
EXPOSE 80
CMD ["serve", "-s", "-l", "80", "."]

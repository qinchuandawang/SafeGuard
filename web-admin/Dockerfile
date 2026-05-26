# SafeGuard 管理后台前端 - 静态文件服务器
FROM node:22-alpine
RUN npm install -g serve
WORKDIR /app
COPY dist/ .
EXPOSE 80
CMD ["serve", "-s", "-l", "80", "."]

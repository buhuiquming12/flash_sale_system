# syntax=docker/dockerfile:1
# =====================================================================
# 前端镜像。构建上下文是 fss-web/ 本身。
#
# 两段：node 段跑 vue-tsc + vite build，nginx 段只留 dist 里的静态文件。
# 成品镜像约 60MB，不含 node_modules。
# =====================================================================
FROM node:24-alpine AS build

WORKDIR /web

# 先拷锁文件装依赖：只改业务代码时这一层命中缓存，省掉一次完整 npm ci。
# 用 npm ci 而不是 npm install —— ci 严格按 package-lock.json 装，
# 镜像里的版本和本地开发一致；install 会在锁文件与 package.json
# 有出入时悄悄改锁文件，构建结果就不可复现了
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund

COPY . .

# build 脚本是 vue-tsc --noEmit && vite build：类型错误会让镜像构建失败。
# 这是刻意的——类型都不过的前端不该被打进镜像
RUN npm run build

# ---------------------------------------------------------------------
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1

# Frontend build with Node and Nginx serving
FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json.example ./package.json
RUN npm install
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]

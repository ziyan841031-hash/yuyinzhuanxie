import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// dev 时把 /ws 代理到后端 Spring Boot(8080)，避免跨域。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
});

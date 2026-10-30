import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const api = process.env.ROUTEIQ_API ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': api,
      '/ws': { target: api.replace(/^http/, 'ws'), ws: true },
    },
  },
})

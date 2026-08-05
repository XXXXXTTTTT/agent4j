import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../../../target/classes/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 2600,
  },
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/ws': {
        target: 'ws://127.0.0.1:8080',
        ws: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    pool: 'forks',
    maxWorkers: 1,
    fileParallelism: false,
    setupFiles: './src/test/setup.ts',
  },
})

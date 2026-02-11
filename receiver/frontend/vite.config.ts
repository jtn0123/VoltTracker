import { defineConfig } from 'vite'
import path from 'path'

export default defineConfig({
  root: '.',
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/__tests__/**/*.test.ts'],
    setupFiles: ['src/__tests__/setup.ts'],
  },
  build: {
    outDir: '../static/js/dist',
    emptyOutDir: true,
    cssCodeSplit: false,
    rollupOptions: {
      input: 'src/main.ts',
      output: {
        entryFileNames: 'main.js',
        chunkFileNames: '[name].js',
        assetFileNames: '[name].[ext]'
      }
    }
  },
  // Ensure CDN dependencies are bundled when imported
  optimizeDeps: {
    include: ['socket.io-client', 'flatpickr', 'chart.js', 'leaflet']
  }
})

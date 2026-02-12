import { defineConfig } from 'vite'
import path from 'path'
import istanbul from 'vite-plugin-istanbul'

export default defineConfig({
  root: '.',
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  plugins: [
    // Istanbul coverage instrumentation for E2E tests
    // Only active when INSTRUMENT_COVERAGE=true (never affects prod builds)
    ...(process.env.INSTRUMENT_COVERAGE === 'true'
      ? [istanbul({
          include: 'src/**/*.ts',
          exclude: ['node_modules', 'src/__tests__/**'],
          extension: ['.ts'],
          requireEnv: true,
          forceBuildInstrument: true,
        })]
      : []),
  ],
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/__tests__/**/*.test.ts'],
    setupFiles: ['src/__tests__/setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['lcov', 'text'],
      reportsDirectory: './coverage',
    },
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

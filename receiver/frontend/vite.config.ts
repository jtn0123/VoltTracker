import { defineConfig } from 'vite'

export default defineConfig({
  root: '.',
  build: {
    outDir: '../static/js/dist',
    emptyOutDir: true,
    rollupOptions: {
      input: 'src/main.ts',
      output: {
        entryFileNames: 'main.js',
        chunkFileNames: '[name].js',
        assetFileNames: '[name].[ext]'
      }
    }
  }
})

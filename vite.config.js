import { defineConfig } from 'vite';
import { resolve } from 'path';
import { fileURLToPath } from 'url';

const __dirname = fileURLToPath(new URL('.', import.meta.url));

export default defineConfig({
    root: './',
    build: {
        outDir: 'receiver/static/dist',
        emptyOutDir: true,
        rollupOptions: {
            input: {
                dashboard: resolve(__dirname, 'receiver/static/js/dashboard.js'),
                styles: resolve(__dirname, 'receiver/static/css/style.css'),
            },
            output: {
                entryFileNames: '[name].min.js',
                chunkFileNames: '[name]-[hash].js',
                assetFileNames: '[name].min.[ext]'
            }
        },
        cssMinify: true,
        minify: 'terser',
        terserOptions: {
            compress: {
                drop_console: false,  // Keep console.error/warn, remove log/debug in production
                drop_debugger: true
            },
            format: {
                comments: false  // Remove all comments
            }
        },
        sourcemap: true,  // Generate sourcemaps for debugging
        target: 'es2015', // Support older browsers
        cssCodeSplit: true
    },
    server: {
        port: 3000,
        proxy: {
            '/api': {
                target: 'http://localhost:5000',
                changeOrigin: true
            }
        }
    }
});

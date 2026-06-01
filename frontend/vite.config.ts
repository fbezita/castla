import { svelte, vitePreprocess } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vite';

export default defineConfig({
  define: {
    __CASTLA_BUILD_TIMESTAMP__: JSON.stringify(new Date().toISOString())
  },
  plugins: [svelte({ preprocess: vitePreprocess() })],
  build: {
    target: 'es2020',
    outDir: 'dist',
    assetsDir: 'assets',
    rollupOptions: {
      output: {
        manualChunks: undefined
      }
    }
  }
});

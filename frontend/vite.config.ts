import { fileURLToPath } from 'node:url';
import { svelte, vitePreprocess } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vite';
import { resolveFrontendSourceFingerprint } from './buildFingerprint';

export default defineConfig(() => {
  const frontendRoot = fileURLToPath(new URL('.', import.meta.url));
  const sourceFingerprint = resolveFrontendSourceFingerprint(frontendRoot);

  return {
    define: {
      __CASTLA_BUILD_TIMESTAMP__: JSON.stringify(sourceFingerprint)
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
  };
});

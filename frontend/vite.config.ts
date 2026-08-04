import { execFileSync } from 'node:child_process';
import { svelte, vitePreprocess } from '@sveltejs/vite-plugin-svelte';
import { defineConfig, loadEnv } from 'vite';

function resolveFrontendCommit(): string {
  try {
    const repositoryRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], {
      encoding: 'utf8'
    }).trim();
    const commit = execFileSync(
      'git',
      ['-C', repositoryRoot, 'log', '-1', '--format=%H', '--', 'frontend'],
      { encoding: 'utf8' }
    ).trim();
    return commit.slice(0, 12) || 'unknown';
  } catch {
    return 'unknown';
  }
}

export default defineConfig(({ mode }) => {
  const explicitBuildId = loadEnv(mode, '.', '').CASTLA_BUILD_TIMESTAMP?.trim();
  const buildTimestamp = explicitBuildId || resolveFrontendCommit();

  return {
    define: {
      __CASTLA_BUILD_TIMESTAMP__: JSON.stringify(buildTimestamp)
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

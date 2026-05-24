import App from './App.svelte';
import './styles.css';

const app = new App({
  target: document.getElementById('app') as HTMLElement
});

export default app;

import { mount } from 'svelte';
import App from './App.svelte';
import './styles.css';

const target = document.getElementById('app');

if (!target) {
  throw new Error('Castla app target #app was not found');
}

const app = mount(App, {
  target
});

export default app;

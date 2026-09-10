import { createApp } from 'vue'
import App from './App.vue'
import './style.css'
import { hydrate } from './db.js'
createApp(App).mount('#app')
hydrate()

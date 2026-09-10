import { createApp } from 'vue'
import DesktopApp from './desktop/DesktopApp.vue'
import './desktop.css'
import { hydrate } from './db.js'

createApp(DesktopApp).mount('#app')
hydrate()

import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The build is copied straight into the Android app's assets; the phone serves it to browsers.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: { outDir: '../app/src/main/assets/web', emptyOutDir: true },
  server: { proxy: { '/api': 'http://PHONE_IP:47831' } }
})

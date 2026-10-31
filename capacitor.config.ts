import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.smartstudy',
  appName: '智能学习',
  webDir: 'dist',
  // backgroundColor: same as --bg in src/styles/tokens.css. The web build targets Chrome 98 (vite.config.ts);
  // on an older WebView Capacitor shows server.errorPath (public/webview-too-old.html) instead of the app.
  // Huawei's WebView is versioned differently, so index.html also checks the needed features itself.
  android: { backgroundColor: '#f6f9ff', allowMixedContent: false, minWebViewVersion: 98 },
  server: { errorPath: 'webview-too-old.html' },
};
export default config;

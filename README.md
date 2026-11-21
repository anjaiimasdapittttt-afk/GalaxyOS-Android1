# Galaxy OS (Kotlin + WebView)
Build: buka folder ini di Android Studio lalu Build > Build APK(s), atau push ke GitHub
(workflow .github/workflows/build-apk.yml menghasilkan app-debug.apk di tab Actions > Artifacts).
UI web ada di app/src/main/assets/www/ (sama dengan proyek galaxy-os).
App Drawer menampilkan aplikasi GalaxyOS + aplikasi Android terpasang (PackageManager, <queries> LAUNCHER, tanpa QUERY_ALL_PACKAGES).

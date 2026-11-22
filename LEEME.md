# IG Descarga (Android)

App sencilla: abre Instagram en un navegador interno (con tu sesión), detecta el vídeo que se reproduce y lo guarda en **Películas/Instagram**.

## Conseguir el APK (sin instalar nada en el PC)
1. Crea un repositorio en GitHub y sube todo este contenido (incluida la carpeta `.github`).
2. Pestaña **Actions** → **Build APK** → **Run workflow** (o se lanza solo al subir).
3. Cuando termine (~3 min), entra en la ejecución y descarga el artefacto **IG-Descarga-APK**.
4. Descomprime, pasa `app-debug.apk` al móvil e instálalo (permite "instalar apps desconocidas").

Alternativa: abrir la carpeta en Android Studio y pulsar Build → Build APK.

## Uso
- Abre la app, inicia sesión en Instagram una vez (queda guardada).
- Abre un reel/vídeo y dale al play → aparece "Vídeo detectado" → **Descargar vídeo**.
- También: en Instagram, Compartir → copiar enlace → en la app **Pegar**. O compartir a "IG Descarga".

## Limitaciones
- Instagram a veces sirve vídeo y audio por separado; en ese caso el mp4 puede salir sin sonido.
- Instagram cambia su web con frecuencia: si deja de detectar, habrá que ajustar `consider()`.
- Úsala con contenido tuyo o con permiso del autor.

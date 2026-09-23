# ChatMesh 📱🌐

Aplicación de chat nativa estilo WhatsApp para Android que funciona completamente **sin conexión a Internet**, utilizando exclusivamente **WiFi Aware (`WifiAwareManager`)** y **WiFi Direct (`WifiP2pManager`)** para formar una red en malla descentralizada entre dispositivos.

---

## 🚀 Compilación y Publicación Automática (GitHub Actions)

El proyecto incluye un flujo de integración continua en [`.github/workflows/android.yml`](.github/workflows/android.yml) que automatiza todo el proceso:

1. **Disparador:** Se activa automáticamente en cada `push` a la rama `main` y en cada `pull_request` (o manualmente desde la pestaña **Actions** con `workflow_dispatch`). En los pull requests la APK queda disponible como *artifact* y no se publica una Release.
2. **Compilación de Release:** Ejecuta `./gradlew assembleRelease`.
3. **Firma Digital con Secretos:** Firma la APK utilizando los secretos configurados en tu repositorio de GitHub.
4. **Publicación en Releases:** Crea una nueva versión en la sección **Releases** de GitHub con el archivo **`ChatMesh-release.apk`** listo para descargar e instalar.

---

### 🔑 Configuración de Secretos en GitHub (Opcional)

Para firmar la APK de producción con tu propia clave (Keystore), añade los siguientes secretos en tu repositorio de GitHub (**Settings** > **Secrets and variables** > **Actions**):

| Nombre del Secreto | Descripción |
| :--- | :--- |
| `KEYSTORE_BASE64` (o `SIGNING_KEY`) | Archivo `.jks` o `.keystore` codificado en base64 (`base64 -w 0 tu-clave.jks`) |
| `STORE_PASSWORD` | Contraseña del almacén de claves (keystore) |
| `KEY_ALIAS` | Alias de la clave (por defecto: `upload`) |
| `KEY_PASSWORD` | Contraseña de la clave privada |

> **Nota:** Si no configuras los secretos, el flujo de trabajo genera automáticamente una clave de respaldo para que la compilación de la APK firmada nunca falle y esté disponible de inmediato en las Releases.

---

## 📋 Características de la Aplicación

- **Creación automática de grupo WiFi Direct:** Crea la red P2P al abrir la app.
- **SSID basado en SIM:** Genera el SSID con el número de la SIM activa (ejemplo: `ChatMesh_+5351234567`).
- **Malla con WiFi Aware (NAN):** Anuncio y descubrimiento de pares cercanos sin depender de routers ni Internet.
- **Enrutamiento Multi-Salto (Store & Forward):** Los paquetes viajan de salto en salto por la malla; si un contacto está desconectado, el mensaje se guarda en SQLite local y se entrega al reconectarse.
- **Chat Estilo WhatsApp:**
  - 4 Pestañas: Chats, Malla P2P, Contactos, Llamadas.
  - Chat fullscreen con Texto, Fotos, Notas de voz interactivas y Archivos.
  - Estados en tiempo real: *en línea*, *escribiendo...*, *grabando audio...*.
  - Llamadas de audio y videollamadas directas P2P.

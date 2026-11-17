# Tzinda UNO Q Local SQLite API

Aplicación local de Arduino App Lab para guardar lecturas recibidas por HTTPS en SQLite y entregar la última lectura a TzindaApp. No genera valores de pulso ni SpO₂. El archivo de base se crea en `data/tzinda_local.sqlite3` al iniciar la app.

## Instalar en UNO Q

1. Abre Arduino App Lab y elige importar una app desde un archivo ZIP.
2. Selecciona `TzindaUNOQ-LocalSQLite-App.zip` y abre la app importada.
3. Pulsa **Run**. Si tienes otra app usando el puerto 8081, detén esa app primero.
4. En el UNO Q genera un certificado cuyo SAN incluya la IP local estable que usará el teléfono. Por ejemplo, adapta la IP y ejecuta `openssl req -x509 -newkey rsa:3072 -sha256 -days 365 -nodes -keyout tzinda-private.key -out tzinda-cert.pem -subj "/CN=tzinda-uno-q" -addext "subjectAltName=IP:192.168.18.35"`. Protege la clave privada en el dispositivo y no la incluyas en ZIP, repositorio, firmware, capturas ni logs.
5. Configura `TZINDA_TLS_CERT_FILE` y `TZINDA_TLS_KEY_FILE` con rutas locales a esos archivos en la configuración privada de la app UNO Q. Configura también `TZINDA_API_TOKEN` con un valor aleatorio de al menos 32 bytes. Sin certificado y clave válidos, el servicio no arranca; no hay fallback HTTP.
6. Confirma la huella del certificado directamente en una terminal confiable del UNO Q con `openssl x509 -in "$TZINDA_TLS_CERT_FILE" -noout -fingerprint -sha256`. Verifica la IP, SAN y huella por un canal local fuera de la red antes de guardar cualquier token en el teléfono; HTTP TOFU no es seguro.
7. En TzindaApp, abre **Ajustes → Pulsera y datos del teléfono**, escribe `https://IP_DEL_UNO_Q:8081`, la huella SHA-256 de 64 dígitos y el mismo token. La app cifra el token con Android Keystore y lo guarda junto con URL/huella atómicamente.
8. Activa el monitoreo en TzindaApp. La app móvil consultará `GET /api/v1/sensor` cada cinco segundos y rechazará HTTP, cambios de certificado, host incorrecto y redirecciones.

Esta app Python corre en el Linux del UNO Q; no es un sketch `.ino` para Arduino IDE. Arduino App Lab administra apps para la parte Linux y, cuando se incluye, el microcontrolador. Este paquete solo necesita Linux para SQLite y HTTPS.

## API

- `GET /health` — disponibilidad del servicio por HTTPS.
- `GET /api/v1/stats` — cantidad de lecturas locales; requiere `Authorization: Bearer <token>`.
- `GET /api/v1/sensor` — devuelve la última lectura o HTTP 503 si aún no hay ninguna; requiere token.
- `POST /api/v1/sensor` — guarda una lectura JSON real en SQLite; requiere token y TLS.
- `GET /` — página de estado.

El token debe tener 32 bytes o más y se compara en tiempo constante. `GET /health` y la página genérica `GET /` son públicos; no devuelven lecturas. Sin token de servidor, las rutas privadas fallan cerradas con HTTP 503; sin credencial válida responden HTTP 401. El servidor exige TLS 1.2 o superior; certificado o clave ausente/inválido impide iniciar el API. Android valida el SAN del host y fija el certificado por su huella SHA-256. Cambiar o renovar el certificado requiere confirmar la nueva huella en persona y guardarla en la app.

El listener limita el trabajo a cuatro conexiones simultáneas y una cola de aceptación de ocho; al agotarse la capacidad, cierra conexiones nuevas. El handshake TLS vence a los cinco segundos. La lectura de una petición tiene un límite inactivo de cinco segundos y un plazo total de 15 segundos que cubre la línea, encabezados y cuerpo; el JSON conserva el máximo de 32 KiB. Esto protege el UNO Q de conexiones que dejan incompleto el handshake o envían una petición lentamente.

El POST admite JSON anidado y preserva el payload original. Reconoce `heart_rate_bpm`, `heartRate`, `heart_rate`, `bpm`, `pulse`, `spo2_percent`, `spo2`, `oxygen_saturation`, `latitude`, `longitude` y ejes `accel_x`/`ax`, `accel_y`/`ay`, `accel_z`/`az`. Las demás claves también quedan guardadas dentro del JSON original.

El firmware que está actualmente en el UNO Q no se puede extraer desde este paquete. Para que SQLite reciba datos del Arduino, el programa que lee el sensor debe soportar HTTPS/TLS, validar la identidad del servidor y enviar cada medición como JSON a `POST https://IP_DEL_UNO_Q:8081/api/v1/sensor`. La compatibilidad TLS del firmware instalado queda pendiente de prueba física; HTTP sin cifrado se rechaza. Si el sensor no está conectado, la base se inicia vacía y la API informa que todavía no hay lecturas.

La base conserva hasta 30 días y 100,000 filas. La retención no sustituye la autenticación ni la validación del certificado TLS.

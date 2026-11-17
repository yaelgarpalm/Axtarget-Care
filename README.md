# TzindaApp 2.1.7

Aplicación Android nativa para apoyar el monitoreo de una persona y avisar a sus contactos ante una posible caída. La detección es experimental: puede omitir caídas o generar falsas alarmas y no sustituye atención médica.

## Roles y dispositivos

- **Persona protegida:** lleva el teléfono; comparte su ubicación y los datos que recibe de la pulsera.
- **Cuidador/a:** consulta ubicación, eventos, pulso y SpO₂ desde otra red siempre que ambos teléfonos tengan Internet.
- **Cámara:** se agrega por separado con QR o código. Envía avisos de detección; el video no se transmite a Supabase.
- **Pulsera Arduino UNO Q:** se conecta al teléfono de la persona por Bluetooth LE tras emparejar `Tzinda` desde Ajustes de Android; app y GATT exigen vínculo cifrado. Wi-Fi local es una alternativa de pruebas con rutas autenticadas y HTTPS fijado por huella SHA-256; el UNO Q falla al arrancar si falta TLS y la app rechaza HTTP. La huella debe confirmarse localmente fuera de banda antes de guardar el token.

La vinculación de persona, cuidador y cámara usa Supabase por Internet; no requiere que los teléfonos estén en la misma red Wi-Fi. Los códigos se generan desde **Dispositivos**. Las lecturas, eventos y ubicación se guardan localmente y se sincronizan con Supabase cuando hay conexión. La ubicación requiere permiso de Android y que el monitoreo esté activo.

La pulsera envía pulso, SpO₂ y detecciones de golpe/caída. El teléfono protegido inicia una cuenta regresiva de 20 segundos; si no se cancela, solicita SMS y llamada a los contactos configurados. Cuando varias fuentes reportan el mismo incidente, la app deduplica el aviso para evitar llamadas repetidas.

**Medicamentos:** la persona protegida puede guardar medicamentos, dosis indicadas y horarios en una base local, recibir recordatorios de Android sin Internet, confirmar/posponer/omitir tomas y consultar el historial. Las recetas por intervalo («cada 8, 12 o 24 horas») generan sus horarios a partir de la primera toma y, si una toma se registra a otra hora, la app propone —solo con confirmación— mover los próximos avisos desde esa hora. El formulario permite escanear la caja o la receta para leer el nombre, la dosis y la presentación; el reconocimiento usa un catálogo de medicamentos frecuentes para corregir lecturas dudosas y todo se revisa antes de guardar. La lectura se puede hacer en el teléfono, sin conexión, o mediante la función autenticada `medication-label` de Supabase. Al elegir la nube, la foto pasa por Supabase y NVIDIA para analizarla; TzindaApp no la guarda y, si falla, la app vuelve a la lectura local. La opción en el teléfono no envía la imagen. La sincronización incluye una opción para compartir cada registro, desactivada por defecto. Un cuidador vinculado puede consultar medicamentos compartidos y registrar tomas, pero no editar recetas ni horarios.

## Supabase

El backend incluye autenticación, perfiles, vínculos, dispositivos, mediciones, eventos y Realtime con políticas RLS. La configuración y despliegue están descritos en [docs/supabase-backend.md](docs/supabase-backend.md); las migraciones están en [supabase/migrations](supabase/migrations).

Gradle lee `TZINDA_SUPABASE_URL`, `TZINDA_SUPABASE_PUBLISHABLE_KEY` y `TZINDA_SUPABASE_REDIRECT_URL` del entorno o de un archivo local `.env.local`. Copia [.env.example](.env.example) como plantilla. No guardes contraseñas, claves privadas ni la clave `service_role` en el APK o el repositorio. La clave `service_role` solo se configura en el entorno servidor de Supabase.

La lectura opcional en la nube requiere sesión y el despliegue de `medication-label`. Configura `NVIDIA_API_KEY` y, de forma opcional, `NVIDIA_VISION_MODEL` como secretos del servidor; no agregues credenciales NVIDIA a la configuración Android. Consulta [despliegue, cuotas y rotación](docs/medication-label-proxy.md). Sin sesión o si la nube falla, se mantiene la lectura local. Retira los APK anteriores y rota su clave NVIDIA si fueron distribuidos.

## Arduino UNO Q

Los proyectos listos para Arduino App Lab están en [arduino](arduino): la pulsera con BLE y la API local de SQLite/HTTPS. También están los paquetes ZIP en [outputs](outputs). Consulta sus README para cargar cada proyecto. Para Wi-Fi se requiere configurar el certificado/clave TLS y un token de al menos 32 bytes en el servidor; dirección HTTPS, huella y token se guardan desde Ajustes. La compatibilidad TLS del firmware instalado que envía lecturas a SQLite queda pendiente de prueba física. Ambos métodos de conexión con la pulsera se pueden desconectar y reconectar desde la app.

## Compilar

Abre esta carpeta en Android Studio o ejecuta en Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. Identificador: `com.guardian.tzindaapp.v2`; versión 2.1.7, código 11; Android mínimo 7.0 (API 24). La compilación debug usa firma de depuración y sirve para pruebas, no para publicación en Google Play.

La verificación de esta auditoría está documentada en [checklist y resultados](docs/auditoria/README.md). El flujo entre dos teléfonos y la pulsera, incluidos el certificado TLS y el firmware UNO Q, debe verificarse en dispositivos reales; los resultados del emulador no confirman aceptación integral de hardware.

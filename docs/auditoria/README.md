# Auditoría integral de TzindaApp

Inicio: 1 de noviembre de 2026 (America/Mexico_City). Rama: `codex/auditoria-integral-tzinda`.
Base: `0ec5a5ce1035487452d63b99a1cbb12592d1b5e5`. Auditoría e implementación **en curso**.

El checklist aportado es una lista de hipótesis, no evidencia de ejecución. Se prioriza la solicitud actual de implementar sobre su antiguo prompt de auditoría sin cambios. No se trabaja en Tzinda Band V1.

## Reglas y evidencia

- `[x]`: corrección implementada con comprobación indicada; no equivale a aceptación en teléfonos.
- `[~]`: validación parcial o pendiente de emulador, dispositivos o servicios.
- `[ ]`: trabajo pendiente.
- No se han enviado SMS/llamadas ni alertas reales. Las consultas remotas son de metadatos y solo lectura.
- APK de pruebas aislado: `-PtzindaAudit=true`, paquete `com.guardian.tzindaapp.v2.audit`, sin configuración de Supabase/NVIDIA. No sustituye la app instalada del usuario.
- La compilación inicial y tareas unitarias terminaron correctamente (Gradle reutilizó resultados). Las correcciones requieren una nueva ejecución; no se atribuye esta primera salida a pruebas nuevas.

## Inventario funcional y datos

| Área | Entradas y fuente | Verificación pendiente |
|---|---|---|
| Cuenta y perfil | MainActivity, SupabaseBackend, EncryptedSessionStore, AppStore; Auth y profiles | Recuperación, cambio de cuenta, identidad frente a modo del teléfono |
| Navegación | TzindaNavigationPolicy y MainActivity; Inicio, Alertas, Salud, Supervisión; cámara: Inicio, Alertas, Vincular, Ajustes | Atrás, restauración, accesibilidad y permisos |
| Contactos y emergencia | AppStore/ContactsRepository, SQLite sync_contacts, GuardianMonitorService | Retención, cancelación, regresión sin SMS real |
| Historial y biometría | LocalTelemetryDatabase; JSON de 100 eventos visible en AppStore; fall_events, measurements | Fuente canónica de historial y frescura |
| Medicamentos | MedicationRepository/Database/UI/ReminderScheduler; medications y medication_intakes | Caché, concurrencia, revocación, reinicio |
| Cuidados | CareFeatureStore; JSON records/deleted; care_records, alert_followups | Ámbito de cuenta/persona, sincronización y estado geográfico |
| Dispositivos | IDs locales, devices, BLE y Wi-Fi UNO Q | Registro frente a conectividad, identidad del periférico |
| Cámara | CameraFallController, enlaces RPC, CameraFallRealtime | Consentimiento, dos teléfonos y revocación |
| Ubicación | Servicio Android, sync_locations, location_history/caregiver_locations | Consentimiento y separación entre uso local y publicación |
| Sincronización | SupabaseSyncRunner, JobScheduler, colas SQLite y JSON | Estado agregado, reintentos, sesiones concurrentes |

## Hallazgos del checklist

| ID | Estado | Evidencia / siguiente trabajo |
|---|---|---|
| H01 | [ ] Confirmado | syncProfile publica user_role y applyRemoteProfile modifica el modo local |
| H02 | [x] Corrección local verificada | 404 ya no se convierte en []; descarga paginada, parseo completo, transacción; conservar acciones pendientes |
| H03 | [~] Agregación verificada; cobertura integral pendiente | Estado agregado de telemetría, medicamentos, cuidados/borrados, perfil y registro; falta comprobar escenarios completos de cuenta |
| H04 | [x] Corrección local verificada | Estado pending/error/confirmed; únicamente confirmación permite envío dependiente de dispositivos |
| H05 | [ ] Confirmado | bindLocalDataToUser borra datos al cambiar de cuenta; requiere preservación y aislamiento |
| H06 | [ ] Confirmado | MainActivity concentra más de 4.600 líneas; extracción gradual |
| H07 | [ ] En revisión | Rutas y títulos por perfil, navegación secundaria |
| H08 | [ ] Confirmado | JSON visible limitado a 100 entradas |
| H09 | [ ] Confirmado | Colas JSON independientes; no se justifica reemplazar SQLite solo por tecnología |
| H10 | [x] Texto verificado por prueba unitaria | UI usa «Sin pendientes locales» y conserva por separado última confirmación remota |
| H11 | [x] Corrección local verificada | Estado geográfico en zone_runtime; configuración conserva su versión y cola |
| H12 | [~] Inspección remota | Tablas de medicamentos sí existen; historial remoto omite migraciones que el esquema refleja parcialmente |
| H13 | [ ] Confirmado | Repositorio/UI permiten acciones del cuidador; textos y políticas deben reconciliarse |

## Lote 1: integridad de descarga y registro

Problemas: errores HTTP se interpretaban como borrado; consultas limitadas a 500/1000 filas disparaban depuración de caché; acciones pendientes del cuidador se eliminaban; confirmación de envío podía marcar una edición posterior como sincronizada; registro de dispositivo fallido quedaba confirmado.

Archivos: `SyncSafety.kt`, `MedicationRepository.kt`, `MedicationDatabase.kt`, `SupabaseBackend.kt`, `SupabaseSyncJobService.kt`, `app/build.gradle.kts`, pruebas `SyncSafetyTest` y `MedicationDatabaseTest`.

Solución: páginas ordenadas por ID hasta respuesta vacía, rechazo de respuesta malformada/repetida, descarga y validación de ambas colecciones antes de aplicar una transacción, depuración solo de filas confirmadas, acuse condicionado a la versión enviada, propagación del error de envío del cuidador y estado explícito de registro.

Pruebas: errores 401/403/404/409/500/timeout controlados, fila inválida, paginación con límite inferior al solicitado, página repetida, respuesta vacía válida, fallo y recuperación de registro; SQLite: conservación de pendientes/fallidos, acuse atrasado, aislamiento de propietario, reapertura.

Resultado: 100 pruebas unitarias sin fallos (incluidas 6 de SyncSafetyTest); 7 pruebas instrumentadas de MedicationDatabaseTest sin fallos, en paquete audit y emulador API 37. Compilaron debug y APK de instrumentación. Ver `evidencia/lote-1-instrumentadas.log`. Riesgos: páginas no constituyen una transacción remota; cambios concurrentes se reconcilian en la siguiente ejecución. Falta validación con Supabase de pruebas y teléfonos. Un esquema sin actor_user_id se informa como error y conserva datos; ya no se oculta la incompatibilidad.

## Supabase remoto

Proyecto identificado mediante la URL configurada: `vhppdgbmmtfrbzocitob`. Evidencia de inventario, migraciones y asesores en `evidencia/`. Las 18 tablas públicas observadas tienen RLS. Esto no demuestra aislamiento correcto: falta ejecutar casos negativos por actor en una base de pruebas.

El asesor reporta funciones SECURITY DEFINER ejecutables por autenticados, dos tablas de cámara sin políticas directas y protección de contraseñas filtradas desactivada. Las funciones y tablas pueden depender deliberadamente de RPC; hay que revisar sus controles antes de cambiar permisos. [Asesor de funciones](https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable), [protección de contraseñas](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection).

No se ha aplicado ninguna migración remota. Docker está instalado pero su motor no responde; hay un emulador Android disponible, sin dispositivo físico conectado al inicio.

## Pendientes de aceptación física

- [ ] Dos teléfonos: cuenta protegida y cámara sin alteración del rol remoto; cuidador vinculado y revocado.
- [ ] Offline → editar → cerrar proceso → abrir → reconectar, comprobando filas por ID.
- [ ] Permisos Android, reinicio, batería, recordatorios y GPS.
- [ ] BLE/Wi-Fi con equipos autorizados, reconexión y lecturas antiguas.
- [ ] Cámara real, cuenta regresiva y cancelación; cualquier SMS/llamada requiere entorno expresamente autorizado.

El objetivo no está completado mientras queden correcciones confirmadas sin implementar o comprobaciones automatizables sin resolver.

## Lote 2: estado global y cuidados

Implementado y verificado en las pruebas indicadas; no equivale a aceptación integral:

- `AppStore.syncModules` agrega las cinco colas e incorpora el resumen a la decisión de éxito del runner. El reintento general incluye medicamentos.
- `TzindaStatusCopy` no declara éxito remoto solo porque no haya pendientes. Inicio muestra conteos por módulo.
- `CareFeatureStore` comparte el bloqueo entre instancias; versiones crecientes por registro y acuses condicionados a la versión enviada conservan ediciones concurrentes.
- `zone_runtime` conserva el estado geográfico sin modificar la configuración ni crear envíos de zona. Los eventos de entrada/salida siguen generando registros de bitácora.
- Resultado: **101 pruebas unitarias y 10 instrumentadas, cero fallos/errores/omitidas**. Compilación debug y APK de pruebas correctas. Evidencia en `lote-2-compilacion.log`, `lote-2-unitarias.json`, `lote-2-instrumentadas.log` y `lote-2-instrumentadas.json`. Instrumentación limitada a MedicationDatabaseTest y CareFeaturePersistenceTest; no se ejecutó NativeAppTest ni alertas reales.
- Pruebas: agregación con fallo de medicamento y borrado pendiente; acuse atrasado desde otra instancia; borrado pendiente tras reapertura; ubicación inicial sin ensuciar configuración.
- Limitaciones: falta el aislamiento de cuenta/persona en cuidados, pruebas completas de sesión concurrente y la política de autorización después de revocar vínculos. H03 no implica que esas capas estén terminadas.

## Continuidad de revisión de seguridad

Scan existente: `cd27264a-1f0e-404a-9563-2e495747963d`; cobertura parcial, sin informe final. Se guardó un checkpoint con modelo preliminar y candidatos que aún requieren validación del coordinador: callback de recuperación, clave cloud en APK, autenticidad de sensores, propiedad del dispositivo en phone_motion_status y consentimiento de ubicación. No se atribuye cobertura completa a los mensajes parciales de los revisores.

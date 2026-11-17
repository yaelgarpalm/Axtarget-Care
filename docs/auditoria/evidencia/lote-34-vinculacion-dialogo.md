# Evidencia del Lote 34: diálogo de vinculación

Fecha: 2026-11-10

## Cambio comprobado en código

En `app/src/main/java/com/example/guardian_iot/MainActivity.kt`, `showPairingQr` conserva en pantalla el QR y el código aunque la consulta detecte que el vínculo ya se completó. En ese momento actualiza un mensaje persistente y detiene el sondeo. El diálogo no se cancela con Atrás ni tocando fuera; al pulsar **Listo** se cierra, elimina los sondeos programados y refresca la pantalla si el vínculo quedó confirmado.

## Validación ejecutada

Comando: `:app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleDebug -PtzindaAudit=true --no-daemon`

- Resultado: `BUILD SUCCESSFUL`.
- Unitarias: 119, 0 fallos, 0 errores, 0 omitidas.
- Instrumentadas: 101, 0 fallos, 0 errores, 0 omitidas; AVD `Medium_Phone_API_37.0`.
- APK debug compilado.
- `git diff --check`: correcto.

Las pruebas fueron locales y no usaron el proyecto Supabase remoto, inferencias, alertas, SMS ni llamadas.

## Pendiente

Probar en dos teléfonos físicos que el código siga disponible mientras la otra persona lo transcribe, que el escaneo también complete el vínculo y que el diálogo se cierre al pulsar **Listo**. El AVD y la compilación no sustituyen esa aceptación física.

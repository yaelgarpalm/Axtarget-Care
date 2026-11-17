# Lote 33 — Codex Security Standard

Fecha: 9 de noviembre de 2026, America/Mexico_City.

Escaneo Standard de repositorio completado. ID: `13665345-fc96-44d0-945b-88538d998564`. Alcance declarado: `.`. El informe canónico `report.md` y los archivos JSON sellados están en el directorio de resultados de Codex Security, fuera del repositorio.

## Hallazgos

1. **CWE-400, severidad baja — clientes lentos podían agotar las APIs UNO Q.** El candidato afecta ambas variantes en la instantánea del escaneo: handshake TLS en el socket de escucha y lecturas HTTP sin límite total. Se corrigió después de capturar la instantánea: servidor con cuatro slots, backlog ocho, límites de handshake/idle y plazo total; suites loopback actuales: 15/15 y 17/17, ZIP verificados. No se ha validado la ruta física/firewall de App Lab.
2. **CWE-345, severidad baja — la vinculación BLE no autentica por sí sola los datos del sensor.** Android exige un periférico que el SO marque como vinculado y GATT exige enlace cifrado. Sin embargo, un periférico malicioso que logre vincularse y anuncie el UUID público puede entregar valores falsos; el protocolo carece de clave de dispositivo y contador de frescura. Una subida falsa del contador de caídas puede iniciar el conteo de alerta cuando el monitoreo está activo. Requiere proximidad, un vínculo malicioso, permisos/contactos y que el usuario no cancele el conteo. Sigue abierto para diseño y prueba física; no se simuló ni envió una alerta.

## Límite de la instantánea

El escaneo permaneció activo mientras evolucionaba la rama. Codex Security advirtió: `Working-tree contents changed while the scan was running; results were saved for the original snapshot.` El CWE-400 se conserva como hallazgo de esa instantánea y el parche actual se verificó por separado; el informe no es un nuevo escaneo de los últimos cambios. El reporte marca cobertura parcial por emparejamiento/hardware, configuración de Dashboard y firma de producción pendientes.

No se accedió al Supabase remoto desde el escáner; su estado se comprobó por separado con metadatos de solo lectura y la migración de índices aplicada con autorización. No se leyeron secretos ni filas personales.

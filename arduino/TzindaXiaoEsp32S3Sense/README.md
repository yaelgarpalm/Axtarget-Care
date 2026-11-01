# Tzinda · Pulsera XIAO ESP32S3 Sense

Firmware para **Seeed Studio XIAO ESP32S3 Sense** con **MAX30102** (pulso y SpO₂) y **MPU6050** (acelerómetro y detección de caídas). Se carga con **Arduino IDE** y entrega los datos a TzindaApp por **Bluetooth BLE**, sin Wi‑Fi, sin Python y sin Internet.

> La detección de caídas es experimental: puede omitir caídas suaves o generar falsas alarmas. No sustituye servicios de emergencia ni atención médica.

## Cableado

Ambos sensores comparten el mismo bus I2C de la XIAO.

| MAX30102 | XIAO ESP32S3 Sense |
|---|---|
| VIN | 3V3 |
| GND | GND |
| SDA | D4 (GPIO5) |
| SCL | D5 (GPIO6) |
| INT | sin conectar |

| MPU6050 | XIAO ESP32S3 Sense |
|---|---|
| VCC | 3V3 |
| GND | GND |
| SDA | D4 (GPIO5) |
| SCL | D5 (GPIO6) |
| AD0 | GND (dirección 0x68) |

- Une todas las tierras.
- El MAX30102 responde en `0x57` y el MPU6050 en `0x68`.
- La mayoría de los módulos ya incluyen pull-ups. Si no los traen, coloca **4,7 kΩ de SDA a 3V3 y de SCL a 3V3 una sola vez para todo el bus**; no añadas un juego por sensor.
- El LED de usuario de la XIAO (GPIO21) queda encendido mientras el teléfono está conectado.

## Preparar Arduino IDE

1. Instala el soporte de placas ESP32: **Archivo → Preferencias → Gestor de URLs adicionales** y añade
   `https://espressif.github.io/arduino-esp32/package_esp32_index.json`.
2. En **Herramientas → Placa → Gestor de placas**, instala **esp32 by Espressif Systems**.
3. Selecciona la placa **Seeed Studio XIAO ESP32S3**. Si usas **ESP32S3 Dev Module**, configura:
   - Flash Size: **8MB**
   - PSRAM: **OPI PSRAM**
   - USB CDC On Boot: **Enabled**
   - USB Mode: **Hardware CDC and JTAG**
4. Abre `TzindaXiaoEsp32S3Sense.ino`. La carpeta y el archivo deben conservar el mismo nombre.
5. No hacen falta librerías externas: `Wire` y BLE vienen con el core ESP32.

## Cargar el sketch

1. Conecta la XIAO por USB‑C.
2. Selecciona el puerto en **Herramientas → Puerto**.
3. Pulsa **Subir**. Si el puerto no aparece o la carga falla, mantén pulsado **B**, pulsa **R** y suelta **B** para entrar en modo bootloader; vuelve a intentar.
4. Abre el **Monitor Serie** a **115200** para ver el diagnóstico.

Al iniciar debe mostrar algo similar a:

```
MAX30102 listo (SDA=D4/GPIO5, SCL=D5/GPIO6)
MPU6050 listo (SDA=D4/GPIO5, SCL=D5/GPIO6)
BLE: anunciando como Tzinda XIAO.
```

Si un sensor no responde, revisa alimentación, GND, SDA/SCL y la dirección `AD0` del MPU. El otro sensor y el BLE pueden seguir funcionando mientras se reintenta cada 2 segundos.

## Vincular con TzindaApp

1. Enciende la pulsera y acércala al teléfono.
2. En TzindaApp abre **Dispositivos → Pulsera XIAO ESP32S3 Sense**.
3. Pulsa **Conectar o volver a conectar** y permite **Dispositivos cercanos** cuando Android lo solicite.
4. La app busca el servicio BLE de Tzinda; el nombre anunciado es `Tzinda XIAO`. No necesitas emparejar desde los ajustes de Bluetooth ni configurar una dirección IP.
5. El estado debe cambiar a **Conectada por Bluetooth · enviando lecturas**. Coloca el dedo en el MAX30102 y espera unos segundos.

La app solo muestra lecturas válidas; si no hay dedo, pulso y SpO₂ permanecen en cero. Las caídas se publican en cuanto el detector las confirma.

## Diagnóstico

- **Monitor Serie:** muestra `|a|`, BPM, SpO₂, IR, estado de ambos sensores y el total de caídas.
- **LED apagado:** no hay teléfono conectado. **LED encendido:** conexión BLE activa.
- **“Pulsera no encontrada” en la app:** confirma que la XIAO esté encendida, que el sketch esté cargado y que el teléfono tenga Bluetooth y permisos de dispositivos cercanos.
- **Se conecta pero no hay lecturas:** revisa el Monitor Serie; si el MAX30102 marca error, revisa su cableado y la alimentación. Sin dedo es normal que BPM/SpO₂ sean cero.

## Características BLE

| Característica | UUID | Formato |
|---|---|---|
| Servicio Tzinda | `6b9f0001-6e2a-4f72-a11d-545a696e6461` | — |
| Pulso | `6b9f0002-6e2a-4f72-a11d-545a696e6461` | `uint16` little-endian |
| SpO₂ | `6b9f0003-6e2a-4f72-a11d-545a696e6461` | `uint8` |
| Caídas | `6b9f0004-6e2a-4f72-a11d-545a696e6461` | `uint32` little-endian |

Las tres características son de lectura y notificación. Al activarse las tres notificaciones se envía una foto completa de los valores; después solo se notifica la característica que cambió.

## Limitación del contador de caídas

El contador vive en RAM y **vuelve a 0 cada vez que la XIAO se reinicia**, igual que el montaje anterior. TzindaApp conserva el último valor recibido como referencia, por lo que después de un reinicio de la placa una caída nueva podría no generar alerta hasta superar el contador anterior. Para evitarlo, no reinicies la pulsera durante el uso; si necesitas que el contador sobreviva reinicios, se puede añadir persistencia en NVS como cambio aparte.

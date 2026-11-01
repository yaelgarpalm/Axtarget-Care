/*
  Tzinda · Pulsera XIAO ESP32S3 Sense
  Sensores: MAX30102 (pulso / SpO2) + MPU6050 (acelerómetro, detección de caídas)
  Enlace: Bluetooth BLE directo con TzindaApp; no usa Wi-Fi ni Python.

  CABLEADO
  --------
  MAX30102 y MPU6050 comparten el mismo bus I2C:
    VIN/VCC -> 3V3
    GND     -> GND
    SDA     -> D4 (GPIO5)
    SCL     -> D5 (GPIO6)
    MPU AD0 -> GND (dirección 0x68)

  El MAX30102 responde en 0x57. Si los módulos no traen pull-ups, coloca
  4,7 kOhm de SDA a 3V3 y de SCL a 3V3 una sola vez para todo el bus.

  PLACA EN ARDUINO IDE
  --------------------
    Seeed Studio XIAO ESP32S3 (o ESP32S3 Dev Module con Flash 8 MB)
    USB CDC On Boot: Enabled
    Monitor Serie: 115200

  BLE
  ---
    Servicio:  6b9f0001-6e2a-4f72-a11d-545a696e6461
    Pulso:     6b9f0002-6e2a-4f72-a11d-545a696e6461  uint16 LE
    SpO2:      6b9f0003-6e2a-4f72-a11d-545a696e6461  uint8
    Caídas:    6b9f0004-6e2a-4f72-a11d-545a696e6461  uint32 LE

  TzindaApp filtra por el UUID del servicio. Al activar las tres
  notificaciones se envía una foto completa de los valores; después solo se
  notifica la característica que cambió. El contador de caídas vive en RAM y
  vuelve a 0 al reiniciar la placa (igual que el montaje anterior).

  La detección de caídas es experimental y no sustituye atención médica.
*/

#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#include "caida.h"

// ------------------------------------------------------------------
// Pines de la XIAO ESP32S3 Sense
// ------------------------------------------------------------------
constexpr uint8_t I2C_SDA = 5;    // D4
constexpr uint8_t I2C_SCL = 6;    // D5
constexpr uint8_t LED_PIN = 21;   // LED de usuario, activo en bajo

// ------------------------------------------------------------------
// Configuración de sensores y tiempos
// ------------------------------------------------------------------
constexpr uint8_t  MAX_ADDR        = 0x57;
constexpr uint8_t  MAX_PART_ID     = 0x15;
constexpr uint8_t  MAX_LED_PA      = 0x3C;    // corriente de cada LED (~12 mA)
constexpr uint32_t MS_POR_MUESTRA  = 10;      // 400 sps con promedio x4 = 100 Hz
constexpr uint32_t FINGER_ON       = 50000;   // IR por encima: hay dedo
constexpr uint32_t FINGER_OFF      = 30000;   // IR por debajo: se quitó el dedo
constexpr uint32_t IR_SATURADO     = 250000;  // cerca del máximo de 18 bits
constexpr uint32_t SETTLE_MS       = 1500;    // asentamiento del filtro con el dedo
constexpr uint32_t MAX_POLL_MS     = 40;      // vaciado de la FIFO
constexpr uint32_t MAX_RETRY_MS    = 2000;    // reintento si el sensor no responde
constexpr uint8_t  MAX_ERR_LIMITE  = 5;       // fallos seguidos antes de darlo por perdido
constexpr uint32_t MAX_STALL_MS    = 1000;    // sin muestras nuevas: se reinició el chip

constexpr uint8_t  MPU_ADDR        = 0x68;
constexpr uint32_t MPU_POLL_MS     = 20;      // 50 muestras por segundo
constexpr uint32_t MPU_RETRY_MS    = 2000;
constexpr uint8_t  MPU_ERR_LIMITE  = 5;

constexpr uint32_t PRINT_MS        = 500;
constexpr uint32_t BLE_SERVICE_MS  = 20;

// Detección de caídas (ver caida.h)
constexpr int32_t  CAIDA_LIBRE_MG        = 500;
constexpr int32_t  CAIDA_IMPACTO_MG      = 2500;
constexpr uint8_t  CAIDA_LIBRE_MIN       = 2;
constexpr uint32_t CAIDA_VENTANA_MS      = 1000;
constexpr uint32_t CAIDA_ASENTAR_MS      = 1000;
constexpr uint32_t CAIDA_QUIETO_MS       = 2000;
constexpr int32_t  CAIDA_QUIETO_RANGO_MG = 300;
constexpr float    CAIDA_GIRO_MIN_DEG    = 35.0f;
constexpr uint32_t CAIDA_AVISO_MS        = 3000;

// SpO2: solo se publica con buena señal durante varias ventanas seguidas
constexpr float    SPO2_A             = 110.0f;
constexpr float    SPO2_B             = 25.0f;
constexpr uint8_t  SPO2_VENTANAS_MIN  = 3;
constexpr float    SPO2_PI_MIN        = 0.05f;
constexpr float    SPO2_PI_MAX        = 20.0f;

// ------------------------------------------------------------------
// UUID y estado BLE
// ------------------------------------------------------------------
static const char *const SERVICE_UUID = "6b9f0001-6e2a-4f72-a11d-545a696e6461";
static const char *const PULSE_UUID   = "6b9f0002-6e2a-4f72-a11d-545a696e6461";
static const char *const SPO2_UUID    = "6b9f0003-6e2a-4f72-a11d-545a696e6461";
static const char *const FALLS_UUID   = "6b9f0004-6e2a-4f72-a11d-545a696e6461";
static const char *const DEVICE_NAME  = "Tzinda XIAO";

static BLECharacteristic *pulseChar = nullptr;
static BLECharacteristic *spo2Char = nullptr;
static BLECharacteristic *fallsChar = nullptr;
static BLE2902 *pulseCccd = nullptr;
static BLE2902 *spo2Cccd = nullptr;
static BLE2902 *fallsCccd = nullptr;

static volatile bool bleConnected = false;
static volatile bool snapshotSent = false;
static bool wasSubscribed = false;
static volatile uint16_t lastBpmSent = 0xFFFF;
static volatile uint8_t lastSpo2Sent = 0xFF;
static volatile uint32_t lastFallsSent = 0xFFFFFFFF;

// ------------------------------------------------------------------
// Códigos de error de diagnóstico
// ------------------------------------------------------------------
enum : int32_t {
  ERR_OK           = 0,
  ERR_SIN_ACK      = 4,
  ERR_ID           = 5,
  ERR_LECTURA      = 6,
  ERR_SIN_MUESTRAS = 7,
};

static const char *errText(int32_t e, bool mpu) {
  switch (e) {
    case ERR_OK:
      return "ok";
    case ERR_SIN_ACK:
      return mpu ? "no responde en 0x68: revisa 3V3, GND, AD0=GND, SDA=D4, SCL=D5"
                 : "no responde en 0x57: revisa 3V3, GND, SDA=D4, SCL=D5";
    case ERR_ID:
      return mpu ? "WHO_AM_I no corresponde a un MPU6050"
                 : "el Part ID no es 0x15";
    case ERR_LECTURA:
      return "comunicación I2C inestable: revisa contactos, cables y pull-ups";
    case ERR_SIN_MUESTRAS:
      return "dejó de entregar muestras: revisa alimentación o reinicio";
  }
  return "?";
}

// ------------------------------------------------------------------
// Estado compartido entre el loop y BLE
// ------------------------------------------------------------------
static int32_t  g_bpm = 0;
static int32_t  g_spo2 = 0;
static int32_t  g_finger = 0;
static uint32_t g_ir = 0;
static uint32_t g_red = 0;
static int32_t  g_maxSat = 0;
static int32_t  g_maxOk = 0;
static int32_t  g_maxErr = ERR_SIN_ACK;
static int32_t  g_maxPartId = 0;
static uint32_t g_maxOvf = 0;
static int32_t  g_mpuOk = 0;
static int32_t  g_mpuErr = ERR_SIN_ACK;
static int32_t  g_mpuId = 0;
static int32_t  g_axMg = 0;
static int32_t  g_ayMg = 0;
static int32_t  g_azMg = 0;
static int32_t  g_magMg = 0;
static int32_t  g_caida = 0;
static uint32_t g_caidas = 0;
static uint32_t g_descartes = 0;

// El monitor USB puede no estar abierto; no bloquear el loop si no hay host.
static void logLine(const char *text) {
  if (Serial) Serial.println(text);
}

// ------------------------------------------------------------------
// I2C por hardware (Wire) en D4/D5
// ------------------------------------------------------------------
static bool regWrite(uint8_t addr, uint8_t reg, uint8_t val) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write(val);
  return Wire.endTransmission() == 0;
}

static bool regRead(uint8_t addr, uint8_t reg, uint8_t *buf, uint8_t n) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;

  const size_t got = Wire.requestFrom(addr, (size_t)n);
  if (got != (size_t)n) return false;
  for (uint8_t i = 0; i < n; i++) {
    if (!Wire.available()) return false;
    buf[i] = Wire.read();
  }
  return true;
}

static bool probe(uint8_t addr) {
  Wire.beginTransmission(addr);
  return Wire.endTransmission() == 0;
}

// ------------------------------------------------------------------
// MAX30102
// ------------------------------------------------------------------
enum : uint8_t {
  REG_FIFO_WR     = 0x04,
  REG_FIFO_OVF    = 0x05,
  REG_FIFO_RD     = 0x06,
  REG_FIFO_DATA   = 0x07,
  REG_FIFO_CFG    = 0x08,
  REG_MODE_CFG    = 0x09,
  REG_SPO2_CFG    = 0x0A,
  REG_LED1_PA     = 0x0C,   // rojo
  REG_LED2_PA     = 0x0D,   // infrarrojo
  REG_PART_ID     = 0xFF
};

constexpr uint8_t FIFO_CFG = 0x40 | 0x10 | 0x0F;  // promedio x4, rollover, aviso a 17 muestras
constexpr uint8_t SPO2_CFG = 0x20 | 0x0C | 0x03;  // 4096 nA, 400 sps, 411 us (18 bits)
constexpr uint8_t MODE_SPO2 = 0x03;               // rojo + IR

static int32_t maxBegin() {
  if (!probe(MAX_ADDR)) return ERR_SIN_ACK;

  uint8_t id = 0;
  if (!regRead(MAX_ADDR, REG_PART_ID, &id, 1)) return ERR_LECTURA;
  g_maxPartId = id;
  if (id != MAX_PART_ID) return ERR_ID;

  if (!regWrite(MAX_ADDR, REG_MODE_CFG, 0x40)) return ERR_LECTURA;   // reset
  uint8_t m = 0x40;
  for (uint8_t i = 0; i < 20 && (m & 0x40); i++) {
    delay(10);
    if (!regRead(MAX_ADDR, REG_MODE_CFG, &m, 1)) m = 0x40;   // durante el reset puede no contestar
  }
  if (m & 0x40) return ERR_LECTURA;

  bool ok = regWrite(MAX_ADDR, REG_FIFO_CFG, FIFO_CFG)
         && regWrite(MAX_ADDR, REG_SPO2_CFG, SPO2_CFG)
         && regWrite(MAX_ADDR, REG_LED1_PA, MAX_LED_PA)
         && regWrite(MAX_ADDR, REG_LED2_PA, MAX_LED_PA)
         && regWrite(MAX_ADDR, REG_FIFO_WR, 0)
         && regWrite(MAX_ADDR, REG_FIFO_OVF, 0)
         && regWrite(MAX_ADDR, REG_FIFO_RD, 0)
         && regWrite(MAX_ADDR, REG_MODE_CFG, MODE_SPO2);
  if (!ok) return ERR_LECTURA;

  uint8_t chk[2];
  if (!regRead(MAX_ADDR, REG_MODE_CFG, chk, 2)) return ERR_LECTURA;
  if ((chk[0] & 0x07) != MODE_SPO2 || chk[1] != SPO2_CFG) return ERR_LECTURA;
  return ERR_OK;
}

// Procesamiento de señal (100 muestras/s). Los tiempos se cuentan en
// muestras, no con millis(), porque la FIFO se lee en ráfagas.
static float irDc = 0, redDc = 0, irLp = 0, env = 0, prevSig = 0;
static float irSq = 0, redSq = 0, irSum = 0, redSum = 0;
static uint16_t winN = 0;
static uint32_t muestras = 0;
static uint32_t lastBeatT = 0;
static uint32_t settleUntil = 0;
static uint32_t ibi[5];
static uint8_t ibiN = 0, ibiIdx = 0, ibiRechazos = 0;
static bool dcInit = false;
static float spo2Smooth = 0;
static uint8_t spo2Validas = 0;

static void resetAlgo() {
  dcInit = false; irLp = 0; env = 0; prevSig = 0;
  irSq = redSq = irSum = redSum = 0; winN = 0;
  lastBeatT = 0;
  ibiN = 0; ibiIdx = 0; ibiRechazos = 0;
  spo2Smooth = 0;
  spo2Validas = 0;
  g_bpm = 0;
  g_spo2 = 0;
}

static uint32_t ibiMediana() {
  uint32_t v[5];
  for (uint8_t i = 0; i < ibiN; i++) v[i] = ibi[i];
  for (uint8_t i = 1; i < ibiN; i++) {
    uint32_t x = v[i];
    int8_t j = i - 1;
    while (j >= 0 && v[j] > x) { v[j + 1] = v[j]; j--; }
    v[j + 1] = x;
  }
  return v[ibiN / 2];
}

static void addIbi(uint32_t d) {
  if (ibiN >= 3) {
    uint32_t med = ibiMediana();
    if (d < med * 2 / 3 || d > med * 3 / 2) {
      if (++ibiRechazos < 3) return;
      ibiN = 0; ibiIdx = 0;
    }
  }
  ibiRechazos = 0;
  ibi[ibiIdx] = d;
  ibiIdx = (ibiIdx + 1) % 5;
  if (ibiN < 5) ibiN++;
  if (ibiN >= 3) {
    uint32_t med = ibiMediana();
    g_bpm = (int32_t)((60000UL + med / 2) / med);
  }
}

static void processSample(uint32_t red, uint32_t ir) {
  uint32_t t = (++muestras) * MS_POR_MUESTRA;
  g_ir = ir;
  g_red = red;
  g_maxSat = (ir >= IR_SATURADO) ? 1 : 0;

  if (!g_finger) {
    if (ir < FINGER_ON) return;
    resetAlgo();
    g_finger = 1;
    settleUntil = t + SETTLE_MS;
  } else if (ir < FINGER_OFF) {
    resetAlgo();
    g_finger = 0;
    return;
  }

  if (!dcInit) { irDc = ir; redDc = red; dcInit = true; }
  irDc  += 0.02f * (ir  - irDc);
  redDc += 0.02f * (red - redDc);

  float irAc  = ir  - irDc;
  float redAc = red - redDc;

  irLp += 0.25f * (irAc - irLp);
  float sig = -irLp;

  if ((int32_t)(t - settleUntil) < 0) { prevSig = sig; return; }

  float decayed = env * 0.995f;
  env = (sig > decayed) ? sig : decayed;
  float thr = 0.5f * env;

  if (env > 30.0f && sig > thr && prevSig <= thr && (t - lastBeatT) > 300) {
    if (lastBeatT != 0) {
      uint32_t d = t - lastBeatT;
      if (d >= 333 && d <= 1500) addIbi(d);
    }
    lastBeatT = t;
  }
  if (lastBeatT && (t - lastBeatT) > 3000) {
    lastBeatT = 0;
    ibiN = 0; ibiIdx = 0;
    spo2Smooth = 0;
    spo2Validas = 0;
    g_bpm = 0;
    g_spo2 = 0;
  }
  prevSig = sig;

  irSq += irAc * irAc;   redSq += redAc * redAc;
  irSum += irDc;         redSum += redDc;
  if (++winN >= 200) {
    float irRms = sqrtf(irSq / winN), redRms = sqrtf(redSq / winN);
    float irMean = irSum / winN, redMean = redSum / winN;
    float pi = (irMean > 0) ? 100.0f * irRms / irMean : 0;
    bool valida = irRms > 20 && redRms > 20 && g_bpm > 0 && !g_maxSat
               && pi >= SPO2_PI_MIN && pi <= SPO2_PI_MAX;
    float s = 0;
    if (valida) {
      float R = (redRms / redMean) / (irRms / irMean);
      s = SPO2_A - SPO2_B * R;
      if (s > 100.0f) s = 100.0f;
      valida = s >= 70.0f;
    }
    if (valida) {
      spo2Smooth = (spo2Smooth == 0) ? s : 0.7f * spo2Smooth + 0.3f * s;
      if (spo2Validas < 255) spo2Validas++;
      g_spo2 = (spo2Validas >= SPO2_VENTANAS_MIN) ? (int32_t)(spo2Smooth + 0.5f) : 0;
    } else {
      spo2Validas = 0;
      spo2Smooth = 0;
      g_spo2 = 0;
    }
    irSq = redSq = irSum = redSum = 0; winN = 0;
  }
}

static uint32_t maxLastSampleMs = 0;

static bool maxPoll() {
  uint8_t p[3];   // FIFO_WR, OVF y FIFO_RD son registros seguidos
  if (!regRead(MAX_ADDR, REG_FIFO_WR, p, 3)) return false;
  uint8_t n = (p[0] - p[2]) & 0x1F;
  if (p[1]) {
    g_maxOvf += p[1];
    if (n == 0) n = 32;   // FIFO llena: punteros iguales
  }
  while (n) {
    uint8_t k = (n > 8) ? 8 : n;   // hasta 8 muestras por lectura (48 bytes)
    uint8_t d[8 * 6];
    if (!regRead(MAX_ADDR, REG_FIFO_DATA, d, k * 6)) return false;
    bool todoCero = true;
    for (uint8_t i = 0; i < k * 6 && todoCero; i++) todoCero = (d[i] == 0);
    if (todoCero) return false;
    maxLastSampleMs = millis();
    for (uint8_t i = 0; i < k; i++) {
      const uint8_t *s = &d[i * 6];
      uint32_t red = (((uint32_t)s[0] << 16) | ((uint32_t)s[1] << 8) | s[2]) & 0x3FFFF;
      uint32_t ir  = (((uint32_t)s[3] << 16) | ((uint32_t)s[4] << 8) | s[5]) & 0x3FFFF;
      processSample(red, ir);
    }
    n -= k;
  }
  return true;
}

static void maxLost() {
  resetAlgo();
  g_finger = 0;
  g_ir = 0;
  g_red = 0;
  g_maxSat = 0;
}

static void reportMax(int32_t e) {
  char b[160];
  if (e == ERR_OK) {
    snprintf(b, sizeof b, "MAX30102 listo (SDA=D4/GPIO5, SCL=D5/GPIO6)");
  } else if (e == ERR_ID) {
    snprintf(b, sizeof b, "MAX30102 no disponible: Part ID=0x%02X, se esperaba 0x15", (unsigned)g_maxPartId);
  } else {
    snprintf(b, sizeof b, "MAX30102 no disponible: %s", errText(e, false));
  }
  logLine(b);
}

static void maxFail(int32_t e) {
  g_maxOk = 0;
  g_maxErr = e;
  maxLost();
  reportMax(e);
}

// ------------------------------------------------------------------
// MPU6050 y detección de caídas
// ------------------------------------------------------------------
enum : uint8_t {
  MPU_REG_SMPLRT   = 0x19,
  MPU_REG_CONFIG   = 0x1A,
  MPU_REG_ACCEL    = 0x1C,
  MPU_REG_ACCEL_X  = 0x3B,
  MPU_REG_PWR1     = 0x6B,
  MPU_REG_WHO_AM_I = 0x75
};

static bool mpuIdValido(uint8_t id) {
  return id == 0x68 || id == 0x70 || id == 0x72;   // MPU6050 y clones comunes
}

static int32_t mpuBegin() {
  if (!probe(MPU_ADDR)) return ERR_SIN_ACK;

  uint8_t id = 0;
  if (!regRead(MPU_ADDR, MPU_REG_WHO_AM_I, &id, 1)) return ERR_LECTURA;
  g_mpuId = id;
  if (!mpuIdValido(id)) return ERR_ID;

  bool ok = regWrite(MPU_ADDR, MPU_REG_PWR1, 0x80);   // reset
  delay(100);
  ok = ok && regWrite(MPU_ADDR, MPU_REG_PWR1, 0x01)
          && regWrite(MPU_ADDR, MPU_REG_SMPLRT, 0x07)
          && regWrite(MPU_ADDR, MPU_REG_CONFIG, 0x04)  // filtro ~21 Hz
          && regWrite(MPU_ADDR, MPU_REG_ACCEL, 0x10);  // ±8 g
  if (!ok) return ERR_LECTURA;

  uint8_t chk = 0;
  if (!regRead(MPU_ADDR, MPU_REG_ACCEL, &chk, 1) || chk != 0x10) return ERR_LECTURA;
  return ERR_OK;
}

static CaidaConfig caidaConfig() {
  CaidaConfig c;
  c.libreMg = CAIDA_LIBRE_MG;
  c.libreMin = CAIDA_LIBRE_MIN;
  c.impactoMg = CAIDA_IMPACTO_MG;
  c.ventanaMs = CAIDA_VENTANA_MS;
  c.asentarMs = CAIDA_ASENTAR_MS;
  c.quietoMs = CAIDA_QUIETO_MS;
  c.quietoRangoMg = CAIDA_QUIETO_RANGO_MG;
  c.giroMinDeg = CAIDA_GIRO_MIN_DEG;
  return c;
}

static DetectorCaidas detector(caidaConfig());
static uint32_t caidaT = 0;

static void caidaReset() {
  detector.reiniciar();
  g_caida = 0;
}

static void caidaProcesar(int32_t x, int32_t y, int32_t z, uint32_t now) {
  char b[160];
  switch (detector.procesar(x, y, z, now)) {
    case CaidaResultado::CAIDA: {
      g_caida = 1;
      g_caidas = g_caidas + 1;
      caidaT = now;
      int32_t p = detector.ultimoPicoMg();
      snprintf(b, sizeof b, "!!! CAIDA DETECTADA !!! golpe %d.%02d g, giro %d grados, quieto después",
               (int)(p / 1000), (int)((p % 1000) / 10), (int)detector.ultimoGiroDeg());
      logLine(b);
      break;
    }
    case CaidaResultado::DESCARTADA_MOVIMIENTO:
      g_descartes = g_descartes + 1;
      snprintf(b, sizeof b, "Golpe descartado: siguió moviéndose (variación %d mg)", (int)detector.ultimoRangoMg());
      logLine(b);
      break;
    case CaidaResultado::DESCARTADA_POSTURA:
      g_descartes = g_descartes + 1;
      snprintf(b, sizeof b, "Golpe descartado: misma postura (giro %d grados)", (int)detector.ultimoGiroDeg());
      logLine(b);
      break;
    case CaidaResultado::NADA:
      break;
  }
  if (g_caida && now - caidaT > CAIDA_AVISO_MS) g_caida = 0;
}

static bool mpuPoll() {
  uint8_t d[6];
  if (!regRead(MPU_ADDR, MPU_REG_ACCEL_X, d, 6)) return false;
  bool todoCero = true;
  for (uint8_t i = 0; i < 6 && todoCero; i++) todoCero = (d[i] == 0);
  if (todoCero) return false;

  int16_t rx = (int16_t)((d[0] << 8) | d[1]);
  int16_t ry = (int16_t)((d[2] << 8) | d[3]);
  int16_t rz = (int16_t)((d[4] << 8) | d[5]);
  int32_t x = ((int32_t)rx * 1000) / 4096;   // milli-g
  int32_t y = ((int32_t)ry * 1000) / 4096;
  int32_t z = ((int32_t)rz * 1000) / 4096;
  int32_t mag = (int32_t)(sqrtf((float)x * x + (float)y * y + (float)z * z) + 0.5f);

  g_axMg = x; g_ayMg = y; g_azMg = z; g_magMg = mag;
  caidaProcesar(x, y, z, millis());
  return true;
}

static void mpuLost() {
  caidaReset();
  g_axMg = g_ayMg = g_azMg = g_magMg = 0;
}

static void reportMpu(int32_t e) {
  char b[160];
  if (e == ERR_OK) {
    snprintf(b, sizeof b, "MPU6050 listo (SDA=D4/GPIO5, SCL=D5/GPIO6)");
  } else if (e == ERR_ID) {
    snprintf(b, sizeof b, "MPU6050 no disponible: WHO_AM_I=0x%02X, se esperaba 0x68", (unsigned)g_mpuId);
  } else {
    snprintf(b, sizeof b, "MPU6050 no disponible: %s", errText(e, true));
  }
  logLine(b);
}

static void mpuFail(int32_t e) {
  g_mpuOk = 0;
  g_mpuErr = e;
  mpuLost();
  reportMpu(e);
}

// ------------------------------------------------------------------
// BLE: mismo servicio y características que espera TzindaApp
// ------------------------------------------------------------------
static uint16_t currentBpm() {
  if (g_bpm <= 0) return 0;
  return (g_bpm > 65535) ? 65535 : (uint16_t)g_bpm;
}

static uint8_t currentSpo2() {
  if (g_spo2 <= 0) return 0;
  return (g_spo2 > 100) ? 100 : (uint8_t)g_spo2;
}

static bool allNotificationsEnabled() {
  return pulseCccd && spo2Cccd && fallsCccd
      && pulseCccd->getNotifications()
      && spo2Cccd->getNotifications()
      && fallsCccd->getNotifications();
}

static void publishChanges(bool force) {
  if (!bleConnected) return;

  const uint16_t bpm = currentBpm();
  const uint8_t spo2 = currentSpo2();
  const uint32_t falls = g_caidas;

  if (force || bpm != lastBpmSent) {
    uint8_t d[2] = { (uint8_t)(bpm & 0xFF), (uint8_t)(bpm >> 8) };
    pulseChar->setValue(d, sizeof d);
    pulseChar->notify();
    lastBpmSent = bpm;
  }
  if (force || spo2 != lastSpo2Sent) {
    uint8_t d[1] = { spo2 };
    spo2Char->setValue(d, sizeof d);
    spo2Char->notify();
    lastSpo2Sent = spo2;
  }
  if (force || falls != lastFallsSent) {
    uint8_t d[4] = {
      (uint8_t)(falls & 0xFF),
      (uint8_t)((falls >> 8) & 0xFF),
      (uint8_t)((falls >> 16) & 0xFF),
      (uint8_t)((falls >> 24) & 0xFF),
    };
    fallsChar->setValue(d, sizeof d);
    fallsChar->notify();
    lastFallsSent = falls;
  }
}

static void serviceBle() {
  if (!bleConnected) return;

  const bool subscribed = allNotificationsEnabled();
  if (subscribed && !wasSubscribed) snapshotSent = false;
  wasSubscribed = subscribed;
  if (!subscribed) return;

  if (!snapshotSent) {
    publishChanges(true);
    snapshotSent = true;
  } else {
    publishChanges(false);
  }
}

class TzindaServerCallbacks : public BLEServerCallbacks {
 public:
  void onConnect(BLEServer *server) override {
    (void)server;
    bleConnected = true;
    snapshotSent = false;
    wasSubscribed = false;
    lastBpmSent = 0xFFFF;
    lastSpo2Sent = 0xFF;
    lastFallsSent = 0xFFFFFFFF;
    digitalWrite(LED_PIN, LOW);
    logLine("BLE: teléfono conectado.");
  }

  void onDisconnect(BLEServer *server) override {
    (void)server;
    bleConnected = false;
    snapshotSent = false;
    wasSubscribed = false;
    lastBpmSent = 0xFFFF;
    lastSpo2Sent = 0xFF;
    lastFallsSent = 0xFFFFFFFF;
    digitalWrite(LED_PIN, HIGH);
    logLine("BLE: teléfono desconectado; anunciando de nuevo.");
    BLEDevice::startAdvertising();
  }
};

static void setupBle() {
  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new TzindaServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);

  pulseChar = service->createCharacteristic(
      PULSE_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  spo2Char = service->createCharacteristic(
      SPO2_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  fallsChar = service->createCharacteristic(
      FALLS_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);

  pulseCccd = new BLE2902();
  spo2Cccd = new BLE2902();
  fallsCccd = new BLE2902();
  pulseChar->addDescriptor(pulseCccd);
  spo2Char->addDescriptor(spo2Cccd);
  fallsChar->addDescriptor(fallsCccd);

  uint8_t zeroPulse[2] = { 0, 0 };
  uint8_t zeroSpo2[1] = { 0 };
  uint8_t zeroFalls[4] = { 0, 0, 0, 0 };
  pulseChar->setValue(zeroPulse, sizeof zeroPulse);
  spo2Char->setValue(zeroSpo2, sizeof zeroSpo2);
  fallsChar->setValue(zeroFalls, sizeof zeroFalls);

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  BLEDevice::startAdvertising();

  logLine("BLE: anunciando como Tzinda XIAO.");
}

// ------------------------------------------------------------------
// Diagnóstico por Monitor Serie
// ------------------------------------------------------------------
static void printStatus() {
  if (!Serial) return;

  char mp[160], mx[160], b[360];

  if (!g_mpuOk) {
    snprintf(mp, sizeof mp, "ERROR %s", errText(g_mpuErr, true));
  } else {
    int32_t m = g_magMg;
    snprintf(mp, sizeof mp, "|a|=%d.%02dg%s", (int)(m / 1000), (int)((m % 1000) / 10),
             g_caida ? " >>> CAIDA <<<" : (detector.analizando() ? " (analizando golpe...)" : ""));
  }

  if (!g_maxOk) {
    snprintf(mx, sizeof mx, "ERROR %s", errText(g_maxErr, false));
  } else if (!g_finger) {
    snprintf(mx, sizeof mx, "coloca el dedo IR=%lu", (unsigned long)g_ir);
  } else if (g_bpm == 0) {
    snprintf(mx, sizeof mx, "midiendo... IR=%lu%s", (unsigned long)g_ir,
             g_maxSat ? " (saturado: baja MAX_LED_PA)" : "");
  } else {
    char sp[8];
    if (g_spo2 > 0) snprintf(sp, sizeof sp, "%d%%", (int)g_spo2);
    else            snprintf(sp, sizeof sp, "--");
    snprintf(mx, sizeof mx, "BPM=%d SpO2=%s IR=%lu%s", (int)g_bpm, sp,
             (unsigned long)g_ir, g_maxSat ? " (saturado: baja MAX_LED_PA)" : "");
  }

  snprintf(b, sizeof b, "MPU6050: %s | MAX30102: %s | caídas=%lu", mp, mx, (unsigned long)g_caidas);
  Serial.println(b);
}

// ------------------------------------------------------------------
void setup() {
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);   // LED apagado hasta que haya conexión BLE

  Serial.begin(115200);
  delay(200);

  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setClock(100000);

  logLine("Tzinda XIAO ESP32S3 Sense · iniciando");

  g_maxErr = maxBegin();
  g_maxOk = (g_maxErr == ERR_OK) ? 1 : 0;
  maxLastSampleMs = millis();
  reportMax(g_maxErr);

  g_mpuErr = mpuBegin();
  g_mpuOk = (g_mpuErr == ERR_OK) ? 1 : 0;
  reportMpu(g_mpuErr);

  setupBle();
}

void loop() {
  static uint32_t lastPrint = 0;
  static uint32_t lastPoll = 0;
  static uint32_t lastRetry = 0;
  static uint8_t errSeguidos = 0;
  static uint32_t lastMpuPoll = 0;
  static uint32_t lastMpuRetry = 0;
  static uint8_t mpuErrSeguidos = 0;
  static uint32_t lastBle = 0;
  const uint32_t now = millis();

  if (g_maxOk) {
    if (now - lastPoll >= MAX_POLL_MS) {
      lastPoll = now;
      if (!maxPoll()) {
        if (++errSeguidos >= MAX_ERR_LIMITE) {
          errSeguidos = 0;
          maxFail(ERR_LECTURA);
          lastRetry = now;
        }
      } else {
        errSeguidos = 0;
        if (millis() - maxLastSampleMs > MAX_STALL_MS) {
          maxFail(ERR_SIN_MUESTRAS);
          lastRetry = now;
        }
      }
    }
  } else if (now - lastRetry >= MAX_RETRY_MS) {
    lastRetry = now;
    int32_t e = maxBegin();
    if (e != g_maxErr) reportMax(e);
    g_maxErr = e;
    g_maxOk = (e == ERR_OK) ? 1 : 0;
    maxLastSampleMs = millis();
  }

  if (g_mpuOk) {
    if (now - lastMpuPoll >= MPU_POLL_MS) {
      lastMpuPoll = now;
      if (!mpuPoll()) {
        if (++mpuErrSeguidos >= MPU_ERR_LIMITE) {
          mpuErrSeguidos = 0;
          mpuFail(ERR_LECTURA);
          lastMpuRetry = now;
        }
      } else {
        mpuErrSeguidos = 0;
      }
    }
  } else if (now - lastMpuRetry >= MPU_RETRY_MS) {
    lastMpuRetry = now;
    int32_t e = mpuBegin();
    if (e != g_mpuErr) reportMpu(e);
    g_mpuErr = e;
    g_mpuOk = (e == ERR_OK) ? 1 : 0;
  }

  if (now - lastBle >= BLE_SERVICE_MS) {
    lastBle = now;
    serviceBle();
  }

  if (now - lastPrint >= PRINT_MS) {
    lastPrint = now;
    printStatus();
  }
}

/**
 * Kóatl Safe — Firmware v2
 * Hardware: Seeed Studio XIAO ESP32-C3
 * Sensor: Microswitch mecánico 6x6mm THT (pin D7)
 * Motor: Vibrador moneda 8mm (pin D8)
 * BLE: Notificación "SOS" tras mantener 5 segundos
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ── Pines ─────────────────────────────────────────────
#define SWITCH_PIN   D7   // Microswitch mecánico
#define MOTOR_PIN    D8   // Motor de vibración moneda

// ── BLE UUIDs (mismos que v1, app no necesita cambios) ─
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// ── Tiempos ───────────────────────────────────────────
const unsigned long TIEMPO_ACTIVACION = 5000;  // 5 seg para activar SOS
const unsigned long TIEMPO_DEBOUNCE   = 300;   // 300ms para confirmar pulsación real
const unsigned long TIEMPO_VIBRACION  = 1000;  // 1 seg de vibración de confirmación

// ── Estado BLE ────────────────────────────────────────
BLEServer*         pServer         = NULL;
BLECharacteristic* pCharacteristic = NULL;
bool dispositivoConectado = false;

// ── Estado del switch ─────────────────────────────────
unsigned long tiempoInicio   = 0;
unsigned long tiempoDebounce = 0;
bool contando        = false;
bool alertaActivada  = false;
bool debounceActivo  = false;

// ── Callbacks BLE ─────────────────────────────────────
class MisCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    dispositivoConectado = true;
    Serial.println("Telefono conectado!");
  }
  void onDisconnect(BLEServer* pServer) {
    dispositivoConectado = false;
    Serial.println("Telefono desconectado");
    BLEDevice::startAdvertising();
  }
};

// ── Vibración de confirmación ─────────────────────────
void vibrar(unsigned long duracionMs) {
  digitalWrite(MOTOR_PIN, HIGH);
  delay(duracionMs);
  digitalWrite(MOTOR_PIN, LOW);
}

// ── Setup ─────────────────────────────────────────────
void setup() {
  Serial.begin(115200);

  // Switch con pull-up interno: reposo = HIGH, presionado = LOW
  pinMode(SWITCH_PIN, INPUT);

  // Motor de vibración como salida
  pinMode(MOTOR_PIN, OUTPUT);
  digitalWrite(MOTOR_PIN, LOW);

  // Vibración corta de arranque (confirma que el dispositivo encendió)
  vibrar(200);

  // Inicializar BLE
  BLEDevice::init("KoatlSafe");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MisCallbacks());

  BLEService* pService = pServer->createService(SERVICE_UUID);
  pCharacteristic = pService->createCharacteristic(
    CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_READ |
    BLECharacteristic::PROPERTY_NOTIFY
  );
  pCharacteristic->addDescriptor(new BLE2902());
  pService->start();

  BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  BLEDevice::startAdvertising();

  Serial.println("Koatl Safe lista. Esperando conexion BLE...");
}

// ── Loop ──────────────────────────────────────────────
void loop() {
  // Con INPUT_PULLUP: LOW = presionado, HIGH = suelto
  int estado = digitalRead(SWITCH_PIN);

  if (estado == HIGH) {  // Botón presionado

    // Paso 1: iniciar debounce al detectar primera pulsación
    if (!debounceActivo && !contando) {
      debounceActivo = true;
      tiempoDebounce = millis();
    }

    // Paso 2: confirmar pulsación real después de 300ms (anti-rebote)
    if (debounceActivo && !contando) {
      if (millis() - tiempoDebounce >= TIEMPO_DEBOUNCE) {
        contando    = true;
        tiempoInicio = millis();
        Serial.println("Pulsacion confirmada. Manteniendo...");
      }
    }

    // Paso 3: cuenta regresiva de 5 segundos
    if (contando) {
      unsigned long tiempoPresionado = millis() - tiempoInicio;
      int segundos = 5 - (tiempoPresionado / 1000);
      if (segundos >= 0) {
        Serial.print("Faltan: ");
        Serial.print(segundos);
        Serial.println(" seg");
      }

      // Paso 4: activar SOS al llegar a 5 segundos
      if (tiempoPresionado >= TIEMPO_ACTIVACION && !alertaActivada) {
        alertaActivada = true;
        Serial.println("¡ALERTA SOS ACTIVADA!");

        // Vibración de confirmación háptica
        vibrar(TIEMPO_VIBRACION);

        // Enviar señal BLE si hay dispositivo conectado
        if (dispositivoConectado) {
          pCharacteristic->setValue("SOS");
          pCharacteristic->notify();
          Serial.println("Senal BLE enviada!");
        } else {
          Serial.println("Sin conexion BLE — alerta local solamente");
        }
      }
    }

  } else {  // Botón suelto (HIGH con pull-up)

    if (contando && !alertaActivada) {
      Serial.println("Soltado antes de tiempo — cancelado");
    }
    debounceActivo = false;
    contando       = false;
    alertaActivada = false;
  }

  delay(100);
}

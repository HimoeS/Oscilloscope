#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <esp_adc_cal.h>

// Địa chỉ UUID dịch vụ
#define SERVICE_UUID "000000ff-0000-1000-8000-00805f9b34fb"
// Địa chỉ UUID đặc trưng
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// Thông số sóng sine
float amplitude = 0.75;  // Amplitude (độ lớn) của sóng sine (đưa về nửa giá trị ADC)
float frequency = 1.0;   // Tần số của sóng sine (Hz)

// Thông số xung vuông
float squareAmplitude = 1.65;  // Amplitude (độ lớn) của xung vuông (đưa về nửa giá trị ADC)
float squareFrequency = 1.0;   // Tần số của xung vuông (Hz)

// Chân kết nối
int sinePin = 25;
int squarePin = 26;

BLEServer* pServer;
BLEService* pService;
BLECharacteristic* pCharacteristic;

bool deviceConnected = false; // Biến để theo dõi thiết bị có kết nối hay không
const int sensorPin = 33;

class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println("Thiết bị đã kết nối");
    };

    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println("Thiết bị đã ngắt kết nối");
      delay(1000);
    }
};

void setup() {
  Serial.begin(115200);

  pinMode(sinePin, OUTPUT);
  pinMode(squarePin, OUTPUT);
  pinMode(sensorPin, INPUT);
  
  Serial.println("BLE preparing ...");
  BLEDevice::init("ESP32"); // Khởi tạo Bluetooth với tên thiết bị là "ESP32"
  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  
  pService = pServer->createService(SERVICE_UUID);
  pCharacteristic = pService->createCharacteristic(
    CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_READ |
    BLECharacteristic::PROPERTY_WRITE
  );

  // Thiết lập giá trị mặc định cho Characteristic
  pCharacteristic->setValue("Hello, this is ESP32!");

  pService->start();
  // Bắt đầu dịch vụ của Bluetooth
  BLEAdvertising* pAdvertising = pServer->getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->start();
  Serial.println("Bluetooth đang phát...");
}

void loop() {
  unsigned long currentTime = millis();

  // Giá trị sóng sine
  float sineValue = amplitude * sin(2 * PI * frequency * currentTime / 1000.0) + amplitude;

  // Giá trị xung vuông
  float squareValue = squareWave(currentTime, squareFrequency) + squareAmplitude;

  // Gửi giá trị đến chân 25 và 26
  analogWrite(sinePin, map(sineValue, 0, 2 * amplitude, 0, 255));
  analogWrite(squarePin, map(squareValue, 0, 2 * squareAmplitude, 0, 255));

  // In giá trị của sóng sine và xung vuông
  Serial.print(sineValue);  // In giá trị sóng sine
  Serial.print('\t');  // Tab để phân tách giữa sóng sine và xung vuông
  Serial.print(squareValue);  // In giá trị xung vuông

  double sensorValue = analogReadAdjusted(sensorPin);
  if (deviceConnected) {
    // Gửi dữ liệu khi có kết nối Bluetooth
    uint8_t byteArray[sizeof(double)];
    memcpy(byteArray, &sensorValue, sizeof(double));
    sensorValue = (sensorValue / 4096) * 3.3;
    pCharacteristic->setValue((uint8_t*)&sensorValue, sizeof(sensorValue));
    Serial.print('\t');
    Serial.println(sensorValue);
    pCharacteristic->notify(); // Gửi thông báo cho thiết bị đã kết nối
  }
  Serial.println();  // Xuống dòng để tạo dòng mới cho Plotter
  delay(100);
}

float squareWave(unsigned long t, float frequency) {
  float period = 1000.0 / frequency;  // Chu kỳ của xung vuông (ms)
  return (t % (unsigned long)period < period / 2) ? squareAmplitude : -squareAmplitude;
}

double analogReadAdjusted(byte pinNumber){

  // Specify the adjustment factors.
  const double f1 = 1.7111361460487501e+001;
  const double f2 = 4.2319467860421662e+000;
  const double f3 = -1.9077375643188468e-002;
  const double f4 = 5.4338055402459246e-005;
  const double f5 = -8.7712931081088873e-008;
  const double f6 = 8.7526709101221588e-011;
  const double f7 = -5.6536248553232152e-014;
  const double f8 = 2.4073049082147032e-017;
  const double f9 = -6.7106284580950781e-021;
  const double f10 = 1.1781963823253708e-024;
  const double f11 = -1.1818752813719799e-028;
  const double f12 = 5.1642864552256602e-033;

  // Specify the number of loops for one measurement.
  const int loops = 40;

  // Specify the delay between the loops.
  const int loopDelay = 1;

  // Initialize the used variables.
  int counter = 1;
  int inputValue = 0;
  double totalInputValue = 0;
  double averageInputValue = 0;

  // Loop to get the average of different analog values.
  for (counter = 1; counter <= loops; counter++) {

    // Read the analog value.
    inputValue = analogRead(pinNumber);

    // Add the analog value to the total.
    totalInputValue += inputValue;

    // Wait some time after each loop.
    delay(loopDelay);
  }

  // Calculate the average input value.
  averageInputValue = totalInputValue / loops;

  // Calculate and return the adjusted input value.
  return f1 + f2 * pow(averageInputValue, 1) + f3 * pow(averageInputValue, 2) + f4 * pow(averageInputValue, 3) + f5 * pow(averageInputValue, 4) + f6 * pow(averageInputValue, 5) + f7 * pow(averageInputValue, 6) + f8 * pow(averageInputValue, 7) + f9 * pow(averageInputValue, 8) + f10 * pow(averageInputValue, 9) + f11 * pow(averageInputValue, 10) + f12 * pow(averageInputValue, 11);
}
// HachimoPhone 云台固件（ESP32-C3 mini）
//
// 手机通过 Type-C OTG 直接接到这块板子的 USB 口，手机 App 以 115200 往 USB CDC 写行：
//   P<x>,<y>   x/y = 0~1000，画面归一化坐标（0=左/上，1000=右/下）
//   L          画面里没人，回中
// 这里做两件事：把坐标映射成两个舵机角度，并用每 10ms 挪 1° 的方式平滑逼近，避免抖动。

#include <Arduino.h>
#include <ESP32Servo.h>

// 接线：舵机信号线接 GPIO4（水平 pan）/ GPIO5（俯仰 tilt）
// 舵机电源必须用独立 5V，且和 ESP32 共地（详见 README）
static const int PAN_PIN = 4;
static const int TILT_PIN = 5;

static const int PAN_MIN = 20;
static const int PAN_MAX = 160;
static const int TILT_MIN = 30;
static const int TILT_MAX = 150;

Servo panServo;
Servo tiltServo;

float panNow = 90;
float panTarget = 90;
float tiltNow = 90;
float tiltTarget = 90;
int lastPanWritten = 90;
int lastTiltWritten = 90;
unsigned long lastCmd = 0;

void handleLine(const String &s) {
  if (s.length() == 0) return;
  lastCmd = millis();
  if (s[0] == 'L') {                 // 没人：回中
    panTarget = 90;
    tiltTarget = 90;
    return;
  }
  if (s[0] != 'P') return;
  int comma = s.indexOf(',');
  if (comma < 1) return;
  int x = constrain(s.substring(1, comma).toInt(), 0, 1000);
  int y = constrain(s.substring(comma + 1).toInt(), 0, 1000);
  panTarget = map(x, 0, 1000, PAN_MIN, PAN_MAX);
  tiltTarget = map(y, 0, 1000, TILT_MIN, TILT_MAX);
}

void setup() {
  Serial.begin(115200);              // C3 原生 USB CDC，波特率仅作标示
  panServo.attach(PAN_PIN, 500, 2500);
  tiltServo.attach(TILT_PIN, 500, 2500);
  panServo.write(90);
  tiltServo.write(90);
  lastCmd = millis();
}

void loop() {
  static String line;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      handleLine(line);
      line = "";
    } else if (line.length() < 32) {
      line += c;
    }
  }

  static unsigned long lastStep = 0;
  if (millis() - lastStep >= 10) {
    lastStep = millis();
    if (panNow < panTarget) panNow += 1;
    else if (panNow > panTarget) panNow -= 1;
    if (tiltNow < tiltTarget) tiltNow += 1;
    else if (tiltNow > tiltTarget) tiltNow -= 1;
    int panOut = (int)panNow;
    int tiltOut = (int)tiltNow;
    if (panOut != lastPanWritten) {
      lastPanWritten = panOut;
      panServo.write(panOut);
    }
    if (tiltOut != lastTiltWritten) {
      lastTiltWritten = tiltOut;
      tiltServo.write(tiltOut);
    }
  }

  // 3 秒没收到新坐标（手机锁屏/断开）就慢慢回中
  if (millis() - lastCmd > 3000) {
    panTarget = 90;
    tiltTarget = 90;
  }
}

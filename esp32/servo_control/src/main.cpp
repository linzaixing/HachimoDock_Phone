// HachimoPhone 云台固件（ESP32-C3 mini）
//
// 手机通过 Type-C OTG 直接接到这块板子的 USB 口，手机 App 以 115200 往 USB CDC 写行：
//   P<x>,<y>   x/y = 0~1000，画面归一化坐标（0=左/上，1000=右/下）
//   L          画面里没人，回中
//   C0 / C1    充电继电器断电（停止充电）/ 通电（恢复充电）
// 这里做三件事：把坐标映射成两个舵机角度并平滑逼近；控制充电继电器；
// 30 分钟收不到 C 指令就恢复充电，避免手机被彻底断电。
//
// 充电保护接线：继电器用常闭触点，串在「充电器 → USB 分线器的充电输入」VBUS 上，
// 不要串在手机到 ESP32 的数据/供电路径上。继电器模块电平可能相反，
// 按实物把 CHARGE_CUT_LEVEL / CHARGE_ON_LEVEL 对调即可。

#include <Arduino.h>
#include <ESP32Servo.h>

// 接线：舵机信号线接 GPIO5（水平 pan）/ GPIO4（俯仰 tilt）
// 舵机电源必须用独立 5V，且和 ESP32 共地（详见 README）
static const int PAN_PIN = 5;
static const int TILT_PIN = 4;
// 充电继电器还没接线：先设为 0，完全不驱动 GPIO6；接好继电器后改成 1
#define ENABLE_CHARGE_RELAY 0
// 充电继电器控制脚（按你的板子换一个空闲 GPIO）
static const int CHARGE_PIN = 6;
#if ENABLE_CHARGE_RELAY
static const int CHARGE_CUT_LEVEL = HIGH;   // 吸合：断开充电
static const int CHARGE_ON_LEVEL = LOW;     // 释放：正常充电
static const unsigned long CHARGE_FAILSAFE_MS = 30UL * 60UL * 1000UL;
#endif

// 水平 5~175°（约 170°）。R02/R03 是 24T→48T 皮带减速，转盘只有舵机角度的一半（约 85°），
// 且转盘止转槽本身只有 90°，所以“180°”是舵机行程，不是转盘行程。
static const int PAN_MIN = 5;
static const int PAN_MAX = 175;
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
#if ENABLE_CHARGE_RELAY
bool chargeCut = false;
unsigned long lastChargeCmd = 0;
#endif

void handleLine(const String &s) {
  if (s.length() == 0) return;
  if (s[0] == 'L') {                 // 没人：回中
    lastCmd = millis();
    panTarget = 90;
    tiltTarget = 90;
    return;
  }
  if (s[0] == 'C') {                 // 充电继电器：C0 停充，C1 恢复
#if ENABLE_CHARGE_RELAY
    if (s.length() < 2) return;
    chargeCut = (s[1] == '0');
    digitalWrite(CHARGE_PIN, chargeCut ? CHARGE_CUT_LEVEL : CHARGE_ON_LEVEL);
    lastChargeCmd = millis();
#endif
    return;
  }
  if (s[0] != 'P') return;
  int comma = s.indexOf(',');
  if (comma < 1) return;
  lastCmd = millis();
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
#if ENABLE_CHARGE_RELAY
  pinMode(CHARGE_PIN, OUTPUT);
  digitalWrite(CHARGE_PIN, CHARGE_ON_LEVEL);   // 上电默认允许充电
#endif
  lastCmd = millis();
#if ENABLE_CHARGE_RELAY
  lastChargeCmd = millis();
#endif
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

  // 停充状态超过 30 分钟没有 C 指令（App 被杀/断连）就恢复充电，避免电池放干
#if ENABLE_CHARGE_RELAY
  if (chargeCut && millis() - lastChargeCmd > CHARGE_FAILSAFE_MS) {
    chargeCut = false;
    digitalWrite(CHARGE_PIN, CHARGE_ON_LEVEL);
  }
#endif
}

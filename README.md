# Smart Garage Door Opener

A complete IoT garage door opener system featuring secure Bluetooth Low Energy (BLE) communication, ESP32-C3 microcontroller, custom PCB design, and Android mobile application.

## 🚀 Project Overview

This project implements a modern, secure garage door opener that can be controlled via a smartphone app. The system features:

- **Secure BLE Communication**: Encrypted pairing and authentication
- **ESP32-C3 Based Controller**: Low-power WiFi/BLE microcontroller
- **Custom PCB Design**: Professional circuit board with integrated power management
- **Android Mobile App**: Modern Material Design 3 interface
- **Visual Feedback**: 8-digit LED matrix display for status and pairing codes
- **Physical Controls**: Manual pairing button and factory reset capability

## 📁 Project Structure

```
Garage Door Opener Root/
├── Garage Door Opener Circuit/        # KiCad PCB design files
│   ├── GarageOpener.kicad_sch         # Main schematic
│   ├── GarageOpener.kicad_pcb         # PCB layout
│   ├── production files/              # Gerber files for manufacturing
│   └── parts.txt                      # Bill of materials
├── Garage Door Opener Code/
│   ├── ESP32/                         # Firmware for ESP32-C3
│   │   └── Garage Door Opener/
│   │       ├── src/main.cpp           # Main firmware code
│   │       └── platformio.ini        # PlatformIO configuration
│   └── Android/                       # Android mobile application
│       └── GarageOpenerUI/
│           └── app/src/main/          # Android app source code
├── LICENSE                            # Project license
└── README.md                          # This file
```

## 🔧 Hardware Components

### Main Components
- **ESP32-C3-MINI-1U-N4**: Main microcontroller with WiFi/BLE
- **5V Relay Module**: Controls garage door motor
- **MAX7219 LED Matrix**: 8-digit display for status and pairing codes
- **Buck Converter**: Efficient 5V power supply from mains voltage
- **3.3V Regulator**: Powers ESP32 and logic circuits

### Key Features
- **Input Voltage**: 3.8V - 32V DC input range
- **Power Consumption**: Low-power design with sleep modes
- **Protection**: ESD protection, overcurrent protection
- **Connectivity**: Micro USB for user cusom programming and debugging
- **User Interface**: Physical pairing button, LED status indicators

### PCB Specifications
- **Board Size**: Compact form factor for enclosure mounting
- **Layers**: Multi-layer design for proper power distribution
- **Components**: Surface mount components for reliability
- **Connectors**: Screw terminals for garage door connections

## 💻 Software Features

### ESP32 Firmware
- **Secure BLE Server**: Implements encrypted communication
- **Pairing Management**: Secure device pairing with passkey display
- **Display Controller**: Real-time status updates on LED matrix
- **Memory Management**: Persistent storage of paired devices
- **Factory Reset**: Complete system reset capability

### Android Application
- **Modern UI**: Material Design 3 with Jetpack Compose
- **Device Discovery**: Automatic BLE device scanning
- **Secure Pairing**: Handles BLE security and authentication
- **Device Management**: Save and manage multiple garage door openers
- **Real-time Status**: Live connection status and feedback

## 🔐 Security Features

- **Encrypted BLE Communication**: All data transmitted over encrypted channels
- **Device Authentication**: Secure pairing with passkey verification
- **Bonded Devices**: Only previously paired devices can connect
- **Timeout Protection**: Pairing mode automatically times out
- **Factory Reset**: Complete security reset capability

## 🛠️ Installation & Setup

### Hardware Assembly

1. **PCB Assembly**: Solder all components according to the schematic
2. **Power Connection**: Connect 5-32V DC power supply to input terminals
3. **Relay Wiring**: Connect relay outputs to garage door motor controls
4. **Display Connection**: Wire MAX7219 LED matrix to designated pins
5. **Enclosure**: Mount PCB in weatherproof enclosure

### Firmware Installation

1. **Install PlatformIO**: Set up PlatformIO IDE or VS Code extension
2. **Clone Repository**: Download project files
3. **Configure Platform**: Verify ESP32-C3 platform configuration
4. **Build & Upload**: Compile and flash firmware to ESP32

```bash
cd "Garage Door Opener Code/ESP32/Garage Door Opener"
pio run --target upload
```

### Android App Installation
1. **Easy Install** : Pre built app can be found in ```Garage Door Opener Code/Android/GarageOpenerUI/app/release/app-release.apk``` 

### Android App build
1. **Android Studio**: Open project in Android Studio
2. **Dependencies**: Sync Gradle dependencies
3. **Build Configuration**: Configure signing and build settings
4. **Install**: Build and install APK on Android device

```bash
cd "Garage Door Opener Code/Android/GarageOpenerUI"
./gradlew assembleRelease
```

## 📱 Usage Instructions

### Initial Setup

1. **Power On**: Connect power to the garage door opener
2. **Pairing Mode**: Press and hold the pairing button for 5 seconds
3. **Mobile App**: Open the Android app and scan for devices
4. **Select Device**: Choose "Garage" from the discovered devices list
5. **Enter Passkey**: Input the 6-digit code displayed on the LED matrix
6. **Complete Pairing**: Confirm pairing on both devices

### Daily Operation

1. **Open App**: Launch the Garage Opener app on your phone
2. **Connect**: App automatically connects to paired device
3. **Control**: Tap the garage door button to operate
4. **Status**: Monitor connection status via app interface

### Advanced Features

- **Multiple Devices**: Pair multiple garage door openers
- **Device Management**: Remove or re-pair devices as needed
- **Factory Reset**: Hold pairing button for 10 seconds to reset

## 🔌 Pin Configuration

### ESP32-C3 Pin Assignments
```cpp
#define RELAY_PIN 12        // Garage door relay control
#define BUTTON_PIN 13       // Pairing/reset button
#define DIN_PIN 21          // MAX7219 data input
#define CS_PIN 19           // MAX7219 chip select
#define CLK_PIN 18          // MAX7219 clock
```

### Power Requirements
- **Input Voltage**: 5V - 32V DC
- **ESP32 Current**: ~80mA active, ~10µA deep sleep
- **Relay Current**: ~70mA when activated
- **Display Current**: ~20mA typical

## 🐛 Troubleshooting

### Common Issues

**Cannot pair device:**
- Ensure Bluetooth is enabled on phone
- Verify device is in pairing mode (LED shows passkey)
- Check app permissions for Bluetooth access

**Relay not activating:**
- Verify power supply voltage (5V minimum)
- Check relay connections and wiring
- Test relay manually with multimeter

**Display not working:**
- Confirm MAX7219 connections and power
- Verify pin assignments in firmware
- Check display module with simple test code

**Connection drops frequently:**
- Reduce distance between phone and device
- Check for interference from other 2.4GHz devices
- Verify phone's Bluetooth Low Energy support

## 🔧 Development

### Building from Source

**Requirements:**
- PlatformIO Core 6.0+
- Android Studio Arctic Fox+
- ESP32 Arduino Core 2.0+
- Android API Level 30+

**ESP32 Development:**
```bash
# Install dependencies
pio pkg install

# Build firmware
pio run

# Upload to device
pio run --target upload

# Monitor serial output
pio device monitor
```

**Android Development:**
```bash
# Build debug APK
./gradlew assembleDebug

# Run tests
./gradlew test

# Generate release APK
./gradlew assembleRelease
```

### Code Structure

**ESP32 Firmware:**
- `main.cpp`: Main application logic and BLE server
- `LedController.hpp`: MAX7219 display management
- `platformio.ini`: Build configuration and dependencies

**Android Application:**
- `MainActivity.kt`: Main UI and app lifecycle
- `BleService.kt`: Bluetooth Low Energy communication
- `build.gradle.kts`: Build configuration and dependencies

## 📋 Bill of Materials (BOM)

### Critical Components
| Component | Part Number | Quantity | Description |
|-----------|-------------|----------|-------------|
| ESP32-C3-MINI-1U-N4 | ESP32-C3-MINI-1U-N4 | 1 | Main microcontroller |
| Relay | SRD-05VDC-SL-C | 1 | 5V SPDT relay |
| Buck Converter IC | TBD | 1 | 5V switching regulator |
| LED Matrix | MAX7219 8-digit | 1 | Status display |
| Capacitors | Various | Multiple | Power filtering |
| Resistors | Various | Multiple | Pull-ups and current limiting |

*See `parts.txt` for complete component specifications*

## 🤝 Contributing

Contributions are welcome! Please follow these guidelines:

1. **Fork the Repository**: Create your own fork
2. **Create Feature Branch**: `git checkout -b feature/new-feature`
3. **Commit Changes**: Use descriptive commit messages
4. **Push Branch**: `git push origin feature/new-feature`
5. **Create Pull Request**: Submit PR with detailed description

### Development Standards
- Follow existing code style and formatting
- Add comments for complex logic
- Test thoroughly before submitting
- Update documentation as needed

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## ⚠️ Safety Warning

**ELECTRICAL SAFETY**: This device interfaces with garage door motors that operate at dangerous voltages. Installation should only be performed by qualified electricians. Always disconnect power before making connections.

**SECURITY**: While this system implements encryption, no system is 100% secure. Use appropriate physical security measures for your garage.

## 📞 Support

For questions, issues, or contributions:
- **Issues**: Use GitHub Issues for bug reports
- **Discussions**: GitHub Discussions for general questions
- **Email**: Contact project maintainer for urgent issues

## 🔄 Version History

- **v1.0**: Initial release with basic BLE functionality
- **Future**: Planned features include WiFi connectivity, web interface, and voice control integration

---

**Built with ❤️ for the maker community**
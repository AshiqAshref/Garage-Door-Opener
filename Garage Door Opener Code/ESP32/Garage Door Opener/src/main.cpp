#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLE2902.h>

#include "LedController.hpp"
#include <Preferences.h>

#include "esp_gap_ble_api.h" // Required for esp_ble_get_bond_device_num() and esp_ble_get_bond_device_list()
#include "esp_bt.h"         // Required for esp_ble_bond_dev_t struct definition

// GPIO pin connected to the relay that controls the garage door
#define RELAY_PIN 12
// #define BUTTON_PIN 27
#define BUTTON_PIN 13

// GPIO pins for the MAX7219 LED Matrix 8 digit display
constexpr int DIN_PIN = 21;
constexpr int CS_PIN  = 19;
constexpr int CLK_PIN = 18;

// UUID generator : https://www.uuidgenerator.net/
#define SERVICE_UUID        "9ba08ea3-3fa9-4622-bae5-bdd3f0c7fedf" // Example Service UUID
#define CHARACTERISTIC_UUID "427c5c12-0f90-46be-ba43-7e4a207be489" // Example Characteristic UUID

// Global pointers for BLE objects
BLEServer* pServer = nullptr;
BLECharacteristic* pCharacteristic = nullptr;

// Create an instance of the LedController
LedController lc = LedController();

// Flags to track connection status for re-advertising
bool deviceConnected = false;
bool oldDeviceConnected = false;

uint32_t currentDisplayedPasskey = 0;

//pairing button
constexpr unsigned long PAIRING_PRESS_DURATION_MILLIS = 5000; // 5 seconds
constexpr unsigned long PAIRING_WINDOW_TIMEOUT_MILLIS = 60000; // Advertising active for 60 seconds if no connection
constexpr unsigned long FACTORY_RESET_PRESS_DURATION = 5000; // 5 seconds for factory reset
bool pairingModeActive = false;  // True if the device is currently advertising for new pairings
bool allowNewPairing = false;    // Only true during explicit pairing mode activated by button
unsigned long buttonPressStartTime = 0; // Tracks when the pairing button was first pressed
unsigned long pairingModeStartTime = 0; // Tracks when pairing mode was activated, for timeout

unsigned long lastDisplayUpdate = 0; // Timestamp for the last display update


Preferences preferences;

// Add this function to your code
void clearBondedDevices() {
    Serial.println("Clearing all bonded devices from NVS...");
    preferences.begin("nvs", false); // Open preferences with namespace "nvs" where ESP32 BT/BLE info is stored
    preferences.clear(); // Clear all preferences under this namespace
    preferences.end(); // Close the preferences

    Serial.println("All bonded devices have been removed. Restarting...");
    ESP.restart();
}

// Function to list all currently bonded devices stored in NVS flash
void listBondedDevices() {
    Serial.println("\n--- Listing Bonded Devices ---");

    // Get the number of bonded devices
    int dev_num = esp_ble_get_bond_device_num(); 

    if (dev_num <= 0) {
        Serial.println("No bonded devices found.");
        return;
    }

    // Allocate memory to hold the list of bonded device structures
    // It's crucial to free this memory after use.
    auto *bond_dev_list = static_cast<esp_ble_bond_dev_t *>(heap_caps_malloc(sizeof(esp_ble_bond_dev_t) * dev_num,
                                                                             MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT));

    if (!bond_dev_list) {
        Serial.println("ERROR: Failed to allocate memory for bond device list.");
        return;
    }

    // Retrieve the list of bonded devices
    // The first argument `&dev_num` will be updated with the actual number of devices retrieved.
    esp_ble_get_bond_device_list(&dev_num, bond_dev_list);

    Serial.printf("%d bonded device(s) found:\n", dev_num);
    for (int i = 0; i < dev_num; i++) {
        // Print the MAC address of each bonded device
        Serial.printf("  Device %d: %02X:%02X:%02X:%02X:%02X:%02X\n", i + 1,
                        bond_dev_list[i].bd_addr[0], bond_dev_list[i].bd_addr[1],
                        bond_dev_list[i].bd_addr[2], bond_dev_list[i].bd_addr[3],
                        bond_dev_list[i].bd_addr[4], bond_dev_list[i].bd_addr[5]);
        // You could also inspect bond_dev_list[i].addr_type if you need to differentiate
        // between public and random addresses, though for most uses, the address itself is enough.
    }

    // Free the allocated memory to prevent memory leaks
    heap_caps_free(bond_dev_list); 
    Serial.println("--- End of Bonded Devices List ---");
}


void displayString(const String& str, uint8_t startSegment, const String& prefix = "") {
    if (pairingModeActive && currentDisplayedPasskey > 0) {
        return; // If pairing mode is active, do not update the display with passkeys
    }
    lastDisplayUpdate = millis(); // Update the timestamp for the last display update
    lc.clearMatrix();  
    const uint32_t len = str.length();
    
    uint8_t segPos;
    if(startSegment > 7) { len>7 ? segPos = 7: segPos=len; } 
    else { segPos = 7 - startSegment; }
    
    if(prefix.length() > 0) {
        byte charAtPos = 0;
        for (int i = 7; i > segPos; i--) {
            i-1 <= segPos ?
            lc.setChar(0, i, prefix.charAt(charAtPos++), true):
            lc.setChar(0, i, prefix.charAt(charAtPos++), false);
        }
    }
    
    for (int i = 0; i < len; i++) {
        char charToDisplay = str.charAt(i);
        lc.setChar(0, segPos--, charToDisplay, false);
        if (segPos==0 && i+2 < len) {
            lc.setChar(0, segPos, '-', false);
            break; 
        }
    }
}

void displayString(uint32_t numberInt, uint8_t startSegment, const String& prefix = "") {
    String numberStr = String(numberInt);
    displayString(numberStr, startSegment, prefix);
}

void displayClear(){
    if (currentDisplayedPasskey == 0){
        lc.clearMatrix();
        lastDisplayUpdate = 0;
    }
    
}

void knightRiderEffect(){
    for (int i = 0; i < 8; i++) {
        lc.setColumn(0, i, 0b11111111);
        delay(40);
        lc.setColumn(0, i, 0b00000000);
    }
    for (int i = 6; i > 0; i--) {
        lc.setColumn(0, i, 0b11111111);
        delay(40);
        lc.setColumn(0, i, 0b00000000);
    }
    lc.clearMatrix();
}

void displayPattern(uint32_t duration){
    lc.clearMatrix();
    uint32_t startMillis = millis();
    while (millis() - startMillis < duration) {
        knightRiderEffect();
        delay(200);
    }
}


/**
 * Monitors a button press for a specified duration.
 * Displays a countdown timer while button is pressed.
 * 
 * @param buttonPin The GPIO pin connected to the button
 * @param requiredDuration The required press duration in milliseconds
 * @param prefixText Optional text prefix for the display (default: "")
 * @return true if button was held for the entire duration, false otherwise
 */
bool waitForButtonPressDuration(const uint8_t buttonPin, const unsigned long requiredDuration, const String& prefixText = "") {
    const unsigned long startTime = millis();
    unsigned long secondsRemaining = requiredDuration / 1000;
    unsigned long lastDisplayedSecond = secondsRemaining;
    delay(15);
    if (digitalRead(buttonPin) == HIGH){
        while (digitalRead(buttonPin) == HIGH) {
            const unsigned long elapsedTime = millis() - startTime;
            
            secondsRemaining = ((requiredDuration - elapsedTime) / 1000);
            if (secondsRemaining != lastDisplayedSecond) {
                lastDisplayedSecond = secondsRemaining;
                displayString(secondsRemaining, prefixText.length(), prefixText);
                Serial.println("Hold for " + String(secondsRemaining) + " more seconds...");
            }
            
            if (elapsedTime >= requiredDuration) {displayClear(); return true; }
            delay(10);
        }
        displayClear();
    }
    
    return false; 
}


// --- 1. BLE Server Callbacks (for connection/disconnection events) ---
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer, esp_ble_gatts_cb_param_t *param) override {
        Serial.println("Device connected. Starting secure connection process...");

            // Check if we have any bonded devices and if we're not in pairing mode
        int dev_num = esp_ble_get_bond_device_num();
        if (dev_num > 0 && !allowNewPairing) {
            // Check if the connecting device is bonded
            if (auto *bond_dev_list = static_cast<esp_ble_bond_dev_t *>(heap_caps_malloc(sizeof(esp_ble_bond_dev_t) * dev_num,
                MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT))) {
                esp_ble_get_bond_device_list(&dev_num, bond_dev_list);
                
                // Check if the connecting device's address matches any bonded device
                bool deviceBonded = false;
                for (int i = 0; i < dev_num; i++) {
                    if (memcmp(param->connect.remote_bda, bond_dev_list[i].bd_addr, ESP_BD_ADDR_LEN) == 0) {
                        deviceBonded = true;
                        break;
                    }
                }
                
                heap_caps_free(bond_dev_list);
                
                // If not bonded and not in pairing mode, disconnect
                if (!deviceBonded) {
                    Serial.println("UNAUTHORIZED CONNECTION ATTEMPT - Disconnecting!");
                    displayString("UNAUTH", 0);
                    delay(1000);
                    pServer->disconnect(param->connect.conn_id);
                    deviceConnected = false;
                    return;
                }
            }
        }

        deviceConnected = true;

        // Print the MAC address of the connecting device
        char deviceAddress[18];
        sprintf(deviceAddress, "%02X:%02X:%02X:%02X:%02X:%02X", 
                param->connect.remote_bda[0], param->connect.remote_bda[1], param->connect.remote_bda[2],
                param->connect.remote_bda[3], param->connect.remote_bda[4], param->connect.remote_bda[5]);
        Serial.print("Device MAC address: ");
        Serial.println(deviceAddress);
        
        if(esp_ble_set_encryption(param->connect.remote_bda, ESP_BLE_SEC_ENCRYPT_MITM) == ESP_OK){
            displayString("COn 6ood", 0);
        }else {
            displayString("COn FAil", 0);
        }
    };

    void onDisconnect(BLEServer* pServer) override {
        Serial.println("Device disconnected. Attempting to restart advertising...");
        displayString("COn Dis", 0);
        deviceConnected = false;
        
        int dev_num = esp_ble_get_bond_device_num();
        if (dev_num > 0) {
            displayString("Adv st", 0);
            delay(500); // Give the BLE stack time to reset
            pServer->getAdvertising()->start();
            Serial.println("Advertising restarted for reconnection of bonded devices.");
        }
    }
};

// --- 2. BLE Security Callbacks (crucial for authentication and pairing) ---
class MySecurityCallbacks : public BLESecurityCallbacks {
    // This callback is triggered when the ESP32 needs to display a passkey to the user.
    // This happens when ESP32 has ESP_IO_CAP_OUT or KBDISP and a Passkey Entry pairing is negotiated.    
    void onPassKeyNotify(uint32_t pass_key) override {
        // Security check: Only allow pairing if we're in pairing mode or if no devices are bonded yet
        int dev_num = esp_ble_get_bond_device_num();
        if (dev_num > 0 && !allowNewPairing) {
            // This is an unauthorized pairing attempt outside of pairing mode
            Serial.println("\n*************************************************");
            Serial.println("****** UNAUTHORIZED PAIRING ATTEMPT DETECTED ******");
            Serial.println("*************************************************\n");
            Serial.println("Rejecting passkey display - not in pairing mode!");
            
            // Force disconnect the device to prevent pairing
            pServer->disconnect(pServer->getConnId());
            return;
        }
        
        // If we get here, pairing is allowed
        currentDisplayedPasskey = pass_key; // Store for potential display loop
        Serial.println("\n*************************************************");
        Serial.print("****** PAIRING PASSKEY TO ENTER ON CLIENT: ");
        Serial.print(pass_key);
        Serial.println(" ******");
        Serial.println("*************************************************\n");
        Serial.println("Please enter this 6-digit passkey on your client (e.g., smartphone) to complete pairing.");
        displayString(pass_key, 2, "P5");
    }

    //NOT USED
    uint32_t onPassKeyRequest() override {
        Serial.println("Passkey Request (this callback should generally not be hit with ESP_IO_CAP_OUT)");
        return 0; // Return 0 as ESP32 is not designed to receive passkey input in this mode
    }

    //NOT USED
    bool onConfirmPIN(uint32_t pin) override {
        Serial.print("Confirm PIN (Numeric Comparison): ");
        Serial.println(pin);
        Serial.println("Does this PIN match on both devices? (Confirm on client if prompted).");
        // In a Numeric Comparison scenario, you'd ask the user to confirm match.
        // For Passkey Entry, this is not relevant.
        return true; // Assuming confirmation if hit, though ideally not.
    }    // This callback is triggered if a peer device explicitly requests security (e.g., to encrypt).
    bool onSecurityRequest() override {
        // Get number of bonded devices
        int dev_num = esp_ble_get_bond_device_num();
        
        // If we already have bonded devices, and we're not explicitly in pairing mode, reject new pairing attempts
        if (dev_num > 0 && !allowNewPairing) {
            Serial.println("SECURITY REQUEST REJECTED - Device not in pairing mode!");
            Serial.println("To pair a new device, press and hold the pairing button for 5 seconds.");
            displayString("SEC rEj", 0);
            delay(1000);
            displayClear();
            pServer->disconnect(pServer->getConnId());
            return false; // Reject the security request, preventing pairing
        }
        Serial.println("Security Request received from client. Allowing...");
        return true; // Allow the security request to initiate pairing process
    }

    // This callback is triggered once the entire authentication/pairing process is complete.
    void onAuthenticationComplete(esp_ble_auth_cmpl_t auth_cmpl) override {
        currentDisplayedPasskey = 0; // Clear the passkey once authentication is done
        lc.clearMatrix(); // Clear the display after authentication
        const String disp_val_fail = "FA1L";
        if (auth_cmpl.success) {
            Serial.println("\nAuthentication SUCCESS! Device bonded.");
            Serial.print("Bonded device address: ");
            Serial.printf("%02X:%02X:%02X:%02X:%02X:%02X\n",
                            auth_cmpl.bd_addr[0], auth_cmpl.bd_addr[1], auth_cmpl.bd_addr[2],
                            auth_cmpl.bd_addr[3], auth_cmpl.bd_addr[4], auth_cmpl.bd_addr[5]);
            Serial.println("Future connections with this device will be automatically re-encrypted.");
            displayString("SEC PASS", 0); 
        } else {
            displayString("SEC FAIL", 0);
            Serial.println("\nAuthentication FAILED! Connection terminated.");
            Serial.printf("Reason: %d\n", auth_cmpl.fail_reason);
            // It's good practice to disconnect on failed authentication to prevent unsecure connections
            pServer->disconnect(pServer->getConnId());
        }        
        
        if (pairingModeActive) {
            pServer->getAdvertising()->stop();
            pairingModeActive = false;
            allowNewPairing = false;  // Reset the pairing flag
            Serial.println("Pairing mode (advertising) deactivated after authentication attempt.");
        }
    }
};


class MyCharacteristicCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) override {
        // Additional safety check - although the ESP32 BLE stack should already enforce security
        int dev_num = esp_ble_get_bond_device_num();
        if (dev_num <= 0) {
            Serial.println("WARNING: Write attempt with no bonded devices. Ignored.");
            pCharacteristic->setValue("Security error: No bonded devices");
            pCharacteristic->notify();
            return;
        }

        std::string value = pCharacteristic->getValue(); // Get the value written by the client

        if (!value.empty()) {
            Serial.print("Received command: ");
            for (int i = 0; i < value.length(); i++)
                Serial.print(value[i]);
            Serial.println();

            // --- Your Garage Door Control Logic ---
            if (value == "TRIGGER") { // Or could be a single byte like 0x01
                Serial.println("--- Triggering Garage Door Relay ---");
                digitalWrite(RELAY_PIN, HIGH); // Activate the relay
                knightRiderEffect();
                digitalWrite(RELAY_PIN, LOW); // Deactivate the relay
                Serial.println("--- Relay action complete ---");
                pCharacteristic->setValue("Command executed");
                pCharacteristic->notify();
            } else {
                Serial.println("Unknown command received.");
                pCharacteristic->setValue("Unknown command received");
                pCharacteristic->notify();
            }
        }
    }

    // You can also implement onRead() if you want the client to read something from the characteristic
    void onRead(BLECharacteristic *pCharacteristic) override {
        Serial.println("Client tried to read characteristic.");
        pCharacteristic->setValue("Status: Ready"); // Example: provide a status
    }
};





void setup(){
    Serial.begin(115200);
    Serial.println("\n--- Starting ESP32 BLE Secure Server with Passkey Entry & Bonding ---");

    lc.init(DIN_PIN, CLK_PIN, CS_PIN, 1);
    lc.activateAllSegments();
    lc.setIntensity(6);
    lc.clearMatrix();

    pinMode(BUTTON_PIN, INPUT);
    pinMode(RELAY_PIN, OUTPUT);
    digitalWrite(RELAY_PIN, LOW);  // Ensure relay is off on startup

    if(waitForButtonPressDuration(BUTTON_PIN, FACTORY_RESET_PRESS_DURATION, "rst")) {
        displayString("FCT rST", 0);
        clearBondedDevices();
        Serial.println();
    } 

    BLEDevice::init("Garage");
    BLEDevice::setSecurityCallbacks(new MySecurityCallbacks());

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks()); // Set server-level callbacks for connect/disconnect
    BLEService *pService = pServer->createService(SERVICE_UUID);    // Create a BLE Characteristic for data exchange.
    pCharacteristic = pService->createCharacteristic(
                                    CHARACTERISTIC_UUID,
                                    BLECharacteristic::PROPERTY_WRITE |
                                    BLECharacteristic::PROPERTY_WRITE_NR | // Write without response
                                    BLECharacteristic::PROPERTY_READ |
                                    BLECharacteristic::PROPERTY_NOTIFY
                                );
    pCharacteristic->setCallbacks(new MyCharacteristicCallbacks()); // Set the callback for this characteristic to handle write operations
    pCharacteristic->setValue("Hello from Secure ESP32!"); // Initial value
    // Add a standard Client Characteristic Configuration Descriptor (CCCD)
    // This allows clients to enable/disable notifications for this characteristic.
    pCharacteristic->addDescriptor(new BLE2902()); 

    // Start the BLE Service
    pService->start();

    // Get the advertising object
    BLEAdvertising *pAdvertising = pServer->getAdvertising();

    // Add your custom service UUID to the advertising data.
    // This allows clients to scan for and discover your specific service.
    pAdvertising->addServiceUUID(SERVICE_UUID);    // --- Core BLE Security Configuration ---
    auto *pSecurity = new BLESecurity();

    // 1. Set Authentication Mode:
    //    ESP_LE_AUTH_REQ_SC_MITM_BOND is the most secure option:
    //    - SC (Secure Connections): Uses robust Elliptic Curve Diffie-Hellman (ECDH) for key exchange.
    //    - MITM (Man-in-the-Middle Protection): Ensures a pairing method is used that protects against MITM attacks.
    //    - BOND (Bonding): Stores the keys (LTK) so devices can reconnect securely later without re-pairing.
    pSecurity->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_MITM_BOND);

    // 2. Set I/O Capabilities:
    //    ESP_IO_CAP_OUT (Display Only): Tells the peer that THIS ESP32 can display a passkey,
    //    but it does not have a keyboard for user input.
    //    Combined with MITM requirement, this forces 'Passkey Entry' where ESP32 displays the key.
    pSecurity->setCapability(ESP_IO_CAP_OUT); 

    // 3. Set the encryption key size range (optional, defaults are usually fine for 128-bit AES)
    //    ESP_BLE_ENC_KEY_MASK: Enable encryption keys.
    //    ESP_BLE_ID_KEY_MASK: Enable identity keys (for privacy and device identification).
    pSecurity->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
    
    // 4. Ensure advertising is secure    pAdvertising->setMinPreferred(0x06);  // Set preferred connection parameters
    pAdvertising->setMinPreferred(0x12);
      // Check if we already have bonded devices
    int dev_num = esp_ble_get_bond_device_num();
    
    if (dev_num > 0) {
        // If we have bonded devices, start advertising for reconnection (not pairing)
        pAdvertising->start();
        Serial.println("Advertising automatically started for bonded devices.");
        Serial.println("For new device pairing, press and hold pairing button for 5 seconds.");
    } else {
        // Don't start advertising automatically if no bonded devices
        pAdvertising->stop();
        Serial.println("No bonded devices found. Press pairing button to enter pairing mode.");
    }
    
    listBondedDevices();
}


void loop() {

    if (!pairingModeActive && waitForButtonPressDuration(BUTTON_PIN, PAIRING_PRESS_DURATION_MILLIS, "PAIr")) {
        pServer->getAdvertising()->start();
        pairingModeActive = true;
        allowNewPairing = true; // Enable new pairing attempts during this window
        pairingModeStartTime = millis(); 
        Serial.println("\n******************************************");
        Serial.println("PAIRING MODE ACTIVATED! Advertising started.");
        Serial.println("Will remain active for 60 seconds if no connection is made.");
        Serial.println("******************************************\n");
        displayString("PAIr ACt", 0);
    }

    // --- Pairing Mode Timeout (if no connection or pairing occurs) ---
    // If pairing mode is active, no device is connected, and the timeout has passed:
    if (pairingModeActive && !deviceConnected && (millis() - pairingModeStartTime >= PAIRING_WINDOW_TIMEOUT_MILLIS)) {
        Serial.println("Pairing mode timed out. No connection made within 60 seconds.");
        pServer->getAdvertising()->stop(); // Stop advertising
        pairingModeActive = false; // Deactivate pairing mode flag
        allowNewPairing = false;   // Disable new pairing
        displayString("PAIr StP", 0);
    }

    // Check if device just connected
    if (deviceConnected && !oldDeviceConnected) {
        // Device just connected, you can add any one-time connection logic here
        oldDeviceConnected = deviceConnected;
        Serial.println("Initial connection established. Awaiting authentication...");
    }
      // Check if device just disconnected
    if (!deviceConnected && oldDeviceConnected) {
        // Device was just disconnected, advertising is handled in onDisconnect callback        oldDeviceConnected = deviceConnected;
    }
    if (lastDisplayUpdate > 0 && millis() - lastDisplayUpdate >= 5000) {
        displayClear();
    }

    delay(100); // Small delay to prevent busy-waiting
}




import Foundation
import CoreBluetooth
import CryptoKit
import AppKit

let serviceID = CBUUID(string: "650f0001-b763-4f40-a619-4d88e9107310")
let rxID = CBUUID(string: "650f0002-b763-4f40-a619-4d88e9107310")
let txID = CBUUID(string: "650f0003-b763-4f40-a619-4d88e9107310")

func option(_ name: String, fallback: String) -> String {
    guard let i = CommandLine.arguments.firstIndex(of: name), CommandLine.arguments.indices.contains(i + 1) else { return fallback }
    return CommandLine.arguments[i + 1]
}
let code = option("--code", fallback: "")
let logPath = option("--log", fallback: NSTemporaryDirectory() + "oabl-bluetooth-lab.log")
let duration = max(30, Double(option("--duration", fallback: "65")) ?? 65)
FileManager.default.createFile(atPath: logPath, contents: nil)
let logFile = FileHandle(forWritingAtPath: logPath)!
var statusLabel: NSTextField?
func log(_ text: String) {
    let line = ISO8601DateFormatter().string(from: Date()) + " " + text + "\n"
    print(line, terminator: ""); try? logFile.write(contentsOf: Data(line.utf8)); try? logFile.synchronize()
    statusLabel?.stringValue = text
}
func die(_ text: String) -> Never { log("FAIL " + text); exit(1) }
func hmac(_ key: Data, _ data: Data) -> Data { Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key))) }
func hex(_ bytes: Data) -> String { bytes.map { String(format: "%02x", $0) }.joined() }
func wireRequest(id: String, target: String, key: Data, time: Int64? = nil) -> [String: Any] {
    let now = time ?? Int64(Date().timeIntervalSince1970 * 1000), nonce = UUID().uuidString
    let canonical = "GET\n\(target)\n\(now)\n\(nonce)\n\(hex(Data(SHA256.hash(data: Data()))))"
    return ["id": id, "target": target, "time": now, "nonce": nonce, "signature": hex(hmac(key, Data(canonical.utf8)))]
}
func frame(_ data: Data, id: UInt16, size: Int) -> [Data] {
    let capacity = min(512, size) - 7, count = max(1, (data.count + capacity - 1) / capacity)
    return (0..<count).map { index in
        var result = Data([1, UInt8(id >> 8), UInt8(id & 255), UInt8(index >> 8), UInt8(index & 255), UInt8(count >> 8), UInt8(count & 255)])
        let start = index * capacity
        result.append(data.subdata(in: start..<min(data.count, start + capacity))); return result
    }
}

final class Lab: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    var central: CBCentralManager!
    var phone: CBPeripheral?, rx: CBCharacteristic?, tx: CBCharacteristic?
    let key = hmac(Data(code.utf8), Data("oabl-link/v1/pair".utf8))
    var writes: [Data] = [], writing = false
    var frameID: UInt16 = 0, requestID = 0
    var receive = Data(), receiveID: Int?, receiveCount = 0, receiveNext = 0
    var receivedAt = Date(), began = Date(), connectedAt = Date()
    var pending: (([String: Any]) -> Void)?, pendingID = "", deadline: Date?
    var handshakeReply: ((Data) -> Void)?, secure: BleSecureSession?
    var checks = 0, connections = 0, nowReads = 0, latestSeq: Int64 = 0
    var firstTime: Int64 = 0, lastTime: Int64 = 0, originalSOC: Double?, changedSOC = false
    var testReconnect = false, reconnecting = false, finishedHandshake = false, polling = false
    var timer: Timer?
    func start() {
        guard code.count == 6, code.allSatisfy({ $0.isNumber && $0.isASCII }) else { die("Supply the phone's six-digit code with --code") }
        log("START BLE phone→Mac test; no IP sockets; duration \(duration)s")
        central = CBCentralManager(delegate: self, queue: nil, options: [CBCentralManagerOptionShowPowerAlertKey: true])
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
    }
    func check(_ success: Bool, _ message: String) {
        if !success { die(message) }; checks += 1; log("PASS \(message)")
    }
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        log("Bluetooth state \(central.state.rawValue); authorization \(CBManager.authorization.rawValue)")
        switch central.state {
        case .poweredOn:
            if CommandLine.arguments.contains("--preflight") { log("PASS Mac BLE central is powered on and authorized"); exit(0) }
            central.scanForPeripherals(withServices: [serviceID], options: nil); log("Scanning for OABL BLE service")
        case .unauthorized: die("Allow OABL Bluetooth Lab in macOS System Settings → Privacy & Security → Bluetooth")
        case .unsupported: die("CoreBluetooth unavailable")
        case .poweredOff: log("Turn Mac Bluetooth on to continue")
        default: break
        }
    }
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard phone == nil else { return }
        phone = peripheral; peripheral.delegate = self; central.stopScan()
        log("Discovered \(peripheral.name ?? "OABL phone") · RSSI \(RSSI) · \(peripheral.identifier)")
        central.connect(peripheral, options: nil)
    }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connections += 1; reconnecting = false; connectedAt = Date()
        log("Connected #\(connections)"); peripheral.discoverServices([serviceID])
    }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) { die("Connect: \(String(describing: error))") }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        log("Disconnected: \(String(describing: error))")
        rx = nil; tx = nil; secure = nil; handshakeReply = nil; writes.removeAll(); writing = false; receive.removeAll(); receiveID = nil; receiveNext = 0
        pending = nil; deadline = nil; polling = false
        guard !reconnecting else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.central.connect(peripheral, options: nil) }; return
        }
        die("Unexpected disconnect")
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error = error { die("Services: \(error)") }
        guard let service = peripheral.services?.first(where: { $0.uuid == serviceID }) else { die("OABL service missing") }
        peripheral.discoverCharacteristics([rxID, txID], for: service)
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error = error { die("Characteristics: \(error)") }
        rx = service.characteristics?.first(where: { $0.uuid == rxID })
        tx = service.characteristics?.first(where: { $0.uuid == txID })
        guard rx != nil, let tx = tx else { die("RX/TX missing") }
        log("Write value limit \(peripheral.maximumWriteValueLength(for: .withResponse)) bytes; subscribing to acknowledged indications")
        peripheral.setNotifyValue(true, for: tx)
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error { die("Subscribe: \(error)") }
        guard characteristic.uuid == txID, characteristic.isNotifying else { die("Indications unavailable") }
        log("Indications enabled")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            self.secure = BleSecureSession(key: self.key)
            self.sendHandshake(self.secure!.begin()) { hello in
                self.sendHandshake(self.secure!.acceptHello(hello)) { proof in
                    self.secure!.acceptProof(proof)
                    self.check(true, "mutually authenticated encrypted Bluetooth session")
                    if self.finishedHandshake { self.catchUp() } else { self.began = Date(); self.authenticationTests() }
                }
            }
        }
    }
    func send(_ request: [String: Any], completion: @escaping ([String: Any]) -> Void) {
        guard pending == nil, let phone = phone, rx != nil else { die("Request while busy/disconnected") }
        pendingID = request["id"] as! String; pending = completion; deadline = Date().addingTimeInterval(18)
        let data = try! JSONSerialization.data(withJSONObject: request, options: [.sortedKeys])
        frameID &+= 1
        writes = frame(secure!.seal(data), id: frameID, size: phone.maximumWriteValueLength(for: .withResponse)); writeNext()
    }
    func sendHandshake(_ data: Data, reply: @escaping (Data) -> Void) {
        guard let phone = phone, rx != nil, pending == nil, handshakeReply == nil else { die("Handshake while busy") }
        handshakeReply = reply; deadline = Date().addingTimeInterval(20); frameID &+= 1
        writes = frame(data, id: frameID, size: phone.maximumWriteValueLength(for: .withResponse)); writeNext()
    }
    func request(_ target: String, key alternate: Data? = nil, time: Int64? = nil, completion: @escaping ([String: Any]) -> Void) {
        requestID += 1
        send(wireRequest(id: "\(requestID)", target: target, key: alternate ?? key, time: time), completion: completion)
    }
    func writeNext() {
        guard !writing, !writes.isEmpty, let phone = phone, let rx = rx else { return }
        writing = true; phone.writeValue(writes.removeFirst(), for: rx, type: .withResponse)
    }
    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error { die("Write: \(error)") }; writing = false; writeNext()
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error { die("Indication: \(error)") }
        guard characteristic.uuid == txID, let data = characteristic.value, data.count >= 7 else { die("Short indication") }
        let b = [UInt8](data), id = Int(b[1]) << 8 | Int(b[2]), index = Int(b[3]) << 8 | Int(b[4]), count = Int(b[5]) << 8 | Int(b[6])
        guard b[0] == 1, count > 0, count <= 4096, index < count, data.count <= 512 else { die("Malformed frame") }
        if receiveID == nil { guard index == 0 else { die("Missing first fragment") }; receiveID = id; receiveCount = count; receivedAt = Date() }
        guard receiveID == id, receiveCount == count, receiveNext == index, Date().timeIntervalSince(receivedAt) < 15 else { die("Fragment order/timeout") }
        receive.append(data.dropFirst(7)); receiveNext += 1
        guard receive.count <= 32768 else { die("Oversized message") }
        if receiveNext != count { return }
        let bytes = receive; receive.removeAll(); receiveID = nil; receiveNext = 0
        if let reply = handshakeReply {
            handshakeReply = nil; deadline = nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { reply(bytes) }; return
        }
        guard let session = secure, session.authenticated else { die("Telemetry before authenticated session") }
        let clear = session.open(bytes)
        guard let object = try? JSONSerialization.jsonObject(with: clear) as? [String: Any], let completion = pending,
              object["id"] as? String == pendingID else { die("Unexpected response/id") }
        pending = nil; deadline = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { completion(object) }
    }
    func body(_ response: [String: Any], expected: Int = 200) -> [String: Any] {
        check((response["status"] as? Int) == expected, "status \(expected)")
        guard let body = response["body"] as? [String: Any] else { die("JSON body missing") }; return body
    }
    func authenticationTests() {
        let wrong = hmac(Data((code == "000000" ? "111111" : "000000").utf8), Data("oabl-link/v1/pair".utf8))
        request("/v1/now", key: wrong) { r in
            _ = self.body(r, expected: 401)
            self.request("/v1/hello") { response in
                let hello = self.body(response)
                self.check(hello["schema"] as? Int == 1 && hello["probe"] as? String == "simulated", "real BLE hello carries explicitly simulated schema 1 data")
                let signed = wireRequest(id: "replay-check", target: "/v1/now", key: self.key)
                self.send(signed) { r in
                    _ = self.body(r)
                    self.send(signed) { r in
                        _ = self.body(r, expected: 401)
                        self.request("/v1/now", time: Int64(Date().timeIntervalSince1970 * 1000) - 60000) { r in
                            _ = self.body(r, expected: 401); self.finishedHandshake = true; self.catchUp()
                        }
                    }
                }
            }
        }
    }
    func catchUp() {
        let after = latestSeq
        request("/v1/samples?after=\(after)&limit=25") { r in
            let body = self.body(r)
            guard let samples = body["samples"] as? [[String: Any]], let latest = body["latest_seq"] as? Int64 else { die("Samples malformed") }
            for sample in samples {
                guard let seq = sample["seq"] as? Int64, seq > self.latestSeq else { die("Catch-up order") }; self.latestSeq = seq
            }
            self.check(latest >= self.latestSeq, "catch-up ordered after \(after); received \(samples.count) samples; latest \(latest)")
            if self.latestSeq < latest { self.catchUp() } else { self.polling = true; self.poll() }
        }
    }
    func poll() {
        guard polling, pending == nil else { return }
        request("/v1/now") { r in
            let b = self.body(r)
            guard let t = b["t"] as? Int64, let values = b["values"] as? [String: [String: Any]],
                  let soc = values["soc"]?["v"] as? Double else { die("SOC/time missing: \(b)") }
            self.check(abs(Date().timeIntervalSince1970 * 1000 - Double(t)) < 10000, "fresh telemetry with \(values.count) signals")
            if self.originalSOC == nil { self.originalSOC = soc; self.firstTime = t }
            self.changedSOC = self.changedSOC || self.originalSOC != soc; self.lastTime = t; self.nowReads += 1
            log("NOW SOC \(soc)% · timestamp \(t) · reads \(self.nowReads) · connection \(self.connections)")
        }
    }
    func tick() {
        let elapsed = Date().timeIntervalSince(began)
        if CommandLine.arguments.contains("--preflight") {
            if elapsed > 300 { die("Bluetooth permission/state preflight timed out") }; return
        }
        if let deadline = deadline, Date() > deadline { die("Response timed out") }
        if elapsed > duration + 45 { die("Test deadline exceeded; check phone service and Mac Bluetooth permission") }
        if phone == nil { return }
        if elapsed >= duration && pending == nil && polling {
            check(connections >= 2, "reconnected after intentional disconnect")
            check(nowReads >= 10 && lastTime > firstTime && changedSOC, "sustained fresh and changing simulated telemetry")
            log("RESULT PASS · \(checks) checks · \(nowReads) live reads · \(connections) connections · \(Int(elapsed))s · BLE only")
            timer?.invalidate(); if let phone = phone { central.cancelPeripheralConnection(phone) }; exit(0)
        }
        if elapsed > 15 && !testReconnect && polling && pending == nil {
            testReconnect = true; reconnecting = true; polling = false
            log("TEST intentional BLE disconnect; next connection must catch up after sequence \(latestSeq)")
            if let phone = phone { central.cancelPeripheralConnection(phone) }; return
        }
        if polling && pending == nil && Int(elapsed) % 2 == 0 { poll() }
    }
}
final class AppDelegate: NSObject, NSApplicationDelegate {
    let lab = Lab()
    var window: NSWindow!
    var peripheral: MacPeripheral?
    func applicationDidFinishLaunching(_ notification: Notification) {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 620, height: 180),
                          styleMask: [.titled, .closable, .miniaturizable], backing: .buffered, defer: false)
        window.title = "OABL Bluetooth Lab"
        let label = NSTextField(wrappingLabelWithString: "Waiting for Bluetooth permission…")
        label.frame = NSRect(x: 24, y: 68, width: 572, height: 76); label.font = .systemFont(ofSize: 16)
        window.contentView?.addSubview(label); statusLabel = label
        let detail = NSTextField(wrappingLabelWithString: "Phone → Mac · simulated data only\nResults: " + logPath)
        detail.frame = NSRect(x: 24, y: 10, width: 572, height: 50); window.contentView?.addSubview(detail)
        window.center(); window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
        if CommandLine.arguments.contains("--peripheral") { peripheral = MacPeripheral(); peripheral!.start() }
        else { lab.start() }
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}
let application = NSApplication.shared
application.setActivationPolicy(.regular)
let delegate = AppDelegate(); application.delegate = delegate
application.run()

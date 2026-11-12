import Foundation
import CoreBluetooth
import CryptoKit

/** Independent simulated peripheral used to exercise the production Android central. */
final class MacPeripheral: NSObject, CBPeripheralManagerDelegate {
    var manager: CBPeripheralManager!
    let key = hmac(Data(code.utf8), Data("oabl-link/v1/pair".utf8))
    let began = Date()
    var peer: CBCentral?, tx: CBMutableCharacteristic?, session: BleSecureSession?
    var incoming = Data(), incomingID: Int?, next = 0, total = 0, assembledAt = Date()
    var queue: [Data] = [], messageID: UInt16 = 0, proofs = false
    var nonces: [String: Int64] = [:], reads = 0
    var configured = false
    func start() {
        log("START Mac simulated BLE peripheral for Android-client verification")
        manager = CBPeripheralManager(delegate: self, queue: nil)
        log("Mac peripheral initial state \(manager.state.rawValue); authorization \(CBManager.authorization.rawValue)")
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            log("Mac peripheral state after initialization \(self.manager.state.rawValue)")
            self.peripheralManagerDidUpdateState(self.manager)
        }
        Timer.scheduledTimer(withTimeInterval: 180, repeats: false) { _ in log("Peripheral test window ended"); exit(0) }
    }
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn else { log("Peripheral Bluetooth state \(peripheral.state.rawValue)"); return }
        if configured { return }; configured = true
        let service = CBMutableService(type: serviceID, primary: true)
        let receive = CBMutableCharacteristic(type: rxID, properties: [.write], value: nil, permissions: [.writeEncryptionRequired])
        tx = CBMutableCharacteristic(type: txID, properties: [.indicate], value: nil, permissions: [])
        service.characteristics = [receive, tx!]; peripheral.add(service)
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error { die("Peripheral service: \(error)") }
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceID], CBAdvertisementDataLocalNameKey: "OABL Mac simulator"])
    }
    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error { die("Peripheral advertising: \(error)") }; log("Advertising simulated OABL service for Android central")
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        if peer != nil && peer?.identifier != central.identifier { return }
        peer = central; session = BleSecureSession(key: key, server: true); proofs = false
        incoming.removeAll(); incomingID = nil; next = 0; queue.removeAll()
        log("Android subscribed · maximum indication \(central.maximumUpdateValueLength)")
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard peer?.identifier == central.identifier else { return }
        peer = nil; session = nil; incoming.removeAll(); incomingID = nil; queue.removeAll(); next = 0
        log("Android disconnected/unsubscribed; waiting for reconnect")
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            guard request.characteristic.uuid == rxID, request.offset == 0, peer?.identifier == request.central.identifier,
                  let value = request.value, value.count >= 7, value.count <= 512 else { peripheral.respond(to: request, withResult: .unlikelyError); continue }
            let b = [UInt8](value), id = Int(b[1]) << 8 | Int(b[2]), index = Int(b[3]) << 8 | Int(b[4]), count = Int(b[5]) << 8 | Int(b[6])
            if incomingID == nil { incomingID = id; next = 0; total = count; assembledAt = Date() }
            guard b[0] == 1, count > 0, count <= 4096, id == incomingID, count == total, index == next,
                  incoming.count + value.count - 7 <= 32768, Date().timeIntervalSince(assembledAt) <= 15 else {
                incoming.removeAll(); incomingID = nil; peripheral.respond(to: request, withResult: .unlikelyError); continue
            }
            incoming.append(value.dropFirst(7)); next += 1
            peripheral.respond(to: request, withResult: .success)
            if next == count {
                let data = incoming; incoming.removeAll(); incomingID = nil; next = 0
                handle(data)
            }
        }
    }
    func handle(_ data: Data) {
        // CoreBluetooth can preserve a bonded central's CCCD across a brief disconnect without
        // another subscribe callback. A new client hello always starts a fresh simulated session.
        if data.count >= 2 && data[data.startIndex] == 2 && data[data.startIndex + 1] == 1 {
            session = BleSecureSession(key: key, server: true); proofs = false
        }
        guard let session = session, let peer = peer else { die("Peripheral message without subscriber") }
        let answer: Data
        if !proofs { answer = session.acceptHello(data); proofs = true }
        else if !session.authenticated { answer = session.acceptProof(data)!; log("Android encrypted session authenticated") }
        else {
            let clear = session.open(data)
            guard let request = try? JSONSerialization.jsonObject(with: clear) as? [String: Any] else { die("Android RPC JSON invalid") }
            answer = session.seal(rpc(request)); reads += 1
        }
        messageID &+= 1; queue.append(contentsOf: frame(answer, id: messageID, size: peer.maximumUpdateValueLength)); flush()
    }
    func flush() {
        guard let tx = tx, let peer = peer else { return }
        while let packet = queue.first {
            if !manager.updateValue(packet, for: tx, onSubscribedCentrals: [peer]) { return }
            queue.removeFirst()
        }
    }
    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) { flush() }
    func rpc(_ r: [String: Any]) -> Data {
        let now = Int64(Date().timeIntervalSince1970 * 1000), id = r["id"] as? String ?? ""
        let target = r["target"] as? String ?? "", time = r["time"] as? Int64 ?? 0, nonce = r["nonce"] as? String ?? ""
        nonces = nonces.filter { now - $0.value <= 30000 }
        let canonical = "GET\n\(target)\n\(time)\n\(nonce)\n\(hex(Data(SHA256.hash(data: Data()))))"
        let signature = hex(hmac(key, Data(canonical.utf8)))
        var status = 200, body: [String: Any] = [:]
        if abs(now - time) > 30000 || nonce.isEmpty || nonces[nonce] != nil || r["signature"] as? String != signature {
            status = 401; body = ["error": "unauthorized"]
        } else {
            nonces[nonce] = time
            let latest = max(1, Int64(Date().timeIntervalSince(began) / 2) + 1)
            if target == "/v1/hello" {
                body = ["schema": 1, "probe": "simulated", "link": "mac-simulator-v2", "app_version": "0.1.2-lab", "model": "Mac BLE simulator", "android": "none",
                        "signals": [["key": "soc", "unit": "%"], ["key": "range_est", "unit": "km"]]]
            } else if target == "/v1/now" {
                body = ["t": now, "values": ["soc": ["v": 40 + Double(latest) / 10, "t": now], "range_est": ["v": 200, "t": now]]]
            } else if target.hasPrefix("/v1/samples?") {
                let parts = target.components(separatedBy: "after=").last?.components(separatedBy: "&") ?? []
                let after = Int64(parts.first ?? "") ?? 0
                let end = min(latest, after + 25)
                let samples: [[String: Any]] = end > after ? ((after + 1)...end).map { seq in
                    ["seq": seq, "t": Int64(began.timeIntervalSince1970 * 1000) + (seq - 1) * 2000, "values": ["soc": 40 + Double(seq) / 10]]
                } : []
                body = ["after": after, "latest_seq": latest, "samples": samples]
            } else { status = 404; body = ["error": "not found"] }
        }
        log("Android read \(target) → \(status)")
        return try! JSONSerialization.data(withJSONObject: ["id": id, "status": status, "body": body], options: [.sortedKeys])
    }
}

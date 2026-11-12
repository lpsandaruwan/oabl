import Foundation
import CryptoKit

/** Interoperates with the production Java v2 record layer. System Bluetooth pairing is also required. */
final class BleSecureSession {
    let server: Bool
    let key: Data
    let ephemeral = P256.KeyAgreement.PrivateKey()
    var clientHello = Data(), transcript = Data(), proofKey = Data()
    var txKey = Data(), rxKey = Data(), txPrefix = Data(), rxPrefix = Data()
    var authenticated = false, sent: UInt64 = 0, received: UInt64 = 0
    init(key: Data, server: Bool = false) { self.key = key; self.server = server }
    func begin() -> Data {
        let pub = ephemeral.publicKey.derRepresentation
        clientHello = Data([2, 1, UInt8(pub.count >> 8), UInt8(pub.count & 255)]) + pub
            + Data((0..<16).map { _ in UInt8.random(in: 0...255) })
        return clientHello
    }
    private func expand(_ prk: Data, _ label: String, _ size: Int) -> Data {
        Data(hmac(prk, Data(("oabl-ble/v2/" + label).utf8) + transcript + Data([1])).prefix(size))
    }
    func acceptHello(_ message: Data) -> Data {
        let b = [UInt8](message)
        guard b.count >= 20, b.count <= 180, b[0] == 2, b[1] == (server ? 1 : 2) else { die("Invalid encrypted BLE handshake") }
        let length = Int(b[2]) << 8 | Int(b[3])
        guard length + 20 == b.count, let pub = try? P256.KeyAgreement.PublicKey(derRepresentation: message.subdata(in: 4..<(4 + length))),
              let shared = try? ephemeral.sharedSecretFromKeyAgreement(with: pub) else { die("Invalid P-256 key") }
        let ourHello: Data
        if server {
            let pub = ephemeral.publicKey.derRepresentation
            ourHello = Data([2, 2, UInt8(pub.count >> 8), UInt8(pub.count & 255)]) + pub + Data((0..<16).map { _ in UInt8.random(in: 0...255) })
            clientHello = message
            transcript = Data(SHA256.hash(data: message + ourHello))
        } else { ourHello = clientHello; transcript = Data(SHA256.hash(data: clientHello + message)) }
        let prk = hmac(key, shared.withUnsafeBytes { Data($0) })
        proofKey = expand(prk, "proof", 32)
        txKey = expand(prk, server ? "s2c-key" : "c2s-key", 32); rxKey = expand(prk, server ? "c2s-key" : "s2c-key", 32)
        txPrefix = expand(prk, server ? "s2c-nonce" : "c2s-nonce", 4); rxPrefix = expand(prk, server ? "c2s-nonce" : "s2c-nonce", 4)
        if server { return ourHello }
        return Data([2, 3]) + hmac(proofKey, Data("client".utf8) + transcript)
    }
    @discardableResult func acceptProof(_ message: Data) -> Data? {
        let expected = Data([2, server ? 3 : 4]) + hmac(proofKey, Data((server ? "client" : "server").utf8) + transcript)
        guard message.count == expected.count, zip(message, expected).reduce(UInt8(0), { $0 | ($1.0 ^ $1.1) }) == 0 else { die("Pairing code/server proof rejected") }
        authenticated = true
        return server ? Data([2, 4]) + hmac(proofKey, Data("server".utf8) + transcript) : nil
    }
    private func counter(_ value: UInt64) -> Data { withUnsafeBytes(of: value.bigEndian) { Data($0) } }
    func seal(_ message: Data) -> Data {
        guard authenticated, sent < UInt64(Int64.max), message.count <= 32742 else { die("Invalid encrypted record") }
        sent += 1
        let header = Data([2, server ? 6 : 5]) + counter(sent)
        do {
            let box = try AES.GCM.seal(message, using: SymmetricKey(data: txKey), nonce: AES.GCM.Nonce(data: txPrefix + counter(sent)), authenticating: transcript + header)
            return header + box.ciphertext + box.tag
        } catch { die("BLE encryption failed: \(error)") }
    }
    func open(_ message: Data) -> Data {
        let b = [UInt8](message)
        guard authenticated, b.count >= 26, b.count <= 32768, b[0] == 2, b[1] == (server ? 5 : 6) else { die("Invalid BLE ciphertext") }
        let seq = b[2..<10].reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        guard seq == received + 1 else { die("BLE record replay/order") }
        do {
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: rxPrefix + counter(seq)),
                ciphertext: message.subdata(in: 10..<(message.count - 16)), tag: message.suffix(16))
            let clear = try AES.GCM.open(box, using: SymmetricKey(data: rxKey), authenticating: transcript + message.prefix(10))
            received = seq; return clear
        } catch { die("BLE response authentication failed: \(error)") }
    }
}

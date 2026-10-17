import Foundation

/// The versioned stdio wire between a JVM host and `ampere-helper` (AMPR-375).
///
/// One JSON object per line, in both directions:
///
/// - a **request** carries `v`, `id`, `op` and, for some ops, `params`;
/// - a **reply** carries `v`, `id` and `ok`, then `result` when `ok` is true and `error`
///   (`code`, `message`) when it is not.
///
/// The frame shape, the op names, the error codes and the availability tokens are the ones
/// Socket's `socket-helper` speaks for the same model (SCKT-853), and this helper serves every
/// inference op that one does: a host built for it runs unchanged against this binary. The reverse
/// is not true. That helper reads only `prompt`, so a request's `instructions`, `maxOutputTokens`
/// and `temperature` would be dropped, and it answers `inference.count` with `unknown_op`. An op
/// a helper lacks is always `unknown_op`, never a malformed reply.
///
/// `probe` is answered whatever version the caller asked with. That is what lets an old helper
/// beside a new host read as unavailable with a reason rather than as a broken call. Every other
/// op is refused with `unsupported_protocol` when the caller's `v` is outside the range below.
///
/// Add a field or an op without bumping the version; change what one means, or remove it, with a
/// bump. `inference.count` joined version 1 that way: a helper without it answers `unknown_op`,
/// which the host reads as "cannot count", and sizes the call by its estimate.
///
/// `ampere-cli/src/jvmMain/.../cli/ondevice/AmpereHelperWire.kt` is the other half. The two are
/// kept in step by hand.
enum Wire {
    /// Bumped when a frame's meaning changes.
    static let version = 1
    static let minimumSupported = 1
    static let maximumSupported = 1

    static let build = "ampere-helper \(version) (FoundationModels inference)"

    /// The services this binary serves, as `probe` reports them. A host checks for its own before
    /// sending anything else.
    enum Service {
        static let inference = "inference"
    }

    enum Op {
        static let probe = "probe"
        static let inferenceProbe = "inference.probe"
        static let inferenceGenerate = "inference.generate"
        static let inferenceCount = "inference.count"
    }

    enum ErrorCode {
        static let unsupportedProtocol = "unsupported_protocol"
        static let unknownOp = "unknown_op"
        static let malformedRequest = "malformed_request"
        static let platformRefused = "platform_refused"

        /// FoundationModels took the request and did not finish it: a guardrail, a context
        /// overflow, a model error. The message is the framework's own. The host reports it as a
        /// failed generation, never as an unavailable model.
        static let generationFailed = "generation_failed"
    }
}

/// A request this helper refused, with the code the host switches on and the reason it shows.
struct HelperFailure: Error {
    let code: String
    let message: String
}

// MARK: - Frames

struct RequestFrame: Decodable {
    let v: Int?
    let id: String
    let op: String
    let params: JSONValue?
}

/// Written with `JSONSerialization` rather than `Codable`: every reply body is already a
/// dictionary built by the service, and a generated encoder would mean a type per op for no
/// reader's benefit.
struct ReplyFrame {
    let id: String
    let ok: Bool
    let body: [String: Any]

    func encoded() -> String {
        var frame: [String: Any] = ["v": Wire.version, "id": id, "ok": ok]
        if ok {
            frame["result"] = body
        } else {
            frame["error"] = body
        }
        guard let data = try? JSONSerialization.data(withJSONObject: frame, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8)
        else {
            // Unencodable means a value the service put in, which is this binary's bug, not the
            // caller's. Say so in a frame the caller can still parse, rather than writing nothing
            // and leaving it to wait out its deadline.
            return "{\"error\":{\"code\":\"\(Wire.ErrorCode.platformRefused)\",\"message\":\"unencodable reply\"}," +
                "\"id\":\"\(id)\",\"ok\":false,\"v\":\(Wire.version)}"
        }
        return text
    }

    static func success(id: String, result: [String: Any]) -> ReplyFrame {
        ReplyFrame(id: id, ok: true, body: result)
    }

    static func failure(id: String, _ failure: HelperFailure) -> ReplyFrame {
        ReplyFrame(id: id, ok: false, body: ["code": failure.code, "message": failure.message])
    }
}

// MARK: - A minimal JSON value

/// Just enough of a JSON tree to read `params` without a `Codable` type per op.
enum JSONValue: Decodable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else {
            self = .null
        }
    }

    /// The field named `key`, or nil when it is absent or `null`.
    subscript(key: String) -> JSONValue? {
        guard case let .object(fields) = self else { return nil }
        let value = fields[key]
        if case .some(.null) = value { return nil }
        return value
    }

    var stringValue: String? {
        guard case let .string(value) = self else { return nil }
        return value
    }

    var intValue: Int? {
        guard case let .number(value) = self, value.rounded() == value, abs(value) < Double(Int.max) else {
            return nil
        }
        return Int(value)
    }

    var doubleValue: Double? {
        guard case let .number(value) = self else { return nil }
        return value
    }
}

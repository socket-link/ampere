import Foundation
import XCTest
@testable import AmpereHelper

/// One request line through `AmpereHelper.handle(line:inference:)`, the function `main` hands every
/// line it reads, and its reply decoded the way a host would read it.
///
/// The cases go through the real `FoundationModelsInference`. A refusal the wire decides is made
/// before the model is asked anything, so the model-free cases need no fake to stay model-free.
struct Exchange {
    let id: String?
    let ok: Bool?
    let v: Int?
    let result: [String: Any]?
    let error: [String: Any]?

    var errorCode: String? { error?["code"] as? String }

    static func send(_ line: String, file: StaticString = #filePath, line number: UInt = #line) async throws -> Exchange {
        let reply = await AmpereHelper.handle(line: line, inference: FoundationModelsInference())
        let text = try XCTUnwrap(reply, "no reply to \(line)", file: file, line: number)
        XCTAssertFalse(text.contains("\n"), "a reply is one line: \(text)", file: file, line: number)
        let decoded = try JSONSerialization.jsonObject(with: Data(text.utf8))
        let frame = try XCTUnwrap(decoded as? [String: Any], "not a JSON object: \(text)", file: file, line: number)
        return Exchange(
            id: frame["id"] as? String,
            ok: frame["ok"] as? Bool,
            v: frame["v"] as? Int,
            result: frame["result"] as? [String: Any],
            error: frame["error"] as? [String: Any]
        )
    }

    /// A request line, with its params written as JSON.
    static func request(id: String = "r1", v: Int? = 1, op: String, params: String? = nil) -> String {
        var fields = ["\"id\":\"\(id)\"", "\"op\":\"\(op)\""]
        if let v { fields.append("\"v\":\(v)") }
        if let params { fields.append("\"params\":\(params)") }
        return "{" + fields.joined(separator: ",") + "}"
    }
}

extension JSONValue {
    /// The params of a request, as the helper decodes them.
    static func params(_ json: String) throws -> JSONValue {
        try JSONDecoder().decode(JSONValue.self, from: Data(json.utf8))
    }
}

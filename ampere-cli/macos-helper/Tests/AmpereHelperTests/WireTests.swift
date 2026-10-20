import Foundation
import XCTest
@testable import AmpereHelper

/// The helper's wire, without the model (AMPR-470).
///
/// Every case here is decided before `FoundationModelsInference` asks the system anything, so the
/// suite passes the same with Apple Intelligence on, off, or on a Mac that has never had it. The
/// JVM half's suite drives a fake helper script and so clears nothing about this binary; these
/// are the refusals the 2026-10-17 hand check made, kept.
final class WireTests: XCTestCase {

    // MARK: - probe

    func testProbeIsAnsweredWhateverVersionAsked() async throws {
        for v in [nil, 0, 1, 2, 99] {
            let reply = try await Exchange.send(Exchange.request(id: "p\(v ?? -1)", v: v, op: Wire.Op.probe))

            XCTAssertEqual(reply.ok, true, "probe at v \(String(describing: v))")
            XCTAssertEqual(reply.id, "p\(v ?? -1)")
            XCTAssertEqual(reply.v, Wire.version, "a reply is written in the helper's own version")
            XCTAssertEqual(reply.result?["protocolMin"] as? Int, Wire.minimumSupported)
            XCTAssertEqual(reply.result?["protocolMax"] as? Int, Wire.maximumSupported)
            XCTAssertEqual(reply.result?["services"] as? [String], [Wire.Service.inference])
            XCTAssertEqual(reply.result?["build"] as? String, Wire.build)
        }
    }

    // MARK: - Versions

    func testEveryOtherOpOutsideTheRangeIsRefusedAsUnsupportedProtocol() async throws {
        let ops = [Wire.Op.inferenceProbe, Wire.Op.inferenceGenerate, Wire.Op.inferenceCount, "inference.translate"]
        for v in [0, 2] {
            for op in ops {
                let reply = try await Exchange.send(
                    Exchange.request(id: "\(op)@\(v)", v: v, op: op, params: #"{"prompt":"hi","text":"hi"}"#)
                )

                XCTAssertEqual(reply.ok, false, "\(op) at v \(v)")
                XCTAssertEqual(reply.id, "\(op)@\(v)")
                XCTAssertEqual(reply.errorCode, Wire.ErrorCode.unsupportedProtocol, "\(op) at v \(v)")
                XCTAssertNil(reply.result)
            }
        }
    }

    func testARequestWithoutAVersionIsReadAsTheCurrentOne() async throws {
        let reply = try await Exchange.send(Exchange.request(v: nil, op: "inference.translate"))

        XCTAssertEqual(reply.errorCode, Wire.ErrorCode.unknownOp, "past the version check, refused by op")
    }

    // MARK: - Refusals

    func testAnUnknownOpIsRefusedByName() async throws {
        let reply = try await Exchange.send(Exchange.request(id: "u1", op: "inference.translate"))

        XCTAssertEqual(reply.ok, false)
        XCTAssertEqual(reply.id, "u1")
        XCTAssertEqual(reply.errorCode, Wire.ErrorCode.unknownOp)
        XCTAssertEqual(reply.error?["message"] as? String, "unknown op inference.translate")
    }

    func testALineThatIsNotARequestFrameIsRefusedWithAnEmptyId() async throws {
        let lines = [
            "not json",
            "{\"id\":",
            "[1,2,3]",
            #"{"v":1,"op":"probe"}"#,
            #"{"v":1,"id":"m1"}"#,
            #"{"v":"one","id":"m1","op":"probe"}"#,
        ]
        for line in lines {
            let reply = try await Exchange.send(line)

            XCTAssertEqual(reply.ok, false, line)
            XCTAssertEqual(reply.id, "", "nothing to correlate with: \(line)")
            XCTAssertEqual(reply.errorCode, Wire.ErrorCode.malformedRequest, line)
        }
    }

    func testABlankLineIsNotAnswered() async {
        for line in ["", "   ", "\t", "\r"] {
            let reply = await AmpereHelper.handle(line: line, inference: FoundationModelsInference())
            XCTAssertNil(reply, "\(line.debugDescription) is answered")
        }
    }

    func testAGenerationWithoutAPromptIsMalformed() async throws {
        let params: [String?] = [nil, "{}", #"{"prompt":""}"#, #"{"prompt":null}"#, #"{"prompt":42}"#,
                                 #"{"instructions":"Be brief."}"#]
        for json in params {
            let reply = try await Exchange.send(Exchange.request(id: "g1", op: Wire.Op.inferenceGenerate, params: json))

            XCTAssertEqual(reply.id, "g1")
            XCTAssertEqual(reply.errorCode, Wire.ErrorCode.malformedRequest, json ?? "no params")
        }
    }

    func testACountWithNeitherTextNorPromptIsMalformed() async throws {
        let params: [String?] = [nil, "{}", #"{"instructions":"Be brief."}"#, #"{"text":7,"prompt":false}"#]
        for json in params {
            let reply = try await Exchange.send(Exchange.request(id: "c1", op: Wire.Op.inferenceCount, params: json))

            XCTAssertEqual(reply.id, "c1")
            XCTAssertEqual(reply.errorCode, Wire.ErrorCode.malformedRequest, json ?? "no params")
        }
    }

    // MARK: - Reading a request

    func testTheTemperatureIsClampedToZeroToOne() throws {
        let table: [(String, Double?)] = [
            ("{}", nil),
            (#"{"temperature":null}"#, nil),
            (#"{"temperature":"0.5"}"#, nil),
            (#"{"temperature":-0.5}"#, 0),
            (#"{"temperature":0}"#, 0),
            (#"{"temperature":0.3}"#, 0.3),
            (#"{"temperature":1}"#, 1),
            (#"{"temperature":1.5}"#, 1),
            (#"{"temperature":1e9}"#, 1),
        ]
        for (json, expected) in table {
            XCTAssertEqual(FoundationModelsInference.temperature(from: try JSONValue.params(json)), expected, json)
        }
        XCTAssertNil(FoundationModelsInference.temperature(from: nil))
    }

    func testTheCapIsSentOnlyWhenItIsAPositiveWholeNumber() throws {
        let table: [(String, Int?)] = [
            ("{}", nil),
            (#"{"maxOutputTokens":null}"#, nil),
            (#"{"maxOutputTokens":"16"}"#, nil),
            (#"{"maxOutputTokens":-5}"#, nil),
            (#"{"maxOutputTokens":0}"#, nil),
            (#"{"maxOutputTokens":16.5}"#, nil),
            (#"{"maxOutputTokens":1}"#, 1),
            (#"{"maxOutputTokens":16}"#, 16),
            (#"{"maxOutputTokens":16.0}"#, 16),
            (#"{"maxOutputTokens":4096}"#, 4096),
        ]
        for (json, expected) in table {
            XCTAssertEqual(FoundationModelsInference.maximumResponseTokens(from: try JSONValue.params(json)), expected, json)
        }
        XCTAssertNil(FoundationModelsInference.maximumResponseTokens(from: nil))
    }

    func testACountWithAPromptCountsTheRequestAndOtherwiseTheText() throws {
        typealias Input = FoundationModelsInference.CountInput
        let table: [(String, Input?)] = [
            ("{}", nil),
            (#"{"instructions":"Be brief."}"#, nil),
            (#"{"text":"hello"}"#, .text("hello")),
            (#"{"text":""}"#, .text("")),
            (#"{"text":"hello","instructions":"Be brief."}"#, .text("hello")),
            (#"{"prompt":"hello"}"#, .request(instructions: nil, prompt: "hello")),
            (#"{"prompt":"hello","instructions":"Be brief."}"#, .request(instructions: "Be brief.", prompt: "hello")),
            (#"{"prompt":"hello","instructions":null}"#, .request(instructions: nil, prompt: "hello")),
            (#"{"prompt":"hello","text":"other"}"#, .request(instructions: nil, prompt: "hello")),
            (#"{"prompt":7,"text":"other"}"#, .text("other")),
        ]
        for (json, expected) in table {
            XCTAssertEqual(FoundationModelsInference.countInput(from: try JSONValue.params(json)), expected, json)
        }
        XCTAssertNil(FoundationModelsInference.countInput(from: nil))
    }

    // MARK: - JSONValue

    func testJSONValueReadsOnlyWhatItsAccessorsName() throws {
        let value = try JSONValue.params(#"{"s":"x","n":3,"f":2.5,"b":true,"z":null,"a":[1],"o":{"k":"v"}}"#)

        XCTAssertEqual(value["s"]?.stringValue, "x")
        XCTAssertEqual(value["n"]?.intValue, 3)
        XCTAssertEqual(value["n"]?.doubleValue, 3)
        XCTAssertNil(value["f"]?.intValue, "a fraction is not a whole number")
        XCTAssertEqual(value["f"]?.doubleValue, 2.5)
        XCTAssertNil(value["b"]?.doubleValue, "a boolean is not a number")
        XCTAssertNil(value["b"]?.stringValue)
        XCTAssertNil(value["z"], "null reads as absent")
        XCTAssertNil(value["missing"])
        XCTAssertEqual(value["o"]?["k"]?.stringValue, "v")
        XCTAssertNil(value["a"]?["k"], "only an object has fields")
        XCTAssertNil(value["n"]?.stringValue)
    }
}

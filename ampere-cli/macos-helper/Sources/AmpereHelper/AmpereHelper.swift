import Foundation

/// `ampere-helper`: one request per line on stdin, one reply per line on stdout (AMPR-375).
///
/// ## It cannot outlive its caller
///
/// The JVM host terminates this process's group when it closes the engine and when a request
/// misses its deadline. A host that is `SIGKILL`ed can do neither, so the guarantee lives here:
/// stdin reaching EOF means the host is gone, and this process exits at once, in the middle of a
/// generation if that is where it is. Nothing the JVM does on its side can promise that.
///
/// ## One request at a time
///
/// The host writes a request only once the one before it has been answered, and this side keeps
/// to the same order. Lines are read on a thread of their own, because a blocking read cannot be
/// woken and EOF must be seen even while a generation runs. They are then handled one after
/// another, in the order they arrived, so two generations never compete for the model and two
/// replies never interleave on stdout.
@main
struct AmpereHelper {
    static func main() async {
        let inference = FoundationModelsInference()
        let lines = AsyncStream<String> { continuation in
            let reader = Thread {
                while let line = readLine(strippingNewline: true) {
                    continuation.yield(line)
                }
                // EOF: the host closed the pipe or died with it open. Nothing more can arrive, and
                // a generation nobody will read is not worth finishing.
                exit(0)
            }
            reader.name = "ampere-helper.stdin"
            reader.start()
        }
        for await line in lines {
            if let reply = await handle(line: line, inference: inference) {
                FileHandle.standardOutput.write(Data((reply + "\n").utf8))
            }
        }
    }

    /// The reply line for one request line, or nil for a blank one.
    static func handle(line: String, inference: FoundationModelsInference) async -> String? {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        guard let data = trimmed.data(using: .utf8),
              let request = try? JSONDecoder().decode(RequestFrame.self, from: data)
        else {
            // No id to correlate with, so this cannot be the reply anyone is waiting for. The host
            // drops it; it still says what went wrong for whoever reads a captured stream.
            return ReplyFrame.failure(
                id: "",
                HelperFailure(code: Wire.ErrorCode.malformedRequest, message: "not a request frame")
            ).encoded()
        }

        // `probe` answers whatever version asked, so a mismatch reads as a range the host can
        // compare rather than as a failed call. Everything else is refused by name.
        let asked = request.v ?? Wire.version
        let speakable = (Wire.minimumSupported...Wire.maximumSupported).contains(asked)
        guard request.op == Wire.Op.probe || speakable else {
            return ReplyFrame.failure(
                id: request.id,
                HelperFailure(
                    code: Wire.ErrorCode.unsupportedProtocol,
                    message: "this helper speaks protocol \(Wire.minimumSupported)–\(Wire.maximumSupported), " +
                        "the caller asked for \(asked)"
                )
            ).encoded()
        }

        let outcome: Result<[String: Any], HelperFailure>
        switch request.op {
        case Wire.Op.probe:
            outcome = .success([
                "protocolMin": Wire.minimumSupported,
                "protocolMax": Wire.maximumSupported,
                "build": Wire.build,
                "services": [Wire.Service.inference],
            ])
        case Wire.Op.inferenceProbe:
            outcome = .success(inference.probe())
        case Wire.Op.inferenceGenerate:
            outcome = await inference.generate(request.params)
        case Wire.Op.inferenceCount:
            outcome = await inference.count(request.params)
        default:
            outcome = .failure(HelperFailure(code: Wire.ErrorCode.unknownOp, message: "unknown op \(request.op)"))
        }

        switch outcome {
        case let .success(result): return ReplyFrame.success(id: request.id, result: result).encoded()
        case let .failure(failure): return ReplyFrame.failure(id: request.id, failure).encoded()
        }
    }
}

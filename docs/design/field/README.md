# Field foundation — design artifacts

The design record behind `link.socket.ampere.field` (AMPR-415, F-register rows F6 and
F27 on [AMPR-328](https://linear.app/miley/issue/AMPR-328)).

| File | What it is |
|------|------------|
| [`ampere-adr-world-state-primitives.md`](ampere-adr-world-state-primitives.md) | The ADR. Status **Accepted** 2026-10-11; the per-row verdicts live in the F-register on AMPR-328. |
| [`FieldPrimitives.kt`](FieldPrimitives.kt) | The reviewed Kotlin sketch of the primitives, kept as a design artifact. The shipped package is `ampere-core/src/commonMain/kotlin/link/socket/ampere/field/`; where the two differ, the package is authoritative and the differences are listed at the top of the sketch. |
| `recon/field-foundation.md` | **Not in this repository.** The four-slice recon (R0–R10, Tables A/B/C, mismatches C1–C62) that the ADR cites was written in the recon workspace for [AMPR-329](https://linear.app/miley/issue/AMPR-329) and was never committed; its findings are summarised in the Gate and Slice-4 sections of AMPR-328. If the file is recovered it belongs beside this README. |

## Provenance of these files

AMPR-328 lists all three artifacts as "pre-existing; commit under `docs/design/field/`
in the first W1 ticket". That commit never happened, and on 2026-10-11 none of the
three could be located in the repository, in Linear (issue attachments or documents),
or in any archived workspace. The ADR and the sketch here were therefore written down
from the locked F-register rows and the AMPR-415 specification, which is the only form
in which the reviewed design survives. They are the design as accepted, not a
transcription of a lost original.

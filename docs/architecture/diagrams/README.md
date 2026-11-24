# Diagrams

**Deviation from the suggested structure, with its reason.** The brief suggested a `diagrams/` directory. All
diagrams are instead inline Mermaid in the document that discusses them, and this directory holds only this
note.

Why: a diagram in a separate file drifts from the prose that explains it, and the brief asks specifically that
diagrams be consistent with the written design. Keeping them inline makes an inconsistency visible in one
screen rather than requiring two files to be read together. Mermaid also renders directly on GitHub and in most
markdown viewers, so nothing is lost by not exporting images, and exported images would go stale silently.

This revision is evidence for the choice: two diagrams had to change when the rules they illustrate changed
(the reconciliation sequence gained a second sweep, and the event-normalisation flowchart's tie-break was
wrong). Both were edited in the same file as the text that explains them.

Where each diagram lives:

| Diagram | File | Section |
|---|---|---|
| System context (C4 L1) | `../SAD.md` | §2.2 |
| Container architecture (C4 L2) | `../SAD.md` | §4 |
| Component architecture (C4 L3) | `../SAD.md` | §5 |
| **Reconciliation sequence — two sweeps** | `../SAD.md` | §5.1 — rewritten in rev 0.2 |
| **Event normalisation — identity, conflict, canonical order** | `../SAD.md` | §5.2 — rewritten in rev 0.2 |
| Finding state transitions | `../SAD.md` | §5.3 |
| Entity relationships | `../SAD.md` | §7 |
| Domain class model | `../domain-model.md` | §3 — updated with the five time fields |
| Failure-handling decision flow | `../failure-mode-analysis.md` | §2b |
| Trust boundaries | `../security-and-threat-model.md` | §2 |
| Milestone dependencies | `../implementation-roadmap.md` | end |

Eleven diagrams. If a rendered export is ever needed (for the seven-page report, say), generate it from the
inline source rather than maintaining a second copy here.

# Fork-lineage transcript fixtures

Captured from a real local-model run (ForkSessionMaker). Lineage:

    A (original, 2 exchanges: "AARDVARK", recall)   4b6f429e-efe2-459e-8720-56da16280fec.jsonl
      └─ fork ─► B (+ "secret number is 42")        29efebea-1d97-4a6c-b39d-207831740ae4.jsonl
                  └─ fork ─► C (+ "secret animal OTTER")  52d26748-15ae-4a11-b663-2b6d36195e29.jsonl

Key facts these fixtures encode (verified):
- Forks COPY the full prior history into the child file (self-contained).
- Copied messages KEEP their original `uuid` but are re-stamped with the child `sessionId`.
- UUID sets nest: uuids(A) ⊂ uuids(B) ⊂ uuids(C).
- No explicit parent/fork field on disk; lineage is recoverable via uuid-subset.

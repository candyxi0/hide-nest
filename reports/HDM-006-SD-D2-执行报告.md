# HDM-006 Slice D Final Mechanical Evidence

Status: HDM006_SLICE_D_FINAL_EVIDENCE_COMMANDER_READY_FOR_LOCAL_COMMIT
Baseline HEAD: 2425883ebf0cacafbe1b621916dfcc7296ecdacd
runner SHA-256: 0274393ABD555F6ECC8A14B1F5B603E2E9713E304FCDBF1DDF6F8E2C4006AB47

## Mechanical gate results

| Gate | Result |
|---|---|
| Maven offline clean verify | exit=0 |
| Surefire XML/tests/failures/errors/skipped | 21 / 266 / 0 / 0 / 0 |
| Node typecheck/lint/test/build | PASS / PASS / PASS / PASS |
| API/Worker default/Worker misconfigured | PASS / PASS / PASS |
| V001-V009/V010/generated | MATCH / MATCH / MATCH |
| Production guard/handler/OPERATIONAL producer | 0 / 0 / 0 |
| Body/secret/trivial assertion hits | 0 / 0 / 0 |
| Git index/staged/out-of-scope | MATCH / 0 / 0 |
| Added Docker containers-volumes-networks | 0-0-0 |
| Task process residue | 0 |

failedCriteria: []

This report is generated from the same-run Evidence by scripts/hdm006-sd-final-judge.ps1. Any failed real gate produces BLOCKED.

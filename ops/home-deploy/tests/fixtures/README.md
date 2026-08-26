# fixtures

Reusable templates for the hermetic counter-proof tests (`Test-DeployHome.ps1`).

- `sample-request.json` — example remote-deploy request payload (shape reference).
  The tests generate their own requests/fake repos at runtime; this file documents
  the request schema and serves as a static fixture for the `fixtures/**` layout.

All tests create fake git repos and fake home roots under a temp directory and
clean them up precisely. They never touch the real home host.

# Security projection prototype

Prototype for background phishing detection on Penpot designs. It is a spike,
not a product feature: it exists to measure whether a small **semantic
projection** of a file carries enough signal for a decision model to judge
phishing, without sending the raw (and huge) Penpot file.

The projection itself lives in the backend as an RPC command,
`get-semantic-file` (`backend/src/app/rpc/commands/semantic.clj`). This folder
only holds the Node prototype that consumes it.

## Pipeline

```
Penpot file
  -> get-semantic-file RPC         (prune: keep pages, frames, text, links)
  -> deterministic checks          (code: URLs, credential/urgency keywords)
  -> decision model via OpenRouter (judgment on the ambiguous)
  -> typed probabilities (noul answers)
```

The model call uses the OpenRouter **Decisions API**
(`POST /api/alpha/decisions`), not chat completions. Decision models answer
typed `noul` / `choice` / `score` questions about a `state` and return
probabilities, never text, which is exactly what this experiment needs. Models
that speak it include `typesafe/jev-1.13`, `upstage/solar-decide`,
`upstage/solar-decide-flash`, `liquid/d1`, `cloudflare/clef-flash` and
`inception/mercury-decide`.

The split is deliberate. Hard signals (raw-IP host, punycode host, brand token
outside its official domain, credential keywords) are computed in code and are
authoritative. The model only judges the parts that need judgment, and it is
documented to be steerable by adversarial content in the state, so it must not
be the only line of defense.

## What the projection keeps and drops

Kept:

- page names and frame (screen) names
- visible text of text shapes, in tree order
- external links (`open-url` interactions), with the label of the trigger shape
- internal navigations (`navigate`/overlay interactions), with the target frame name
- a per-frame count of images

Dropped: geometry (`x`, `y`, `width`, `height`, transforms, paths), fills,
strokes, shadows, ids, layout internals, media, components, colors,
typographies, tokens and every library. Component main instances are dropped
too: they are library content, not design content.

The projection is versioned (`projection-version`), so stored analyses can be
invalidated when the extractor changes.

## Running the prototype

Requirements: a running Penpot instance reachable from Node, an OpenRouter API
key, and Node 20+. The script imports `dotenv` from the repo root
`node_modules`, so run it from a checkout with dependencies installed.

Configuration and secrets come from a `.env` file (real environment variables
win over the file). Copy `.env.example` and fill it in:

```sh
cp .env.example .env
node analyze.mjs <file-id>
```

- `--dump` prints only the projection and the deterministic checks, no model call.
- `--request` prints the exact decisions request (`model`, `state`,
  `questions`) that would be sent, and stops. Use it to see what is built from
  the file id before spending a call.
- `--model <name>` overrides `OPENROUTER_MODEL`.
- `--env <path>` reads another `.env` file.

Authentication: either `PENPOT_ACCESS_TOKEN` (an access token, sent as
`Authorization: Token ...`) or `PENPOT_EMAIL` + `PENPOT_PASSWORD` (the script
logs in and reuses the session cookie).

The pure logic (URL scoring, deterministic checks, metrics) has tests:

```sh
node --test
```

## Comparing models

`compare.mjs` sends the same decisions request for one file to several models
and prints latency, cost and the key signals side by side:

```sh
node compare.mjs <file-id>
node compare.mjs <file-id> typesafe/jev-1.13 cloudflare/clef-flash
```

## Labeled set and evaluation

The model only ever sees the projection, so the model choice is measured on
labeled **projections**, not on files. `fixtures/labeled-projections.json` holds
24 entries `{ id, label, tag, projection }`: benign designs (including a
checkout and a corporate login, to catch false positives), phishing designs
(brand login, OTP, card, crypto, delivery fee), 4 ambiguous cases and 4
adversarial cases with prompt injection in the design text. Entries tagged
`ambiguous` are reported but excluded from the metrics.

`evaluate.mjs` runs every entry through every model, compares
`answers.is_phishing.noul` against the label at a threshold, and prints
precision / recall / FP / FN, cost and latency:

```sh
node evaluate.mjs
node evaluate.mjs --threshold 0.7
node evaluate.mjs typesafe/jev-1.13 liquid/d1
```

First run (24 entries, 12 benign / 12 phishing, threshold 0.5):

| model | precision | recall | F1 | p50 ms | cost (24 cases) |
|---|---|---|---|---|---|
| `typesafe/jev-1.13` | 1.00 | 1.00 | 1.00 | 358 | $0.001121 |
| `liquid/d1` | 1.00 | 1.00 | 1.00 | 406 | $0.003317 |
| `perplexity/pplx-decider-v1.1-27b` | 1.00 | 1.00 | 1.00 | 545 | $0.001445 |
| `cloudflare/clef-flash` | 0.92 | 1.00 | 0.96 | 348 | $0.000574 |
| `inception/mercury-decide:free` | 0.85 | 1.00 | 0.92 | 512 | $0.000000 |

Every model catches all 12 phishing cases, including the adversarial ones. The
differences are in false positives: `clef-flash` and `mercury-decide` are swayed
by the injected text in a benign design, and `mercury-decide` also flags a
benign corporate login. `pplx-decider` is perfect here but very polarized (many
answers at exactly 0), so it is less useful for a graded threshold.

Caveat: 24 synthetic cases is a first signal, not a verdict. The phishing cases
are clear; the benign, ambiguous and adversarial cases are what separates the
models. Replace the synthetic entries with real files when they exist.

## Validating the extractor on real files

The labeled set tests the model. `seed.mjs` tests the extractor: it creates
**real** Penpot files and reads the projection back.

It does not build shapes from scratch (the backend validates shapes against the
full schema). It clones a frame subtree from a template file you already have,
remaps the ids, and rewrites the text, the URLs and the frame name. Then it
calls `get-semantic-file` and checks the expected signals survived:

```sh
node seed.mjs --template <file-id>          # creates, checks, deletes
node seed.mjs --template <file-id> --keep   # leaves the files to inspect
```

The template file must contain at least one plain frame (no component
instances) with a text shape and an `open-url` link; the Plants-app demo works.
Set `SEED_TEMPLATE_FILE` to avoid passing `--template` every time.

## What to measure

1. Projection size vs raw file size. The script prints the projection size and
   a rough token estimate; the raw file comes from `get-file` if a comparison
   is needed.
2. Precision, recall and latency with `evaluate.mjs` as the set grows.
3. The same questions against Jev directly (`https://api.typesafe.ai/v1/systemone`,
   or OpenCode Zen) and against a self-hosted open model such as Laya, which
   speaks the same `POST /v1/systemone` protocol.

Decision: whether a projection like this is enough, or whether a later phase
needs a screenshot plus a vision model for visual brand impersonation.

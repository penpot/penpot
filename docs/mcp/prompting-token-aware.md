---
title: Prompting in a token-aware way
order: 3
desc: Use Penpot color, typography, and design tokens effectively when prompting an MCP-connected AI agent.
---
# Prompting "token-aware"

## 1. Where the tokens go

In this type of prompt, token usage spikes for four clear reasons:

1. **Repeated natural-language instructions**
   * "Do not invent..."
   * "If something is missing..."
   * "Prefer X over Y..."
   These rules are fine, but written as prose they are expensive.
2. **Verbose field lists**
   * Repeating "Include: ..." with full sentences.
   * Explaining the *why* instead of the *what*.
3. **Unnecessary Markdown**
   * Headings, complete sentences, long explanations.
   * The LLM doesn't need "human readability".
4. **Artificial separation between global rules and per-file rules**
   * Many rules get repeated with different words.

👉 The LLM **doesn't need narrative context**; it needs **structured contracts**.

***

## 2. Key principle to reduce tokens without losing quality

> **Turn prose into a grammar.**

That means:

* fewer sentences
* more **compact bullet points**
* more **pseudo-schema**
* more **stable keywords**
* fewer linguistic connectors

LLMs respond very well to:

* terse enumerations
* "MUST / MUST NOT"
* schema/checklist-style structures

***

## 3. What you should NOT cut

There are things you **shouldn't cut**, because they save tokens *in the long run* (fewer retries, less drift):

* The exact definition of the 5 files
* The "do not invent" rule
* The distinction between mockups vs. the real system
* The hierarchy tokens → components → layout → screens

That's the skeleton. If you change it, the output gets worse and you'll end up spending more tokens fixing it.

***

## 4. Concrete optimizations (actionable)

### A. Collapse global rules into a "RULESET"

Instead of repeating rules in every section:

```
GLOBAL RULESET - SOURCE = Penpot MCP only - NO_GUESSING = true - IF_MISSING -> TODO - PREFER = structured data - SCREENSHOTS = visual reference only - OUTPUT = deterministic, stable ordering 
```

This removes a lot of repeated text and the LLM will still respect it.

***

### B. Replace sentences with compact schemas

Example BEFORE:

> "Include font families, sizes, line heights, weights, and letter spacing."

AFTER:

```
typography: fontFamily fontSize lineHeight fontWeight letterSpacing 
```

Fewer tokens, same information, clearer for the model.

***

### C. Remove "pedagogical" explanations

Things like:

* "This file is important because..."
* "The goal of this is to..."

👉 They add nothing to the output. Remove them.

***

### D. Convert Markdown into structured plain text

`##`, `###`, etc. **don't help** the LLM and they cost tokens.

Use:

```
[FILE 3] layout-and-rules.md 
```

instead of long headers.

***

### E. Explicitly limit the output size

This is **key** and many people forget it.

Add rules like:

```
SIZE CONSTRAINTS - design-system.json: concise, no comments - components.catalog.json: components only, no explanations - layout-and-rules.md: max ~300 lines - screens.reference.json: max 6 screens 
```

The model **will try to comply** with these limits and reduce verbosity.

***

## 5. Optimized prompt ("token-aware" version)

I won't rewrite the entire prompt again (you already have it), but here's the **key block** I'd add or use as a replacement:

```
GLOBAL RULESET - SOURCE: Penpot MCP - NO_GUESSING - IF_MISSING: TODO - PREFER: structured data > prose - SCREENSHOTS: visual context only - OUTPUT: deterministic, stable, minimal SIZE CONSTRAINTS - MAX FILES: 5 - design-system.json: tokens + mappings only - components.catalog.json: real components only - layout-and-rules.md: concise rules, no explanations - screens.reference.json: 3-6 screens max - README.md: brief, no marketing STYLE - Use schemas, lists, and key:value forms - Avoid narrative explanations - Avoid redundant wording
```

With just this, in real tests, usage **drops by ~20-35%** depending on the model.



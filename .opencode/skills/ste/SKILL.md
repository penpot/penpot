---
name: ste
description: Write or rewrite text in ASD-STE100 Simplified Technical English. ONLY use this skill when the user explicitly invokes it by name — i.e. they type "/ste" or literally write "use the ste skill" / "apply ASD-STE100". Do NOT trigger it on paraphrased intent such as "simplify this", "make it clearer", "write technical documentation", or "shorter sentences please" — the user has deliberately scoped this skill to explicit invocation only. For those requests, respond normally without loading this skill unless they name it.
---

# ASD-STE100 Simplified Technical English

Apply the ASD-STE100 standard to all prose you produce in this task. Do not announce that you use STE, do not name the standard, and do not explain the style unless the user asks. If the user later asks you to "write more naturally," ask one short question to confirm they want to leave STE before you drop it.

Compliance note (for you, not for output): the official specification and its dictionary are copyright ASD. This skill encodes paraphrased rules and a publicly sourced word list. For certified aerospace/defense deliverables, tell the user that full compliance requires the free official specification (asd-ste100.org) and a human sign-off. Never claim certified compliance.

## Step 0 — Classify the text

Before writing a single sentence, decide: is this **procedural** text (instructions someone follows) or **descriptive** text (explanation, background, description)? Every limit below depends on this. Mixed documents get classified section by section.

## Core rules

### Sentences
- Procedural: maximum **20 words** per sentence.
- Descriptive: maximum **25 words** per sentence.
- Maximum **6 sentences** per paragraph. One topic per paragraph.
- One instruction per sentence. Two actions in one sentence only if they occur at the same time.
- Put a condition BEFORE its command: "If the pressure decreases, close the valve."
- Do not omit articles, subjects, or verbs to save words. "Ensure file exists" is wrong; "Make sure that the file exists" is correct. Keep the word "that" after verbs like "make sure."
- Numbers, units with numbers, abbreviations, quoted strings, code identifiers, and proper nouns each count as one word.

### Verbs
- Allowed forms only: infinitive, imperative, simple present, simple past, simple future, and past participle used as an adjective.
- Never use present perfect or continuous forms. "We have received" → "We received." "is being tested" → a simple form.
- Never use an -ing form as a verb. An -ing word is allowed only inside a technical name ("the mounting bracket," "logging").
- Active voice. Passive is allowed only in descriptive text when the agent is unknown or unimportant.
- Instructions use the imperative: "Open the panel," not "You must open the panel" or "The panel should be opened."
- Express actions as verbs, not nouns: "compress the file," not "perform compression of the file."
- Modals: use **can** (possibility), **will** (future), **must** (requirement). Do not use should, would, could, may, might. A hedge becomes a fact or a "can": "an explosion can occur."
- No phrasal verbs: "go down" → "decrease," "set up" → "install," "carry out" → "do."

### Words
- One word, one meaning, one part of speech, used consistently. Never rotate synonyms: pick one name for a thing and repeat it.
- Before drafting, replace unapproved vocabulary. Read `references/word-substitutions.md` and apply it; it is the working dictionary for this skill.
- Domain-specific nouns (part names, tool names, product names, UI labels) and domain verbs (drill, ream, boot, compile) are your **technical nouns/verbs** — keep them as-is, use each consistently, and do not verb a noun or noun a verb.
- Noun clusters: maximum **3 words** ("overhead panel light" is the limit). Longer clusters get decomposed with prepositions or hyphenated on first use: "main-gear-door retraction-winch handle."
- American English spelling.
- No Latin abbreviations: "e.g." → "for example," "i.e." → "that is," delete "etc."

### Punctuation
- No semicolons — write two sentences.
- Parentheses only for references, abbreviations, and item numbers.
- Hyphenate words that act as one unit; a hyphenated word counts as one word.
- No contractions.

### Warnings, cautions, notes
- **WARNING** = risk of injury or death. **CAUTION** = risk of damage. **NOTE** = information only, never an instruction.
- Start a warning or caution with the command or condition, then give the risk:
  "WARNING: Do not touch the terminal. The terminal has a dangerous voltage."
- Notes obey the 25-word descriptive limit.

## Step 2 — Self-check pass

After drafting, scan your text once for each of these and fix every hit before you respond:

1. Any sentence over the 20/25-word limit for its type
2. Contractions, semicolons
3. "should," "would," "could," "may," "might"
4. "has been," "have been," "had been," "is being," "was being"
5. -ing words used as verbs
6. Missing articles (a/an/the/this) before nouns
7. Synonym rotation (the same object under two names)
8. Any word in the unapproved column of `references/word-substitutions.md`
9. Warnings that state the risk before the command

## Reference files

- `references/word-substitutions.md` — unapproved → approved word mappings and one-meaning rulings. Read it before drafting; it is short.
- `references/examples.md` — worked before/after rewrites (procedural, descriptive, warnings, common mistakes). Read it when rewriting existing text or when unsure how a rule applies.

## What NOT to touch

Code blocks, command strings, file paths, error messages, quoted UI text, and proper nouns stay exactly as written. STE applies to the prose around them.

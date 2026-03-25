---
title: Good prompting practices (design)
order: 2
desc: Write clearer prompts when using AI with Penpot designs - scope, context, and safe iteration with MCP.
---
# Good prompting practices (design)

## AI isn't magic, it's a good brief (a guide for designers)

I've been wrestling with AI agents for a while now to integrate them into my design workflow, and the conclusion is the same as always: **garbage in, garbage out**. We can't ask a machine for something we don't know how to explain to a human.

### 1. The role: define it like you're hiring

Telling the AI "Act like a UX/UI designer" is not useful. It's too vague. Would you post a job offer on LinkedIn with just that? Give it seniority and clear boundaries.

* **Bad:** "You are a creative designer."
* **Good:** "You are a Senior Product Designer. You are an expert in design systems, accessibility (WCAG), and you understand the connection between Penpot and code. You do not make business decisions without data."

If you don't set limits, the AI hallucinates. If you wouldn't hire someone with a vague description, don't expect the agent to work with one.

### 2. The prompt is a structured brief

Forget about writing pretty prose. We need structure. A good design prompt needs to look like a well-made Jira ticket:

* **Context:** What product is it, who is the user?
* **Goal:** What problem are we solving today? (e.g., "Improve the visual hierarchy of form X").
* **Inputs:** Your frames, tokens, and documentation go here.
* **Constraints:** "Only existing components," "WCAG AA," "no inventing colors."
* **Quality Criteria:** How do I know the work is finished?

Less inspirational adjectives, more data.

### 3. Images are not optional

This is visual design. If you don't attach screenshots, you are sabotaging the process. But be careful: just dropping the image isn't enough. You have to direct the agent's gaze:

> "In image 1, look only at the negative space between the label and the input. In image 2, ignore the header and focus on the table."

The AI "sees," but it doesn't know what matters unless you tell it.

### 4. Documentation: summarize and conquer

Sending a link to the documentation and expecting it to read the whole thing is naive. You have to chew it for them. If you use Penpot, give it the rules of the game:

* **Color:** "Use only tokens from /core/colors".
* **Type:** "Don't stray from the defined typographic scale".
* **Components:** "Don't duplicate, use variants".

This turns the agent into someone who respects the system, not a cowboy who breaks the rules because "it looks prettier."

### 5. Work via transformations, not "final results"

If you tell it "redesign this dashboard to make it modern," it will return a generic template. It's better to ask for concrete actions on what you already have:

* Adjust the visual hierarchy.
* Reduce noise by eliminating unnecessary borders.
* Normalize spacings using our tokens.

We want something actionable, not a Dribbble image that's impossible to code.

### 6. Design-to-code: speak the development language

If the goal is to go from Penpot to code, the agent needs to know what it's getting into. Tell it the framework (React, Vue...), the styling strategy (Tailwind, CSS variables...), and forbid magic numbers.

> "Generate the structure for React based on this frame. Map Penpot tokens to CSS variables. Don't invent breakpoints."

This avoids the classic "looks great in design, impossible to implement in production."

### 7. Ask for the "why," not just the "what"

A designer who can't justify their decisions is dangerous. The AI is the same. Always ask it to explain its changes: the trade-offs, what it discarded, and why. This helps you audit the result and see whether it understood the problem or was just guessing.

### 8. Iterate; don't look for the "one-shot"

The perfect prompt doesn't exist. What exists is the workflow cycle. Design the conversation: first analysis, then proposal, then feedback, and finally preparation for handoff.

If you try to get it to do everything in the first message, the result will be mediocre.

### 9. Tell it what NOT to do (negatives)

This works like a charm and almost no one does it. Set barriers so it doesn't go off the rails:

* "Don't add new navigation patterns."
* "Don't touch the visual identity."
* "Don't assume product decisions."

Limits, paradoxically, improve the quality of the proposal.

### 10. Penpot is the competitive advantage here

This is key. Penpot works well with AI because its structure (tokens, SVG, open standards) is much more "readable" for a machine than other proprietary formats. If you feed the agent good tokens and clear conventions, you can almost treat it like another team member. *(We're still figuring out the best way to export a design to give the best context to the AI... a .penpot file? Something else?)* 

***

This isn't about writing beautifully - it's about reducing ambiguity. If you treat AI like a "creative magic box," you'll get chaos. If you treat it like a sharp designer who needs context and rules, it will take a lot of grunt work off your plate.
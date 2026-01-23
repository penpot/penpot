<rpg-method>
# Repository Planning Graph (RPG) Method - PRD Template

This template teaches you (AI or human) how to create structured, dependency-aware PRDs using the RPG methodology from Microsoft Research. The key insight: separate WHAT (functional) from HOW (structural), then connect them with explicit dependencies.

## Core Principles

1. **Dual-Semantics**: Think functional (capabilities) AND structural (code organization) separately, then map them
2. **Explicit Dependencies**: Never assume - always state what depends on what
3. **Topological Order**: Build foundation first, then layers on top
4. **Progressive Refinement**: Start broad, refine iteratively

## How to Use This Template

- Follow the instructions in each `<instruction>` block
- Look at `<example>` blocks to see good vs bad patterns
- Fill in the content sections with your project details
- The AI reading this will learn the RPG method by following along
- Task Master will parse the resulting PRD into dependency-aware tasks

## Recommended Tools for Creating PRDs

When using this template to **create** a PRD (not parse it), use **code-context-aware AI assistants** for best results:

**Why?** The AI needs to understand your existing codebase to make good architectural decisions about modules, dependencies, and integration points.

**Recommended tools:**
- **Claude Code** (claude-code CLI) - Best for structured reasoning and large contexts
- **Cursor/Windsurf** - IDE integration with full codebase context
- **Gemini CLI** (gemini-cli) - Massive context window for large codebases
- **Codex/Grok CLI** - Strong code generation with context awareness

**Note:** Once your PRD is created, `task-master parse-prd` works with any configured AI model - it just needs to read the PRD text itself, not your codebase.
</rpg-method>

---

<overview>
<instruction>
Start with the problem, not the solution. Be specific about:
- What pain point exists?
- Who experiences it?
- Why existing solutions don't work?
- What success looks like (measurable outcomes)?

Keep this section focused - don't jump into implementation details yet.
</instruction>

## Problem Statement
[Describe the core problem. Be concrete about user pain points.]

## Target Users
[Define personas, their workflows, and what they're trying to achieve.]

## Success Metrics
[Quantifiable outcomes. Examples: "80% task completion via autopilot", "< 5% manual intervention rate"]

</overview>

---

<functional-decomposition>
<instruction>
Now think about CAPABILITIES (what the system DOES), not code structure yet.

Step 1: Identify high-level capability domains
- Think: "What major things does this system do?"
- Examples: Data Management, Core Processing, Presentation Layer

Step 2: For each capability, enumerate specific features
- Use explore-exploit strategy:
  * Exploit: What features are REQUIRED for core value?
  * Explore: What features make this domain COMPLETE?

Step 3: For each feature, define:
- Description: What it does in one sentence
- Inputs: What data/context it needs
- Outputs: What it produces/returns
- Behavior: Key logic or transformations

<example type="good">
Capability: Data Validation
  Feature: Schema validation
    - Description: Validate JSON payloads against defined schemas
    - Inputs: JSON object, schema definition
    - Outputs: Validation result (pass/fail) + error details
    - Behavior: Iterate fields, check types, enforce constraints

  Feature: Business rule validation
    - Description: Apply domain-specific validation rules
    - Inputs: Validated data object, rule set
    - Outputs: Boolean + list of violated rules
    - Behavior: Execute rules sequentially, short-circuit on failure
</example>

<example type="bad">
Capability: validation.js
  (Problem: This is a FILE, not a CAPABILITY. Mixing structure into functional thinking.)

Capability: Validation
  Feature: Make sure data is good
  (Problem: Too vague. No inputs/outputs. Not actionable.)
</example>
</instruction>

## Capability Tree

### Capability: [Name]
[Brief description of what this capability domain covers]

#### Feature: [Name]
- **Description**: [One sentence]
- **Inputs**: [What it needs]
- **Outputs**: [What it produces]
- **Behavior**: [Key logic]

#### Feature: [Name]
- **Description**:
- **Inputs**:
- **Outputs**:
- **Behavior**:

### Capability: [Name]
...

</functional-decomposition>

---

<structural-decomposition>
<instruction>
NOW think about code organization. Map capabilities to actual file/folder structure.

Rules:
1. Each capability maps to a module (folder or file)
2. Features within a capability map to functions/classes
3. Use clear module boundaries - each module has ONE responsibility
4. Define what each module exports (public interface)

The goal: Create a clear mapping between "what it does" (functional) and "where it lives" (structural).

<example type="good">
Capability: Data Validation
  → Maps to: src/validation/
    ├── schema-validator.js      (Schema validation feature)
    ├── rule-validator.js         (Business rule validation feature)
    └── index.js                  (Public exports)

Exports:
  - validateSchema(data, schema)
  - validateRules(data, rules)
</example>

<example type="bad">
Capability: Data Validation
  → Maps to: src/utils.js
  (Problem: "utils" is not a clear module boundary. Where do I find validation logic?)

Capability: Data Validation
  → Maps to: src/validation/everything.js
  (Problem: One giant file. Features should map to separate files for maintainability.)
</example>
</instruction>

## Repository Structure

```
project-root/
├── src/
│   ├── [module-name]/       # Maps to: [Capability Name]
│   │   ├── [file].js        # Maps to: [Feature Name]
│   │   └── index.js         # Public exports
│   └── [module-name]/
├── tests/
└── docs/
```

## Module Definitions

### Module: [Name]
- **Maps to capability**: [Capability from functional decomposition]
- **Responsibility**: [Single clear purpose]
- **File structure**:
  ```
  module-name/
  ├── feature1.js
  ├── feature2.js
  └── index.js
  ```
- **Exports**:
  - `functionName()` - [what it does]
  - `ClassName` - [what it does]

</structural-decomposition>

---

<dependency-graph>
<instruction>
This is THE CRITICAL SECTION for Task Master parsing.

Define explicit dependencies between modules. This creates the topological order for task execution.

Rules:
1. List modules in dependency order (foundation first)
2. For each module, state what it depends on
3. Foundation modules should have NO dependencies
4. Every non-foundation module should depend on at least one other module
5. Think: "What must EXIST before I can build this module?"

<example type="good">
Foundation Layer (no dependencies):
  - error-handling: No dependencies
  - config-manager: No dependencies
  - base-types: No dependencies

Data Layer:
  - schema-validator: Depends on [base-types, error-handling]
  - data-ingestion: Depends on [schema-validator, config-manager]

Core Layer:
  - algorithm-engine: Depends on [base-types, error-handling]
  - pipeline-orchestrator: Depends on [algorithm-engine, data-ingestion]
</example>

<example type="bad">
- validation: Depends on API
- API: Depends on validation
(Problem: Circular dependency. This will cause build/runtime issues.)

- user-auth: Depends on everything
(Problem: Too many dependencies. Should be more focused.)
</example>
</instruction>

## Dependency Chain

### Foundation Layer (Phase 0)
No dependencies - these are built first.

- **[Module Name]**: [What it provides]
- **[Module Name]**: [What it provides]

### [Layer Name] (Phase 1)
- **[Module Name]**: Depends on [[module-from-phase-0], [module-from-phase-0]]
- **[Module Name]**: Depends on [[module-from-phase-0]]

### [Layer Name] (Phase 2)
- **[Module Name]**: Depends on [[module-from-phase-1], [module-from-foundation]]

[Continue building up layers...]

</dependency-graph>

---

<implementation-roadmap>
<instruction>
Turn the dependency graph into concrete development phases.

Each phase should:
1. Have clear entry criteria (what must exist before starting)
2. Contain tasks that can be parallelized (no inter-dependencies within phase)
3. Have clear exit criteria (how do we know phase is complete?)
4. Build toward something USABLE (not just infrastructure)

Phase ordering follows topological sort of dependency graph.

<example type="good">
Phase 0: Foundation
  Entry: Clean repository
  Tasks:
    - Implement error handling utilities
    - Create base type definitions
    - Setup configuration system
  Exit: Other modules can import foundation without errors

Phase 1: Data Layer
  Entry: Phase 0 complete
  Tasks:
    - Implement schema validator (uses: base types, error handling)
    - Build data ingestion pipeline (uses: validator, config)
  Exit: End-to-end data flow from input to validated output
</example>

<example type="bad">
Phase 1: Build Everything
  Tasks:
    - API
    - Database
    - UI
    - Tests
  (Problem: No clear focus. Too broad. Dependencies not considered.)
</example>
</instruction>

## Development Phases

### Phase 0: [Foundation Name]
**Goal**: [What foundational capability this establishes]

**Entry Criteria**: [What must be true before starting]

**Tasks**:
- [ ] [Task name] (depends on: [none or list])
  - Acceptance criteria: [How we know it's done]
  - Test strategy: [What tests prove it works]

- [ ] [Task name] (depends on: [none or list])

**Exit Criteria**: [Observable outcome that proves phase complete]

**Delivers**: [What can users/developers do after this phase?]

---

### Phase 1: [Layer Name]
**Goal**:

**Entry Criteria**: Phase 0 complete

**Tasks**:
- [ ] [Task name] (depends on: [[tasks-from-phase-0]])
- [ ] [Task name] (depends on: [[tasks-from-phase-0]])

**Exit Criteria**:

**Delivers**:

---

[Continue with more phases...]

</implementation-roadmap>

---

<test-strategy>
<instruction>
Define how testing will be integrated throughout development (TDD approach).

Specify:
1. Test pyramid ratios (unit vs integration vs e2e)
2. Coverage requirements
3. Critical test scenarios
4. Test generation guidelines for Surgical Test Generator

This section guides the AI when generating tests during the RED phase of TDD.

<example type="good">
Critical Test Scenarios for Data Validation module:
  - Happy path: Valid data passes all checks
  - Edge cases: Empty strings, null values, boundary numbers
  - Error cases: Invalid types, missing required fields
  - Integration: Validator works with ingestion pipeline
</example>
</instruction>

## Test Pyramid

```
        /\
       /E2E\       ← [X]% (End-to-end, slow, comprehensive)
      /------\
     /Integration\ ← [Y]% (Module interactions)
    /------------\
   /  Unit Tests  \ ← [Z]% (Fast, isolated, deterministic)
  /----------------\
```

## Coverage Requirements
- Line coverage: [X]% minimum
- Branch coverage: [X]% minimum
- Function coverage: [X]% minimum
- Statement coverage: [X]% minimum

## Critical Test Scenarios

### [Module/Feature Name]
**Happy path**:
- [Scenario description]
- Expected: [What should happen]

**Edge cases**:
- [Scenario description]
- Expected: [What should happen]

**Error cases**:
- [Scenario description]
- Expected: [How system handles failure]

**Integration points**:
- [What interactions to test]
- Expected: [End-to-end behavior]

## Test Generation Guidelines
[Specific instructions for Surgical Test Generator about what to focus on, what patterns to follow, project-specific test conventions]

</test-strategy>

---

<architecture>
<instruction>
Describe technical architecture, data models, and key design decisions.

Keep this section AFTER functional/structural decomposition - implementation details come after understanding structure.
</instruction>

## System Components
[Major architectural pieces and their responsibilities]

## Data Models
[Core data structures, schemas, database design]

## Technology Stack
[Languages, frameworks, key libraries]

**Decision: [Technology/Pattern]**
- **Rationale**: [Why chosen]
- **Trade-offs**: [What we're giving up]
- **Alternatives considered**: [What else we looked at]

</architecture>

---

<risks>
<instruction>
Identify risks that could derail development and how to mitigate them.

Categories:
- Technical risks (complexity, unknowns)
- Dependency risks (blocking issues)
- Scope risks (creep, underestimation)
</instruction>

## Technical Risks
**Risk**: [Description]
- **Impact**: [High/Medium/Low - effect on project]
- **Likelihood**: [High/Medium/Low]
- **Mitigation**: [How to address]
- **Fallback**: [Plan B if mitigation fails]

## Dependency Risks
[External dependencies, blocking issues]

## Scope Risks
[Scope creep, underestimation, unclear requirements]

</risks>

---

<appendix>
## References
[Papers, documentation, similar systems]

## Glossary
[Domain-specific terms]

## Open Questions
[Things to resolve during development]
</appendix>

---

<task-master-integration>
# How Task Master Uses This PRD

When you run `task-master parse-prd <file>.txt`, the parser:

1. **Extracts capabilities** → Main tasks
   - Each `### Capability:` becomes a top-level task

2. **Extracts features** → Subtasks
   - Each `#### Feature:` becomes a subtask under its capability

3. **Parses dependencies** → Task dependencies
   - `Depends on: [X, Y]` sets task.dependencies = ["X", "Y"]

4. **Orders by phases** → Task priorities
   - Phase 0 tasks = highest priority
   - Phase N tasks = lower priority, properly sequenced

5. **Uses test strategy** → Test generation context
   - Feeds test scenarios to Surgical Test Generator during implementation

**Result**: A dependency-aware task graph that can be executed in topological order.

## Why RPG Structure Matters

Traditional flat PRDs lead to:
- ❌ Unclear task dependencies
- ❌ Arbitrary task ordering
- ❌ Circular dependencies discovered late
- ❌ Poorly scoped tasks

RPG-structured PRDs provide:
- ✅ Explicit dependency chains
- ✅ Topological execution order
- ✅ Clear module boundaries
- ✅ Validated task graph before implementation

## Tips for Best Results

1. **Spend time on dependency graph** - This is the most valuable section for Task Master
2. **Keep features atomic** - Each feature should be independently testable
3. **Progressive refinement** - Start broad, use `task-master expand` to break down complex tasks
4. **Use research mode** - `task-master parse-prd --research` leverages AI for better task generation
</task-master-integration>

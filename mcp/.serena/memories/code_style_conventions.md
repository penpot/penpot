# Code Style and Conventions

## General Principles
- **Object-Oriented Design**: VERY IMPORTANT: Use idiomatic, object-oriented style with explicit abstractions
- **Strategy Pattern**: Prefer explicitly typed interfaces over bare functions for non-trivial functionality
- **Clean Architecture**: Tools implement a common interface for consistent registration and execution

## TypeScript Configuration
- **Strict Mode**: All strict TypeScript options enabled
- **Target**: ES2022
- **Module System**: CommonJS
- **Declaration Files**: Generated with source maps

## Naming Conventions
- **Classes**: PascalCase (e.g., `ExeceuteCodeTool`, `PenpotMcpServer`)
- **Interfaces**: PascalCase (e.g., `Tool`)
- **Methods**: camelCase (e.g., `execute`, `registerTools`)
- **Constants**: camelCase for readonly properties (e.g., `definition`)
- **Files**: PascalCase for classes (e.g., `ExecuteCodeTool.ts`)

## Documentation Style
- **JSDoc**: Use comprehensive JSDoc comments for classes, methods, and interfaces
- **Description Format**: Initial elliptical phrase that defines *what* it is, followed by details
- **Comment Style**: VERY IMPORTANT: Start with lowercase for comments of code blocks (unless lengthy explanation with multiple sentences)


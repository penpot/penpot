# Task Completion Guidelines

## After Making Code Changes

### 1. Build and Test
```bash
cd mcp-server
npm run build:full  # or npm run build for faster bundling only
```

### 2. Verify TypeScript Compilation
```bash
npx tsc --noEmit
```

### 3. Test the Server
```bash
# Start in development mode to test changes
npm run dev
```

### 4. Code Quality Checks
- Ensure all code follows the established conventions
- Verify JSDoc comments are complete and accurate
- Check that error handling is appropriate
- Use clean imports WITHOUT file extensions (esbuild handles resolution)
- Validate that tool interfaces are properly implemented

### 5. Integration Testing
- Test tool registration in the main server
- Verify MCP protocol compliance
- Ensure tool definitions match implementation

## Before Committing Changes
1. **Build Successfully**: `npm run build:full` completes without errors
2. **No TypeScript Errors**: `npx tsc --noEmit` passes
3. **Documentation Updated**: JSDoc comments reflect changes
4. **Tool Registry Updated**: New tools added to `registerTools()` method
5. **Interface Compliance**: All tools implement the `Tool` interface correctly

## File Organization
- Place new tools in `src/tools/` directory
- Update main server registration in `src/index.ts`
- Follow existing naming conventions

## Common Patterns
- All tools must implement the `Tool` interface
- Use readonly properties for tool definitions
- Include comprehensive error handling
- Follow the established documentation style
- Import WITHOUT file extensions (esbuild resolves them automatically)

## Build System
- Uses esbuild for fast bundling and TypeScript for declarations
- Import statements should omit file extensions entirely
- IDE refactoring is safe - no extension-related build failures
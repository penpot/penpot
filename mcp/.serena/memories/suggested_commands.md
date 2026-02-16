# Suggested Commands

## Development Commands
```bash
# Navigate to MCP server directory
cd penpot/mcp/server

# Install dependencies
pnpm install

# Build the TypeScript project
pnpm run build

# Start the server (production)
pnpm run start

# Start the server in development mode
npm run start:dev
```

## Testing and Development
```bash
# Run TypeScript compiler in watch mode
pnpx tsc --watch

# Check TypeScript compilation without emitting files
pnpx tsc --noEmit
```

## Windows-Specific Commands
```cmd
# Directory navigation
cd penpot/mcp/server
dir                    # List directory contents
type package.json      # Display file contents

# Git operations
git status
git add .
git commit -m "message"
git push

# File operations
copy src\file.ts backup\file.ts    # Copy files
del dist\*                         # Delete files
mkdir new-directory                # Create directory
rmdir /s directory                 # Remove directory recursively
```

## Project Structure Navigation
```bash
# Key directories
cd penpot/mcp/server/src              # Source code
cd penpot/mcp/server/src/tools        # Tool implementations
cd penpot/mcp/server/src/interfaces   # Type definitions
cd penpot/mcp/server/dist             # Compiled output
```

## Common Utilities
```cmd
# Search for text in files
findstr /s /i "HelloWorld" *.ts

# Find files by name
dir /s /b *Tool.ts

# Process management
tasklist | findstr node    # Find Node.js processes
taskkill /f /im node.exe   # Kill Node.js processes
```

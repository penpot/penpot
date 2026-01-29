# Suggested Commands

## Development Commands
```bash
# Navigate to MCP server directory
cd mcp-server

# Install dependencies
npm install

# Build the TypeScript project
npm run build

# Start the server (production)
npm start

# Start the server in development mode
npm run dev
```

## Testing and Development
```bash
# Run TypeScript compiler in watch mode
npx tsc --watch

# Check TypeScript compilation without emitting files
npx tsc --noEmit
```

## Windows-Specific Commands
```cmd
# Directory navigation
cd mcp-server
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
cd mcp-server\src              # Source code
cd mcp-server\src\tools        # Tool implementations
cd mcp-server\src\interfaces   # Type definitions
cd mcp-server\dist             # Compiled output
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

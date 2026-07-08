#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR/mcp"
echo "Starting Penpot MCP Server and Plugin..."
npx -y pnpm@11.9.0 --filter mcp-server --filter mcp-plugin -r --parallel run start

@echo off
cd /d "%~dp0mcp"
echo Starting Penpot MCP Server and Plugin...
npx -y pnpm@11.9.0 --filter mcp-server --filter mcp-plugin -r --parallel run start

@echo off
echo ===================================================
echo Starting Penpot Dev Servers...
echo ===================================================

:: Open the installation guide
start "" "%~dp0mcp_readme.txt"

:: Start Image Cropper Plugin Server in a new window
echo 1. Launching Image Cropper Plugin Server (Port 4210)...
start "Penpot Image Cropper Plugin" cmd /k "cd /d "%~dp0plugins" && npx -y pnpm@11.9.0 --filter image-cropper-plugin run init"

:: Start MCP Server and Connecting Plugin in a new window
echo 2. Launching Penpot MCP Server (Port 4401) and Plugin (Port 4400)...
start "Penpot MCP Server & Plugin" cmd /k "cd /d "%~dp0mcp" && npx -y pnpm@11.9.0 --filter mcp-server --filter mcp-plugin -r --parallel run start"

echo ===================================================
echo Both servers have been launched in separate windows!
echo You can close this window now.
echo ===================================================
pause

# Setup Guide — Penpot MCP

## Prerequisites (Pehle Yeh Install Hona Chahiye)

- [x] Node.js v22.x — https://nodejs.org/
- [x] pnpm — `npm install -g pnpm`
- [x] Git
- [x] Penpot (locally ya https://design.penpot.app)

---

## Method 1: npx se Run Karo (Aasaan Tarika)

Agar aapko sirf use karna hai, develop nahi karna:

```bash
# Latest Penpot ke saath
npx -y @penpot/mcp@latest

# Beta version ke saath
npx -y @penpot/mcp@beta
```

Bas! Server start ho jayega. Ab [Plugin Connect Guide](./03-plugin-connect.md) follow karo.

---

## Method 2: Source Code se Run Karo (Developer Tarika)

### Step 1: Repository Clone Karo

```bash
# Penpot 2.14 ke liye
git clone https://github.com/penpot/penpot.git --branch mcp-prod-2.14.1 --depth 1

# Ya staging/beta ke liye
git clone https://github.com/penpot/penpot.git --branch staging --depth 1
```

### Step 2: MCP Directory mein Jao

```bash
cd penpot/mcp
```

### Step 3: Dependencies Install Karo (Sirf Pehli Baar)

```bash
./scripts/setup
```

> **Windows Users**: Git Bash terminal use karo, PowerShell nahi!

### Step 4: Build aur Start Karo

```bash
pnpm run bootstrap
```

Yeh command:
1. Saari dependencies install karta hai
2. Saare components build karta hai  
3. Saare servers start karta hai

---

## Verify Karo — Sab Chal Raha Hai?

Terminal mein yeh messages dikhne chahiye:

```
✓ MCP Server running on port 4401
✓ Plugin Server running on port 4400  
✓ WebSocket Server running on port 4402
```

Browser mein check karo:
- http://localhost:4400/manifest.json — Plugin manifest dikhna chahiye
- http://localhost:4401/mcp — MCP endpoint

---

## Next Steps

- [Plugin Connect Karo →](./03-plugin-connect.md)
- [MCP Client Configure Karo →](./04-mcp-clients.md)

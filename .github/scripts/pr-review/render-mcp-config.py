"""Write the selected reviewer's config for the stdio servers in config/mcp.json."""

import json
import sys
from pathlib import Path


def review_config(provider):
    config_dir = Path(__file__).parent / "config"
    # Serena tailors its tools to the client that launches it.
    contexts = {"claude": "claude-code", "copilot": "copilot-cli", "opencode": "ide"}
    config = json.loads((config_dir / "mcp.json").read_text(encoding="utf-8"))
    servers = config["mcpServers"]
    # Copilot can launch MCP servers from the Actions workspace, one level above
    # the reviewed checkout. Pass the checkout path explicitly to every client.
    arguments = {"${PROJECT_ROOT}": str(Path.cwd()), "${SERENA_CONTEXT}": contexts[provider]}
    for server in servers.values():
        server["args"] = [arguments.get(arg, arg) for arg in server["args"]]

    if provider == "copilot":
        for server in servers.values():
            server.update(type="local", tools=["*"])
    elif provider == "opencode":
        config = json.loads((config_dir / "opencode-go.json").read_text(encoding="utf-8"))
        # OpenCode takes the executable and its arguments in a single array.
        config["mcp"] = {
            name: {
                "type": "local",
                "command": [server["command"], *server["args"]],
                "timeout": 120000,
            }
            for name, server in servers.items()
        }
    return config


if __name__ == "__main__":
    print(json.dumps(review_config(sys.argv[1]), indent=2))

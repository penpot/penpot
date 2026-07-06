import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

async function runDemo() {
    console.log("Connecting to Penpot MCP Server...");
    
    // Connect to the local Penpot MCP server using Streamable HTTP
    const transport = new StreamableHTTPClientTransport(
        new URL("http://localhost:4401/mcp")
    );
    
    const client = new Client(
        {
            name: "penpot-demo-client",
            version: "1.0.0"
        },
        {
            capabilities: {}
        }
    );

    try {
        await client.connect(transport);
        console.log("Successfully connected to the Penpot MCP Server!");

        // 1. List all available tools
        console.log("\n--- Listing Available Tools ---");
        const toolsResult = await client.listTools();
        console.log(`Found ${toolsResult.tools.length} tools:`);
        for (const tool of toolsResult.tools) {
            console.log(` - ${tool.name}: ${tool.description.split("\n")[0]}`);
        }

        // 2. Fetch the High-Level Overview instructions
        console.log("\n--- Calling high_level_overview ---");
        const overview = await client.callTool({
            name: "high_level_overview",
            arguments: {}
        });
        console.log("Overview response length:", overview.content?.[0] ? (overview.content[0] as any).text.length : 0);

        // 3. Execute code in Penpot to create a beautiful dark mode card
        console.log("\n--- Executing design code on Penpot ---");
        
        // This JavaScript will run inside the Penpot Plugin context.
        const codeToExecute = `
            // Check if we are connected to a page
            const page = penpot.currentPage;
            if (!page) {
                throw new Error("No active page found in Penpot. Please open a design file.");
            }

            console.log("Creating Board...");
            const board = penpot.createBoard();
            board.name = "MCP Demo Dashboard";
            board.resize(500, 400);
            board.x = 100;
            board.y = 100;

            // Set board background (fills array) to Slate 900 (#0F172A)
            board.fills = [{ fillColor: "#0F172A", fillOpacity: 1 }];

            console.log("Creating Card background...");
            const card = penpot.createRectangle();
            card.name = "Glass Card";
            card.resize(420, 320);
            
            // Position card with 40px margin inside the board
            penpotUtils.setParentXY(card, 40, 40);
            
            // Dark Slate 800 background with rounded corners (16px)
            card.fills = [{ fillColor: "#1E293B", fillOpacity: 1 }];
            card.borderRadius = 16;
            
            // Add a beautiful Cyan-400 stroke/border
            card.strokes = [{ strokeColor: "#22D3EE", strokeWidth: 2, strokeOpacity: 1 }];
            
            board.appendChild(card);

            console.log("Creating Card title...");
            const title = penpot.createText();
            title.name = "Card Title";
            title.characters = "Penpot MCP Integration";
            title.fontSize = 24;
            title.fills = [{ fillColor: "#F8FAFC", fillOpacity: 1 }]; // Slate 50
            
            // Auto layout dimensions
            title.growType = "auto-width";
            penpotUtils.setParentXY(title, 80, 80);
            board.appendChild(title);

            console.log("Creating Card body text...");
            const body = penpot.createText();
            body.name = "Card Body";
            body.characters = "This card was generated dynamically using the Model Context Protocol (MCP) and Penpot's Plugin API.\\n\\nEverything is styled programmatically, from the colors and borders to the layout constraints.";
            body.fontSize = 15;
            body.fills = [{ fillColor: "#94A3B8", fillOpacity: 1 }]; // Slate 400
            
            body.growType = "auto-height";
            body.resize(340, 150);
            penpotUtils.setParentXY(body, 80, 130);
            board.appendChild(body);

            console.log("Creating Status Indicator badge...");
            const badge = penpot.createRectangle();
            badge.name = "Status Badge";
            badge.resize(110, 32);
            penpotUtils.setParentXY(badge, 80, 270);
            badge.fills = [{ fillColor: "#10B981", fillOpacity: 0.15 }]; // Emerald 500 with opacity
            badge.strokes = [{ strokeColor: "#10B981", strokeWidth: 1.5, strokeOpacity: 1 }];
            badge.borderRadius = 6;
            board.appendChild(badge);

            const badgeText = penpot.createText();
            badgeText.name = "Badge Text";
            badgeText.characters = "● Connected";
            badgeText.fontSize = 12;
            badgeText.fills = [{ fillColor: "#10B981", fillOpacity: 1 }];
            badgeText.growType = "auto-width";
            penpotUtils.setParentXY(badgeText, 95, 278);
            board.appendChild(badgeText);

            return {
                status: "success",
                boardId: board.id,
                message: "Beautiful dark mode card created successfully on board '" + board.name + "'!"
            };
        `;

        const executeResult = await client.callTool({
            name: "execute_code",
            arguments: {
                code: codeToExecute
            }
        });

        console.log("Execution Result:\n", (executeResult.content?.[0] as any)?.text || JSON.stringify(executeResult, null, 2));

    } catch (error) {
        console.error("An error occurred during demo execution:", error);
    } finally {
        // Close transport
        try {
            await transport.close();
            console.log("\nDisconnected from server.");
        } catch (_) {}
    }
}

runDemo();

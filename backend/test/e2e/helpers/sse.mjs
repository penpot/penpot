import { createParser } from "eventsource-parser";

export function parseSSE(text) {
  const events = [];
  const parser = createParser({
    onEvent(event) {
      events.push({ event: event.event || "message", data: event.data });
    },
  });
  parser.feed(text);
  return events;
}

export function extractResult(events) {
  const endEvent = events.find((e) => e.event === "end");
  if (!endEvent) {
    const errEvent = events.find((e) => e.event === "error");
    if (errEvent) {
      throw new Error(`SSE error: ${errEvent.data}`);
    }
    throw new Error(`No end event found in SSE stream. Events: ${JSON.stringify(events)}`);
  }

  const raw = JSON.parse(endEvent.data);

  // Transit JSON verbose format:
  // For URIs (e.g. asset URL): {"~#uri":"https://..."}
  // For objects: {"~:key":"val",...} or ["^ ","~:key","val",...]
  // For strings: plain string
  if (raw && typeof raw === "object") {
    // Tagged URI
    if ("~#uri" in raw) {
      return raw["~#uri"];
    }
    // Transit map with ~:value key
    if ("~:value" in raw) {
      const value = raw["~:value"];
      if (Array.isArray(value)) {
        return transitArrayToObj(value);
      }
      return value;
    }
    // Direct transit map (keys starting with ~:)
    const firstKey = Object.keys(raw)[0];
    if (firstKey && firstKey.startsWith("~:")) {
      return transitMapToObj(raw);
    }
  }

  return raw;
}

function transitArrayToObj(arr) {
  // Transit verbose object: ["^ ","~:key1","val1","~:key2","val2",...]
  const obj = {};
  for (let i = 1; i < arr.length; i += 2) {
    const key = arr[i].replace(/^~:/, "");
    const val = arr[i + 1];
    obj[key] = val;
  }
  return obj;
}

function transitMapToObj(map) {
  // Transit verbose map: {"~:key1":"val1","~:key2":"val2",...}
  const obj = {};
  for (const [key, val] of Object.entries(map)) {
    const cleanKey = key.replace(/^~:/, "");
    obj[cleanKey] = val;
  }
  return obj;
}

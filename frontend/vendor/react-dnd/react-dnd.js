import { useDrag, useDrop } from "react-dnd/dist/esm/hooks";
import { DndProvider } from "react-dnd/dist/esm/common";
import HTML5Backend from "react-dnd-html5-backend";

if (typeof self !== "undefined") { init(self); }
else if (typeof global !== "undefined") { init(global); }
else if (typeof window !== "undefined") { init(window); }
else { throw new Error("unsupported execution environment"); }

function init(g) {
  g.ReactDnd = {
    useDrag,
    useDrop,
    DndProvider,
    HTML5Backend
  };
}


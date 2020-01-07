import randomcolor from "randomcolor";

if (typeof self !== "undefined") { init(self); }
else if (typeof global !== "undefined") { init(global); }
else if (typeof window !== "undefined") { init(window); }
else { throw new Error("unsupported execution environment"); }

function init(g) {
  g.randomcolor = randomcolor;
}


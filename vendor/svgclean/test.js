const svgclean = require("./src/svgclean");
const fs   = require("fs");

fs.readFile("./test.svg", "utf-8", (err, data) => {
  svgclean.optimize({}, data).then((result) => {
    console.dir(result);
  });
});

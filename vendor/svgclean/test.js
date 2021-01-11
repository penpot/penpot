const svgc = require("./main.js");
const fs   = require("fs");

fs.readFile("./test2.svg", "utf-8", (err, data) => {
  svgc.optimize(data).then((result) => {
    fs.writeFileSync("./result.svg", result);
    // console.log(result);
    // console.log();
  });
});

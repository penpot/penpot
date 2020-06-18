function svgDataURL(svg) {
  var svgAsXML = new XMLSerializer().serializeToString(svg);
  return "data:image/svg+xml," + encodeURIComponent(svgAsXML);
}

window.addEventListener("DOMContentLoaded", event => {
  html2canvas(document.querySelector("foreignObject"), {
    logging: false,
    scale: 4
  }).then((canvas) => {


    let dataURL = canvas.toDataURL();
    let image = document.createElementNS("http://www.w3.org/2000/svg", "image");
    image.setAttribute("href", dataURL);
    image.setAttribute("width", "400");
    image.setAttribute("height", "200");
    image.width = 400;
    image.height = 200;

    document.querySelector("foreignObject").replaceWith(image);
    document.body.appendChild(canvas);

    // html2canvas(document.querySelector("svg")).then(canvas => {
    //   document.body.appendChild(canvas);
    // });
  });
  //console.log(svgDataURL(svg));
});

import CanvasKitInit from 'canvaskit-wasm/bin/canvaskit.js';

class CanvasKit {
  constructor(canvasId, CanvasKit, vbox) {
    this.canvasId = canvasId;
    this.CanvasKit = CanvasKit;
    this.vbox = vbox;
  }

  static async initialize(canvasId, vbox) {
    const kit = await CanvasKitInit();
    return new CanvasKit(canvasId, kit, vbox);
  }

  setVbox(vbox) {
    this.vbox = vbox;
  }


clear() {
  const surface = this.CanvasKit.MakeCanvasSurface(this.canvasId)
  function draw(canvas) {
    canvas.clear(CanvasKit.TRANSPARENT);
  }
  surface.drawOnce(draw);  
}

  paintRect(shape) {
    const surface = this.CanvasKit.MakeCanvasSurface(this.canvasId)
    
    const self = this;
    function draw(canvas) {
      canvas.translate(- self.vbox.x, - self.vbox.y);
      const paint = new self.CanvasKit.Paint();
      // Drawing fills
      if (shape.fills) {
        for (const fill of shape.fills.reverse()) {          
          paint.setStyle(self.CanvasKit.PaintStyle.Fill);
          const color = self.CanvasKit.parseColorString(fill["fill-color"]);
          const opacity = fill["fill-opacity"]
          console.log("color", fill["fill-color"], fill["fill-opacity"])
          color[3] = opacity
          paint.setColor(color);
          const rr = self.CanvasKit.RRectXY(self.CanvasKit.LTRBRect(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height), 0, 0);        
          canvas.drawRRect(rr, paint);
        }
      }
      paint.delete();
    }

    surface.drawOnce(draw);  

    // // Drawing another border
    // const paint2 = new this.CanvasKit.Paint();
    // paint2.setColor(this.CanvasKit.Color4f(0.9, 0, 1.0, 1.0));
    // paint2.setStyle(this.CanvasKit.PaintStyle.Stroke);
    // paint2.setStrokeWidth(25.0);
    // paint2.setAntiAlias(true);
    // const rr2 = this.CanvasKit.RRectXY(this.CanvasKit.LTRBRect(x, y, width, height), 0, 0);
  
    // // Drawing a shadow
    // const paint3 = new this.CanvasKit.Paint();
    // paint3.setColor(this.CanvasKit.Color4f(0.9, 0, 1.0, 1.0));
    // paint3.setStyle(this.CanvasKit.PaintStyle.Fill);
    // const rr3 = this.CanvasKit.RRectXY(this.CanvasKit.LTRBRect(x, y, width, height), 0, 0);
    // const drop = this.CanvasKit.ImageFilter.MakeDropShadow(4, 4, 4, 4,this.CanvasKit.MAGENTA, null);
    // paint3.setImageFilter(drop)
  
    //   // Drawing a blur
    // const paint4 = new this.CanvasKit.Paint();
    // paint4.setColor(this.CanvasKit.Color4f(0.9, 0, 1.0, 1.0));
    // paint4.setStyle(this.CanvasKit.PaintStyle.Fill);
    // const rr4 = this.CanvasKit.RRectXY(this.CanvasKit.LTRBRect(x, y, width, height), 0, 0);
    // const blur = this.CanvasKit.ImageFilter.MakeBlur(4, 4, this.CanvasKit.TileMode.Decal, null);
    // paint4.setImageFilter(blur)
    // const self = this;
    // function draw(canvas) {
    //   canvas.translate(- self.vbox.x, - self.vbox.y);
    //   // canvas.scale(kk3, kk3);
    //   for (const d of toDraw) {
    //     canvas.drawRRect(d, paint);
    //   }
      
    //   // canvas.drawRRect(rr2, paint2);
    //   // canvas.drawRRect(rr3, paint3);
    //   // canvas.drawRRect(rr4, paint4);
    //   paint.delete();    
    //   // paint2.delete();    
    //   // paint3.delete();
    //   // paint4.delete();
    // }
    // surface.drawOnce(draw);  
  }
}

export { CanvasKit };

export function init() {
  return CanvasKitInit();
}
export function rect(CanvasKit, canvasId, x, y, width, height, kk1, kk2, kk3) {
  surface = CanvasKit.MakeCanvasSurface(canvasId)

  // Drawing a border
  console.log("rect:", x, y, width, height)
  const paint = new CanvasKit.Paint();
  paint.setColor(CanvasKit.Color4f(0.9, 0, 0, 1.0));
  paint.setStyle(CanvasKit.PaintStyle.Stroke);
  paint.setStrokeWidth(50.0);
  // paint.setStrokeCap(CanvasKit.StrokeCap.Round);
  paint.setAntiAlias(true);
  const rr = CanvasKit.RRectXY(CanvasKit.LTRBRect(x, y, width, height), 0, 0);

  // Drawing another border
  const paint2 = new CanvasKit.Paint();
  paint2.setColor(CanvasKit.Color4f(0.9, 0, 1.0, 1.0));
  paint2.setStyle(CanvasKit.PaintStyle.Stroke);
  paint2.setStrokeWidth(25.0);
  paint2.setAntiAlias(true);
  const rr2 = CanvasKit.RRectXY(CanvasKit.LTRBRect(x, y, width, height), 0, 0);

  // Drawing a shadow
  const paint3 = new CanvasKit.Paint();
  paint3.setColor(CanvasKit.Color4f(0.9, 0, 1.0, 1.0));
  paint3.setStyle(CanvasKit.PaintStyle.Fill);
  const rr3 = CanvasKit.RRectXY(CanvasKit.LTRBRect(x, y, width, height), 0, 0);
  const drop = CanvasKit.ImageFilter.MakeDropShadow(4, 4, 4, 4, CanvasKit.MAGENTA, null);
  paint3.setImageFilter(drop)


  // Drawing a blur
  const paint4 = new CanvasKit.Paint();
  paint4.setColor(CanvasKit.Color4f(0.9, 0, 1.0, 1.0));
  paint4.setStyle(CanvasKit.PaintStyle.Fill);
  const rr4 = CanvasKit.RRectXY(CanvasKit.LTRBRect(x, y, width, height), 0, 0);
  const blur = CanvasKit.ImageFilter.MakeBlur(4, 4, CanvasKit.TileMode.Decal, null);
  paint4.setImageFilter(blur)

  function draw(canvas) {
    canvas.translate(- kk1, - kk2);
    // canvas.scale(kk3, kk3);
    // canvas.drawRRect(rr, paint);
    // canvas.drawRRect(rr2, paint2);
    // canvas.drawRRect(rr3, paint3);
    canvas.drawRRect(rr4, paint4);
    paint.delete();    
    paint2.delete();    
    paint3.delete();
  }
  surface.drawOnce(draw);  
}

export function path(CanvasKit, canvasId, x, y, content, kk1, kk2, kk3) {
  // surface = CanvasKit.MakeCanvasSurface(canvasId)
  console.log("path:", x, y, content)

  surface = CanvasKit.MakeCanvasSurface(canvasId)
  const paint = new CanvasKit.Paint();
  paint.setStrokeWidth(1.0);
  paint.setAntiAlias(true);
  paint.setColor(CanvasKit.Color4f(0.9, 0, 1.0, 1.0));
  paint.setStyle(CanvasKit.PaintStyle.Stroke);
  const path = CanvasKit.Path.MakeFromSVGString(content);

  function draw(canvas) {
    canvas.translate(- kk1, - kk2);
    // canvas.scale(kk3, kk3);
    canvas.drawPath(path, paint);
    paint.delete();    
  }
  surface.drawOnce(draw);  
}

// export function shadow(CanvasKit, canvasId, kk1, kk2, kk3) {
//   console.log("CanvasKit", CanvasKit)
//   console.log("CanvasKit.ImageFilter", CanvasKit.ImageFilter.MakeDropShadow())
//   const paint = new CanvasKit.Paint();
//   paint.setColor(CanvasKit.Color4f(0.9, 0, 1.0, 1.0));
//   paint.setStyle(CanvasKit.PaintStyle.Fill);  
//     // var paint = SKPaint 
//     // { 
//     //     Color = SKColors.Red, 
//     //     Style = SKPaintStyle.Fill
//     // };
//     const drop = CanvasKit.ImageFilter.MakeDropShadow(0, 0, 4.0, 2.0, CanvasKit.MAGENTA, null);
//     const paint = new CanvasKit.Paint();
//     paint.setImageFilter(drop)

// }


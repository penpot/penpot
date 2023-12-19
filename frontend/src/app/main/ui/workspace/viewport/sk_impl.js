import CanvasKitInit from 'canvaskit-wasm/bin/canvaskit.js';

export function init() {
  return CanvasKitInit();
}
export function rect(CanvasKit, canvasId, x, y, width, height, kk1, kk2, kk3) {
  surface = CanvasKit.MakeCanvasSurface(canvasId)
  console.log("rect:", x, y, width, height)
  const paint = new CanvasKit.Paint();
  paint.setColor(CanvasKit.Color4f(0.9, 0, 0, 1.0));
  paint.setStyle(CanvasKit.PaintStyle.Stroke);
  paint.setStrokeWidth(50.0);
  // paint.setStrokeCap(CanvasKit.StrokeCap.Round);
  paint.setAntiAlias(true);
  const rr = CanvasKit.RRectXY(CanvasKit.LTRBRect(x, y, width, height), 0, 0);

  const paint2 = new CanvasKit.Paint();
  paint2.setColor(CanvasKit.Color4f(0.9, 0, 1.0, 1.0));
  paint2.setStyle(CanvasKit.PaintStyle.Stroke);
  paint2.setStrokeWidth(25.0);
  paint2.setAntiAlias(true);
  const rr2 = CanvasKit.RRectXY(CanvasKit.LTRBRect(x, y, width, height), 0, 0);

  function draw(canvas) {
    canvas.translate(- kk1, - kk2);
    // canvas.scale(kk3, kk3);
    canvas.drawRRect(rr, paint);
    canvas.drawRRect(rr2, paint2);
    paint.delete();    
    paint2.delete();    
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

export function clear(CanvasKit, canvasId) {
  surface = CanvasKit.MakeCanvasSurface(canvasId)

  function draw(canvas) {
    // canvas.clear(CanvasKit.WHITE);
    canvas.translate(400, 400);

  }
  surface.drawOnce(draw);  
}
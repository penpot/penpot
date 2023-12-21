import CanvasKitInit from 'canvaskit-wasm/bin/canvaskit.js';

class CanvasKit {
  constructor(canvasId, CanvasKit, fontManager, vbox) {
    this.canvasId = canvasId;
    this.CanvasKit = CanvasKit;
    this.vbox = vbox;
    this.fontManager = fontManager;
    this.onDraw = this.onDraw.bind(this)
  }

  static async loadFont(url) {
    const response = await fetch(url)
    return response.arrayBuffer()
  }

  static async initialize(canvasId, vbox) {
    const kit = await CanvasKitInit();
    const fontData = await this.loadFont("/fonts/WorkSans-Regular.woff2");
    const fontManager = kit.FontMgr.FromData([fontData]);
    return new CanvasKit(canvasId, kit, fontManager, vbox);
  }

  setVbox(vbox) {
    this.vbox = vbox;
  }

  getTextDirectionFromString(textDirection) {
    switch (textDirection) {
      case 'ltr': return this.CanvasKit.TextDirection.LTR;
      case 'rtl': return this.CanvasKit.TextDirection.RTL;
      default: return this.CanvasKit.TextDirection.LTR;
    }
  }

  getTextDecorationFromString(textDecoration) {
    switch(textDecoration) {
      case 'underline': return this.CanvasKit.UnderlineDecoration;
      case 'overline': return this.CanvasKit.OverlineDecoration;
      case 'line-through': return this.CanvasKit.LineThroughDecoration;
      case 'none': return this.CanvasKit.NoDecoration;
      default: return this.CanvasKit.NoDecoration;
    }
  }

  getTextAlignFromString(textAlign) {
    console.log('text-align-from-string', textAlign)
    switch (textAlign) {
      case 'left': return this.CanvasKit.TextAlign.Left;
      case 'center': return this.CanvasKit.TextAlign.Center;
      case 'right': return this.CanvasKit.TextAlign.Right;
      case 'justify': return this.CanvasKit.TextAlign.Justify;
      case 'start': return this.CanvasKit.TextAlign.Start;
      case 'end': return this.CanvasKit.TextAlign.End;
      default: return this.CanvasKit.TextAlign.Left;
    }
  }

  getBlendModeFromObject(object) {
    switch (object['blend-mode']) {
      case 'normal': return this.CanvasKit.BlendMode.SrcOver;
      case 'multiply': return this.CanvasKit.BlendMode.Multiply;
      case 'screen': return this.CanvasKit.BlendMode.Screen;
      case 'overlay': return this.CanvasKit.BlendMode.Overlay;
      case 'darken': return this.CanvasKit.BlendMode.Darken;
      case 'lighten': return this.CanvasKit.BlendMode.Lighten;
      case 'color-dodge': return this.CanvasKit.BlendMode.ColorDodge;
      case 'color-burn': return this.CanvasKit.BlendMode.ColorBurn;
      case 'hard-light': return this.CanvasKit.BlendMode.HardLight;
      case 'soft-light': return this.CanvasKit.BlendMode.SoftLight;
      case 'difference': return this.CanvasKit.BlendMode.Difference;
      case 'exclusion': return this.CanvasKit.BlendMode.Exclusion;
      case 'hue': return this.CanvasKit.BlendMode.Hue;
      case 'saturation': return this.CanvasKit.BlendMode.Saturation;
      case 'color': return this.CanvasKit.BlendMode.Color;
      case 'luminosity': return this.CanvasKit.BlendMode.Luminosity;
      default: return this.CanvasKit.BlendMode.SrcOver;
    }
  }

  drawFrame(canvas, object) {
    this.drawRect(canvas, object)
  }

  drawRect(canvas, shape) {
    const paint = new this.CanvasKit.Paint();
    paint.setBlendMode(this.getBlendModeFromObject(shape));
    // Drawing fills
    if (shape.fills) {
      for (const fill of shape.fills.reverse()) {
        paint.setStyle(this.CanvasKit.PaintStyle.Fill);
        const color = this.CanvasKit.parseColorString(fill["fill-color"]);
        const opacity = fill["fill-opacity"];
        console.log("fill color", fill["fill-color"], fill["fill-opacity"]);
        color[3] = opacity;
        paint.setColor(color);
        const rr = this.CanvasKit.RRectXY(
          this.CanvasKit.LTRBRect(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height),
          0,
          0,
        );
        canvas.drawRRect(rr, paint);
      }
    }
    // Drawing strokes
    if (shape.strokes) {
      for (const stroke of shape.strokes.reverse()) {
        paint.setStyle(this.CanvasKit.PaintStyle.Stroke);
        const color = this.CanvasKit.parseColorString(stroke["stroke-color"]);
        const opacity = stroke["stroke-opacity"];
        const strokeWidth = stroke["stroke-width"];
        paint.setStrokeWidth(strokeWidth);
        console.log("stroke", stroke, stroke["stroke-color"], stroke["stroke-opacity"], strokeWidth);
        color[3] = opacity;
        paint.setColor(color);
        const rr = this.CanvasKit.RRectXY(
          this.CanvasKit.LTRBRect(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height),
          0,
          0,
        );
        canvas.drawRRect(rr, paint);
      }
    }
    paint.delete();
  }

  drawCircle(canvas, shape) {
    const paint = new this.CanvasKit.Paint();
    paint.setAntiAlias(true);
    paint.setBlendMode(this.getBlendModeFromObject(shape));
    // Drawing fills
    if (shape.fills) {
      for (const fill of shape.fills.reverse()) {
        paint.setStyle(this.CanvasKit.PaintStyle.Fill);
        const color = this.CanvasKit.parseColorString(fill["fill-color"]);
        const opacity = fill["fill-opacity"];
        color[3] = opacity;
        paint.setColor(color);
        const rr = this.CanvasKit.RRectXY(
          this.CanvasKit.LTRBRect(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height),
          0,
          0,
        );
        canvas.drawOval(rr, paint);
      }
    }
    // Drawing strokes
    if (shape.strokes) {
      for (const stroke of shape.strokes.reverse()) {
        paint.setStyle(this.CanvasKit.PaintStyle.Stroke);
        const color = this.CanvasKit.parseColorString(stroke["stroke-color"]);
        const opacity = stroke["stroke-opacity"];
        const strokeWidth = stroke["stroke-width"];
        paint.setStrokeWidth(strokeWidth);
        color[3] = opacity;
        paint.setColor(color);
        const rr = this.CanvasKit.RRectXY(
          this.CanvasKit.LTRBRect(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height),
          0,
          0,
        );
        canvas.drawOval(rr, paint);
      }
    }
    paint.delete();
  }

  drawPath(canvas, shape) {
    const path = new this.CanvasKit.Path();
    for (const { command, params } of shape.content) {
      switch (command) {
        // :move-to "M"
        // :close-path "Z"
        // :line-to "L"
        // :line-to-horizontal "H"
        // :line-to-vertical "V"
        // :curve-to "C"
        // :smooth-curve-to "S"
        // :quadratic-bezier-curve-to "Q"
        // :smooth-quadratic-bezier-curve-to "T"
        // :elliptical-arc "A"
        case 'move-to': path.moveTo(params.x, params.y); break;
        case 'line-to': path.lineTo(params.x, params.y); break;
        case 'line-to-horizontal': /* path.lineTo(...params); */ break;
        case 'line-to-vertical': /* path.lineTo(...params); */ break;
        case 'curve-to': path.cubicTo(params.c1x, params.c1y, params.c2x, params.c2y, params.x, params.y); break;
        case 'smooth-curve-to': path.cubicTo(...params); break;
        case 'quadratic-bezier-to': path.quadTo(...params); break;
        case 'smooth-quadratic-bezier-curve-to': path.quadTo(...params); break;
        case 'elliptical-arc': path.arcTo(...params); break;
        case 'close-path': path.close(); break;
      }
    }

    const paint = new this.CanvasKit.Paint();
    paint.setAntiAlias(true);
    paint.setBlendMode(this.getBlendModeFromObject(shape));
    // Drawing fills
    if (shape.fills) {
      for (const fill of shape.fills.reverse()) {
        paint.setStyle(this.CanvasKit.PaintStyle.Fill);
        const color = this.CanvasKit.parseColorString(fill["fill-color"]);
        const opacity = fill["fill-opacity"];
        color[3] = opacity;
        paint.setColor(color);
        const rr = this.CanvasKit.RRectXY(
          this.CanvasKit.LTRBRect(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height),
          0,
          0,
        );
        canvas.drawPath(path, paint);
      }
    }
    // Drawing strokes
    if (shape.strokes) {
      for (const stroke of shape.strokes.reverse()) {
        paint.setStyle(this.CanvasKit.PaintStyle.Stroke);
        const color = this.CanvasKit.parseColorString(stroke["stroke-color"]);
        const opacity = stroke["stroke-opacity"];
        const strokeWidth = stroke["stroke-width"];
        paint.setStrokeWidth(strokeWidth);
        color[3] = opacity;
        paint.setColor(color);
        const rr = this.CanvasKit.RRectXY(
          this.CanvasKit.LTRBRect(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height),
          0,
          0,
        );
        canvas.drawPath(path, paint);
      }
    }
    paint.delete();
  }

  drawTextRun(canvas, shape, textRun) {
    for (const child of textRun.children) {
      if (child.type === 'paragraph') {
        const textDirection = this.getTextDirectionFromString(child["text-direction"]);
        const textAlign = this.getTextAlignFromString(child["text-align"]);
        console.log("text-align", textAlign);
        for (const paragraphText of child.children) {
          if (paragraphText.text === '') continue;
          for (const fill of paragraphText.fills) {
            const paragraphStyle = new this.CanvasKit.ParagraphStyle({
              textDirection,
              textStyle: {
                color: this.CanvasKit.parseColorString(fill["fill-color"]),
                decoration: this.getTextDecorationFromString(paragraphText["text-decoration"]),
                fontFamilies: [paragraphText["font-family"]],
                fontSize: parseInt(paragraphText["font-size"], 10),
                /*
                // TODO: Hacer una función que devuelva el weight o un valor de los del enum
                fontStyle: {
                  weight: parseInt(textRun['font-weight'], 10),
                },
                */
                letterSpacing: parseFloat(paragraphText["letter-spacing"]) || -1,
              },
              textAlign,
            });
            const text = paragraphText.text;
            const builder = this.CanvasKit.ParagraphBuilder.Make(paragraphStyle, this.fontManager);
            builder.addText(text);
            const paragraph = builder.build();
            const layoutWidth = shape["grow-type"] === "fixed" ? shape.width : Infinity;
            console.log(layoutWidth)
            paragraph.layout(layoutWidth); // width in pixels to use when wrapping text
            canvas.drawParagraph(paragraph, shape.x, shape.y);
          }
        }
      } else {
        this.drawTextRun(canvas, shape, child)
      }
    }

  }

  drawText(canvas, shape) {
    console.log('drawText', shape)
    this.drawTextRun(canvas, shape, shape.content)
    /*
    const font = new this.CanvasKit.Font(null, 200);
    const paint = new this.CanvasKit.Paint();
    paint.setAntiAlias(true);
    paint.setColor(this.CanvasKit.Color4f(0.9, 0, 1.0, 1.0));
    // paint.setBlendMode(this.getBlendModeFromObject(shape));
    canvas.drawText("this picture has a round rect", 88, 58, paint, font);
    paint.delete();
    font.delete();
    */

    /*
    for (const positionData of shape['position-data']) {
      this.drawTextRun(canvas, shape, positionData)
    }
    */
  }

  drawGroup(canvas, object) {
    console.warn("To be implemented");
  }

  drawObject(canvas, object) {
    console.log(canvas, object);
    console.log(canvas.save());
    canvas.rotate(object.rotation, object.x + object.width / 2, object.y + object.height / 2)
    switch (object.type) {
      case "frame":
        return this.drawFrame(canvas, object);
      case "rect":
        return this.drawRect(canvas, object);
      case "circle":
        return this.drawCircle(canvas, object);
      case "path":
        return this.drawPath(canvas, object);
      case "text":
        return this.drawText(canvas, object);
      case "group":
        return this.drawGroup(canvas, object);
    }
    canvas.restore(4)
  }

  onDraw(canvas) {
    // Es posible que no haga falta.
    // canvas.clear(CanvasKit.TRANSPARENT);
    console.log(this.vbox);
    canvas.save()
    canvas.scale(this.surface.width() / this.vbox.width, this.surface.height() / this.vbox.height);
    canvas.translate(-this.vbox.x, -this.vbox.y);
    for (const object of this.objects) {
      this.drawObject(canvas, object);
    }
    canvas.restore()
  }

  draw(objects) {
    this.objects = objects;
    this.surface = this.CanvasKit.MakeCanvasSurface(this.canvasId);
    this.surface.drawOnce(this.onDraw);
  }

  paintRect(shape) {
    const surface = this.CanvasKit.MakeCanvasSurface(this.canvasId);

    const self = this;
    function draw(canvas) {
      if (self.vbox) {
        canvas.translate(-self.vbox.x, -self.vbox.y);
      }

      const paint = new self.CanvasKit.Paint();
      // Drawing fills
      if (shape.fills) {
        for (const fill of shape.fills.reverse()) {
          paint.setStyle(self.CanvasKit.PaintStyle.Fill);
          const color = self.CanvasKit.parseColorString(fill["fill-color"]);
          const opacity = fill["fill-opacity"];
          color[3] = opacity;
          paint.setColor(color);
          const rr = self.CanvasKit.RRectXY(
            self.CanvasKit.LTRBRect(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height),
            0,
            0,
          );
          canvas.drawRRect(rr, paint);
        }
      }
      // Drawing strokes
      if (shape.strokes) {
        for (const stroke of shape.strokes.reverse()) {
          paint.setStyle(self.CanvasKit.PaintStyle.Stroke);
          const color = self.CanvasKit.parseColorString(stroke["stroke-color"]);
          const opacity = stroke["stroke-opacity"];
          const strokeWidth = stroke["stroke-width"];
          paint.setStrokeWidth(strokeWidth);
          color[3] = opacity;
          paint.setColor(color);
          const rr = self.CanvasKit.RRectXY(
            self.CanvasKit.LTRBRect(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height),
            0,
            0,
          );
          canvas.drawRRect(rr, paint);

          // Inner stroke?
          // const rr2 = self.CanvasKit.RRectXY(self.CanvasKit.LTRBRect(shape.x, shape.y, shape.x + shape.width, shape.y + shape.height), 0, 0);
          // canvas.clipRRect(rr2, self.CanvasKit.ClipOp.Intersect, true);
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


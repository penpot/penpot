/**
 * Performance focused pure javascript implementation of the
 * SVG path parser.
 *
 * @author KALEIDOS INC
 * @license MPL-2.0 <https://www.mozilla.org/en-US/MPL/2.0/>
 */

import cljs from "goog:cljs.core";

const MOVE_TO = cljs.keyword("move-to");
const CLOSE_PATH = cljs.keyword("close-path");
const LINE_TO = cljs.keyword("line-to");
const CURVE_TO = cljs.keyword("curve-to");

const K_COMMAND = cljs.keyword("command");
const K_PARAMS = cljs.keyword("params");
const K_X = cljs.keyword("x");
const K_Y = cljs.keyword("y");
const K_C1X = cljs.keyword("c1x");
const K_C1Y = cljs.keyword("c1y");
const K_C2X = cljs.keyword("c2x");
const K_C2Y = cljs.keyword("c2y");

class Segment {
  constructor(command, params) {
    this.command = command;
    this.params = params;
  }

  toPersistentMap() {
    const fromArray = (data) => {
      return cljs.PersistentArrayMap.fromArray(data);
    }

    let command, params;

    switch(this.command) {
    case "M":
      command = MOVE_TO;
      params = fromArray([K_X, this.params[0], K_Y, this.params[1]]);
      break;

    case "Z":
      command = CLOSE_PATH;
      params = cljs.PersistentArrayMap.EMPTY;
      break;

    case "L":
      command = LINE_TO;
      params = fromArray([K_X, this.params[0], K_Y, this.params[1]]);
      break;

    case "C":
      command = CURVE_TO;
      params = fromArray([K_C1X, this.params[0],
                          K_C1Y, this.params[1],
                          K_C2X, this.params[2],
                          K_C2Y, this.params[3],
                          K_X, this.params[4],
                          K_Y, this.params[5]]);
      break;
    default:
      command = null
      params = null;
    }

    if (command === null || params === null) {
      throw new Error("invalid segment");
    }

    return fromArray([K_COMMAND, command,
                      K_PARAMS, params])
  }
}

function validCommand(c) {
  switch (c) {
  case "Z":
  case "M":
  case "L":
  case "C":
  case "Q":
  case "A":
  case "H":
  case "V":
  case "S":
  case "T":
  case "z":
  case "m":
  case "l":
  case "c":
  case "q":
  case "a":
  case "h":
  case "v":
  case "s":
  case "t":
    return true;
  default:
    return false;
  }
}

class Parser {
  constructor(string) {
    this._string = string;
    this._currentIndex = 0;
    this._endIndex = this._string.length;
    this._prevCommand = null;
    this._skipOptionalSpaces();
  }

  [Symbol.iterator]() {
    return this;
  }

  next() {
    const done = !this.hasNext();
    if (done) {
      return {done: true};
    } else {
      return {
        done: false,
        value: this.parseSegment()
      };
    }
  }

  hasNext() {
    if (this._currentIndex === 0) {
      const command = this._peekSegmentCommand();
      return ((this._currentIndex < this._endIndex) &&
              (command === "M" || command === "m"));
    } else {
      return this._currentIndex < this._endIndex;
    }
  }

  parseSegment() {
    var ch = this._string[this._currentIndex];
    var command = validCommand(ch) ? ch : null;

    if (command === null) {
      // Possibly an implicit command. Not allowed if this is the first command.
      if (this._prevCommand === null) {
        return null;
      }

      // Check for remaining coordinates in the current command.
      if ((ch === "+" || ch === "-" || ch === "." || (ch >= "0" && ch <= "9")) && this._prevCommand !== "Z") {
        if (this._prevCommand === "M") {
          command = "L";
        } else if (this._prevCommand === "m") {
          command = "l";
        } else {
          command = this._prevCommand;
        }
      } else {
        command = null;
      }

      if (command === null) {
        return null;
      }
    } else {
      this._currentIndex += 1;
    }

    this._prevCommand = command;

    var params = null;
    var cmd = command.toUpperCase();

    if (cmd === "H" || cmd === "V") {
      params = [this._parseNumber()];
    } else if (cmd === "M" || cmd === "L" || cmd === "T") {
      params = [this._parseNumber(), this._parseNumber()];
    } else if (cmd === "S" || cmd === "Q") {
      params = [this._parseNumber(), this._parseNumber(), this._parseNumber(), this._parseNumber()];
    } else if (cmd === "C") {
      params = [
        this._parseNumber(),
        this._parseNumber(),
        this._parseNumber(),
        this._parseNumber(),
        this._parseNumber(),
        this._parseNumber()
      ];
    } else if (cmd === "A") {
      params = [
        this._parseNumber(),
        this._parseNumber(),
        this._parseNumber(),
        this._parseArcFlag(),
        this._parseArcFlag(),
        this._parseNumber(),
        this._parseNumber()
      ];
    } else if (cmd === "Z") {
      this._skipOptionalSpaces();
      params = [];
    }

    if (params === null || params.indexOf(null) >= 0) {
      // Unknown command or known command with invalid params
      return null;
    } else {
      return new Segment(command, params);
    }
  }

  _peekSegmentCommand() {
    var ch = this._string[this._currentIndex];
    return validCommand(ch) ? ch : null;
  }

  _isCurrentSpace() {
    var ch = this._string[this._currentIndex];
    return ch <= " " && (ch === " " || ch === "\n" || ch === "\t" || ch === "\r" || ch === "\f");
  }

  _skipOptionalSpaces() {
    while (this._currentIndex < this._endIndex && this._isCurrentSpace()) {
      this._currentIndex += 1;
    }
    return this._currentIndex < this._endIndex;
  }

  _skipOptionalSpacesOrDelimiter() {
    if (this._currentIndex < this._endIndex &&
        !this._isCurrentSpace() &&
        this._string[this._currentIndex] !== ",") {
      return false;
    }

    if (this._skipOptionalSpaces()) {
      if (this._currentIndex < this._endIndex && this._string[this._currentIndex] === ",") {
        this._currentIndex += 1;
        this._skipOptionalSpaces();
      }
    }
    return this._currentIndex < this._endIndex;
  }

  // Parse a number from an SVG path. This very closely follows genericParseNumber(...) from
  // Source/core/svg/SVGParserUtilities.cpp.
  // Spec: http://www.w3.org/TR/SVG11/single-page.html#paths-PathDataBNF
  _parseNumber() {
    var exponent = 0;
    var integer = 0;
    var frac = 1;
    var decimal = 0;
    var sign = 1;
    var expsign = 1;
    var startIndex = this._currentIndex;

    this._skipOptionalSpaces();

    // Read the sign.
    if (this._currentIndex < this._endIndex && this._string[this._currentIndex] === "+") {
      this._currentIndex += 1;
    } else if (this._currentIndex < this._endIndex && this._string[this._currentIndex] === "-") {
      this._currentIndex += 1;
      sign = -1;
    }

    if (this._currentIndex === this._endIndex ||
        ((this._string[this._currentIndex] < "0" || this._string[this._currentIndex] > "9") &&
         this._string[this._currentIndex] !== ".")) {
      // The first chacter of a number must be one of [0-9+-.].
      return null;
    }

    // Read the integer part, build right-to-left.
    var startIntPartIndex = this._currentIndex;

    while (this._currentIndex < this._endIndex &&
           this._string[this._currentIndex] >= "0" &&
           this._string[this._currentIndex] <= "9") {
      this._currentIndex += 1; // Advance to first non-digit.
    }

    if (this._currentIndex !== startIntPartIndex) {
      var scanIntPartIndex = this._currentIndex - 1;
      var multiplier = 1;

      while (scanIntPartIndex >= startIntPartIndex) {
        integer += multiplier * (this._string[scanIntPartIndex] - "0");
        scanIntPartIndex -= 1;
        multiplier *= 10;
      }
    }

    // Read the decimals.
    if (this._currentIndex < this._endIndex && this._string[this._currentIndex] === ".") {
      this._currentIndex += 1;

      // There must be a least one digit following the .
      if (this._currentIndex >= this._endIndex ||
          this._string[this._currentIndex] < "0" ||
          this._string[this._currentIndex] > "9") {
        return null;
      }

      while (this._currentIndex < this._endIndex &&
             this._string[this._currentIndex] >= "0" &&
             this._string[this._currentIndex] <= "9") {
        frac *= 10;
        decimal += (this._string[this._currentIndex] - "0") / frac;
        this._currentIndex += 1;
      }
    }

    // Read the exponent part.
    if (this._currentIndex !== startIndex &&
        this._currentIndex + 1 < this._endIndex &&
        (this._string[this._currentIndex] === "e" || this._string[this._currentIndex] === "E") &&
        (this._string[this._currentIndex + 1] !== "x" && this._string[this._currentIndex + 1] !== "m")) {
      this._currentIndex += 1;

      // Read the sign of the exponent.
      if (this._string[this._currentIndex] === "+") {
        this._currentIndex += 1;
      } else if (this._string[this._currentIndex] === "-") {
        this._currentIndex += 1;
        expsign = -1;
      }

      // There must be an exponent.
      if (this._currentIndex >= this._endIndex ||
          this._string[this._currentIndex] < "0" ||
          this._string[this._currentIndex] > "9") {
        return null;
      }

      while (this._currentIndex < this._endIndex &&
             this._string[this._currentIndex] >= "0" &&
             this._string[this._currentIndex] <= "9") {
        exponent *= 10;
        exponent += (this._string[this._currentIndex] - "0");
        this._currentIndex += 1;
      }
    }

    var number = integer + decimal;
    number *= sign;

    if (exponent) {
      number *= Math.pow(10, expsign * exponent);
    }

    if (startIndex === this._currentIndex) {
      return null;
    }

    this._skipOptionalSpacesOrDelimiter();

    return number;
  }

  _parseArcFlag() {
    if (this._currentIndex >= this._endIndex) {
      return null;
    }

    var flag = null;
    var flagChar = this._string[this._currentIndex];

    this._currentIndex += 1;

    if (flagChar === "0") {
      flag = 0;
    } else if (flagChar === "1") {
      flag = 1;
    } else {
      return null;
    }

    this._skipOptionalSpacesOrDelimiter();
    return flag;
  }
};

function absolutizePathData(pdata) {
  var currentX = null;
  var currentY = null;

  var subpathX = null;
  var subpathY = null;

  for (let i=0; i<pdata.length; i++) {
    let segment = pdata[i];
    switch(segment.command) {
    case "M":
      var x = segment.params[0];
      var y = segment.params[1];

      subpathX = x;
      subpathY = y;
      currentX = x;
      currentY = y;
      break;

    case "m":
      var x = currentX + segment.params[0];
      var y = currentY + segment.params[1];

      segment.command = "M";
      segment.params[0] = x;
      segment.params[1] = y;

      subpathX = x;
      subpathY = y;

      currentX = x;
      currentY = y;
      break;

    case "L":
      var x = segment.params[0];
      var y = segment.params[1];

      currentX = x;
      currentY = y;
      break;

    case "l":
      var x = currentX + segment.params[0];
      var y = currentY + segment.params[1];

      segment.command = "L";
      segment.params[0] = x;
      segment.params[1] = y;

      currentX = x;
      currentY = y;
      break;

    case "C":
      var x = segment.params[4];
      var y = segment.params[5];

      currentX = x;
      currentY = y;
      break;

    case "c":
      var x1 = currentX + segment.params[0];
      var y1 = currentY + segment.params[1];
      var x2 = currentX + segment.params[2];
      var y2 = currentY + segment.params[3];
      var x = currentX + segment.params[4];
      var y = currentY + segment.params[5];

      segment.command = "C";
      segment.params[0] = x1;
      segment.params[1] = y1;
      segment.params[2] = x2;
      segment.params[3] = y2;
      segment.params[4] = x;
      segment.params[5] = y;

      currentX = x;
      currentY = y;
      break;

    case "Q":
      var x = segment.params[2];
      var y = segment.params[3];
      currentX = x;
      currentY = y;
      break;


    case "q":
      var x1 = currentX + segment.params[0];
      var y1 = currentY + segment.params[1];
      var x = currentX + segment.params[2];
      var y = currentY + segment.params[3];

      segment.command = "Q";
      segment.params[0] = x1;
      segment.params[1] = y1;
      segment.params[2] = x;
      segment.params[3] = y;

      currentX = x;
      currentY = y;
      break;

    case "A":
      var x = segment.params[5];
      var y = segment.params[6];
      currentX = x;
      currentY = y;
      break;

    case "a":
      var x = currentX + segment.params[5];
      var y = currentY + segment.params[6];

      segment.command = "A";
      segment.params[5] = x;
      segment.params[6] = y;

      currentX = x;
      currentY = y;
      break;

    case "H":
      var x = segment.params[0];
      currentX = x;
      break;

    case "h":
      var x = currentX + segment.params[0];
      segment.command = "H";
      segment.params[0] = x;
      currentX = x;
      break;

    case "V":
      var y = segment.params[0];
      currentY = y;
      break;

    case "v":
      var y = currentY + segment.params[0];
      segment.command = "V";
      segment.params[0] = y;
      currentY = y;
      break;

    case "S":
      var x = segment.params[2];
      var y = segment.params[3];
      currentX = x;
      currentY = y;
      break;

    case "s":
      var x2 = currentX + segment.params[0];
      var y2 = currentY + segment.params[1];
      var x = currentX + segment.params[2];
      var y = currentY + segment.params[3];

      segment.command = "S";
      segment.params[0] = x2;
      segment.params[1] = y2;
      segment.params[2] = x;
      segment.params[3] = y;

      currentX = x;
      currentY = y;
      break;

    case "T":
      var x = segment.params[0];
      var y = segment.params[1]
      currentX = x;
      currentY = y;
      break;

    case "t":
      var x = currentX + segment.params[0];
      var y = currentY + segment.params[1]

      segment.command = "T";
      segment.params[0] = x;
      segment.params[1] = y;

      currentX = x;
      currentY = y;
      break;
    case "Z":
    case "z":
      currentX = subpathX;
      currentY = subpathY;
      segment.command = "Z";
      break;
    }
  }
  return pdata;
}


function unitVectorAngle(ux, uy, vx, vy) {
  const sign = (ux * vy - uy * vx) < 0 ? -1.0 : 1.0;
  let dot = ux * vx + uy * vy;
  dot = (dot > 1.0) ? 1.0 : (dot < -1.0) ? -1.0 : dot;
  return sign * Math.acos(dot);
}

function getArcCenter(x1, y1, x2, y2, fa, fs, rx, ry, sinPhi, cosPhi) {
  let x1p = (cosPhi * ((x1 - x2) / 2)) + (sinPhi * ((y1 - y2) / 2));
  let y1p = (-sinPhi * ((x1 - x2) / 2)) + (cosPhi * ((y1 - y2) / 2));

  let rxSq = rx * rx;
  let rySq = ry * ry;
  let x1pSq = x1p * x1p;
  let y1pSq = y1p * y1p;
  let radicant = rxSq * rySq - rxSq * y1pSq - rySq * x1pSq;

  radicant = (radicant < 0) ? 0 : radicant;
  radicant /= (rxSq * y1pSq + rySq * x1pSq);
  radicant = (Math.sqrt(radicant) * ((fa === fs) ? -1 : 1))

  let cxp = radicant * (rx / ry) * y1p;
  let cyp = radicant * (-ry / rx) * x1p;
  let cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2;
  let cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2;

  let v1x = (x1p - cxp) / rx;
  let v1y = (y1p - cyp) / ry;
  let v2x = (-x1p - cxp) / rx;
  let v2y = (-y1p - cyp) / ry;
  let theta1 = unitVectorAngle(1, 0, v1x, v1y);

  let dtheta = unitVectorAngle(v1x, v1y, v2x, v2y);
  dtheta = (fs === 0 && dtheta > 0) ? dtheta - Math.PI * 2 : dtheta;
  dtheta = (fs === 1 && dtheta < 0) ? dtheta + Math.PI * 2 : dtheta;

  return [cx, cy, theta1, dtheta];
}

function approximateUnitArc(theta1, dtheta) {
  const alpha = (4.0 / 3.0) * Math.tan(dtheta / 4);
  const x1 = Math.cos(theta1);
  const y1 = Math.sin(theta1);
  const x2 = Math.cos(theta1 + dtheta);
  const y2 = Math.sin(theta1 + dtheta);

  return [
    x1,
    y1,
    x1 - y1 * alpha,
    y1 + x1 * alpha,
    x2 + y2 * alpha,
    y2 - x2 * alpha,
    x2,
    y2
  ];
}

function processCurve(curve, cx, cy, rx, ry, sinPhi, cosPhi) {
  const x0 = curve[0] * rx;
  const y0 = curve[1] * ry;
  const x1 = curve[2] * rx;
  const y1 = curve[3] * ry;
  const x2 = curve[4] * rx;
  const y2 = curve[5] * ry;
  const x3 = curve[6] * rx;
  const y3 = curve[7] * ry;

  const xp0 = cosPhi * x0 - sinPhi * y0;
  const yp0 = sinPhi * x0 + cosPhi * y0;
  const xp1 = cosPhi * x1 - sinPhi * y1;
  const yp1 = sinPhi * x1 + cosPhi * y1;
  const xp2 = cosPhi * x2 - sinPhi * y2;
  const yp2 = sinPhi * x2 + cosPhi * y2;
  const xp3 = cosPhi * x3 - sinPhi * y3;
  const yp3 = sinPhi * x3 + cosPhi * y3;

  curve[0] = cx + xp0;
  curve[1] = cy + yp0;
  curve[2] = cx + xp1;
  curve[3] = cy + yp1;
  curve[4] = cx + xp2;
  curve[5] = cy + yp2;
  curve[6] = cx + xp3;
  curve[7] = cy + yp3;
}

export function arcToBeziers(x1, y1, x2, y2, fa, fs, rx, ry, phi) {
  const tau = Math.PI * 2;
  const phiTau = phi * tau / 360;

  const sinPhi = Math.sin(phiTau);
  const cosPhi = Math.cos(phiTau);

  let x1p = (cosPhi * (x1 - x2)) / 2 + (sinPhi * (y1 - y2)) / 2;
  let y1p = (-sinPhi * (x1 - x2)) / 2 + (cosPhi * (y1 - y2)) / 2;

  if (x1p === 0 && y1p === 0) {
    // we're asked to draw line to itself
    return [];
  }

  if (rx === 0 || ry === 0) {
      // one of the radii is zero
    return [];
  }

  rx = Math.abs(rx);
  ry = Math.abs(ry);

  let lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
  rx = (lambda > 1) ? rx * Math.sqrt(lambda) : rx;
  ry = (lambda > 1) ? ry * Math.sqrt(lambda) : ry;

  const cc = getArcCenter(x1, y1, x2, y2, fa, fs, rx, ry, sinPhi, cosPhi);
  const cx = cc[0];
  const cy = cc[1];
  let theta1 = cc[2];
  let dtheta = cc[3];

  const segments = Math.max(Math.ceil(Math.abs(dtheta) / (tau / 4)), 1);
  dtheta /= segments;

  const result = [];
  for (let i = 0; i < segments; i++) {
    const curve = approximateUnitArc(theta1, dtheta);
    processCurve(curve, cx, cy, rx, ry, sinPhi, cosPhi);
    result.push(new Segment("C", curve.slice(2)));

    theta1 += dtheta;
  }

  return result;
}

// Takes path data that consists only from absolute commands,
// returns path data that consists only from "M", "L", "C" and "Z"
// commands.
function simplifyPathData(pdata) {
  var result = [];
  var lastCommand = null;

  var lastControlX = null;
  var lastControlY = null;

  var currentX = null;
  var currentY = null;

  var subpathX = null;
  var subpathY = null;

  for (let i=0; i<pdata.length; i++) {
    const segment = pdata[i];
    const currentCommand = segment.command;

    switch(currentCommand) {
    case "M":
      var x = segment.params[0];
      var y = segment.params[1];
      result.push(segment);
      subpathX = x;
      subpathY = y;
      currentX = x;
      currentY = y;
      break;

    case "C":
      var x2 = segment.params[2];
      var y2 = segment.params[3];
      var x = segment.params[4];
      var y = segment.params[5];

      result.push(segment);

      lastControlX = x2;
      lastControlY = y2;
      currentX = x;
      currentY = y;
      break;

    case "L":
      var x = segment.params[0];
      var y = segment.params[1];

      result.push(segment);
      currentX = x;
      currentY = y;
      break;

    case "H":
      var x = segment.params[0];

      segment.command = "L";
      segment.params = [x, currentY];

      result.push(segment);
      currentX = x;
      break;

    case "V":
      var y = segment.params[0];

      segment.command = "L";
      segment.params = [currentX, y];
      result.push(segment);

      currentY = y;
      break;

    case "S":
      var x2 = segment.params[0];
      var y2 = segment.params[1];
      var x = segment.params[2];
      var y = segment.params[3];

      var cx1, cy1;

      if (lastCommand === "C" || lastCommand === "S") {
        cx1 = currentX + (currentX - lastControlX);
        cy1 = currentY + (currentY - lastControlY);
      } else {
        cx1 = currentX;
        cy1 = currentY;
      }


      segment.command = "C";
      segment.params = [cx1, cy1, x2, y2, x, y];
      result.push(segment);

      lastControlX = x2;
      lastControlY = y2;

      currentX = x;
      currentY = y;
      break;

    case "T":
      var x = segment.params[0];
      var y = segment.params[1];

      var x1, y1;

      if (lastCommand === "Q" || lastCommand === "T") {
        x1 = currentX + (currentX - lastControlX);
        y1 = currentY + (currentY - lastControlY);
      } else {
        x1 = currentX;
        y1 = currentY;
      }

      var cx1 = currentX + 2 * (x1 - currentX) / 3;
      var cy1 = currentY + 2 * (y1 - currentY) / 3;
      var cx2 = x + 2 * (x1 - x) / 3;
      var cy2 = y + 2 * (y1 - y) / 3;

      segment.command = "C";
      segment.params = [cx1, cy1, cx2, cy2, x, y];
      result.push(segment);

      lastControlX = x1;
      lastControlY = y1;

      currentX = x;
      currentY = y;
      break;

    case "Q":
      var x1 = segment.params[0];
      var y1 = segment.params[1];
      var x = segment.params[2];
      var y = segment.params[3];

      var cx1 = currentX + 2 * (x1 - currentX) / 3;
      var cy1 = currentY + 2 * (y1 - currentY) / 3;
      var cx2 = x + 2 * (x1 - x) / 3;
      var cy2 = y + 2 * (y1 - y) / 3;

      segment.command = "C";
      segment.params = [cx1, cy1, cx2, cy2, x, y];
      result.push(segment);

      lastControlX = x1;
      lastControlY = y1;

      currentX = x;
      currentY = y;
      break;

    case "A":
      var rx = Math.abs(segment.params[0]);
      var ry = Math.abs(segment.params[1]);
      var phi = segment.params[2];
      var fa = segment.params[3];
      var fs = segment.params[4];
      var x = segment.params[5];
      var y = segment.params[6];

      if (rx === 0 || ry === 0) {
        segment.command = "C";
        segment.params = [currentX, currentY, x, y, x, y];
        result.add(segment);

        currentX = x;
        currentY = y;
      } else if (currentX !== x || currentY !== y) {

        var segments = arcToBeziers(currentX, currentY, x, y, fa, fs, rx, ry, phi);
        result.push(...segments);

        currentX = x;
        currentY = y;
      }
      break;

    case "Z":
      result.push(segment);
      currentX = subpathX;
      currentY = subpathY;
      break;
    }

    lastCommand = currentCommand;
  }

  return result;
}

export function parse(string) {
  if (!string || string.length === 0) return [];

  try {
    var source = new Parser(string);
    var result = Array.from(source);

    result = absolutizePathData(result);
    result = simplifyPathData(result);

    return result;
  } catch (cause) {
    const msg = "unexpected exception parsing path";
    console.group(msg);
    console.log(`string: ${string}`)
    console.error(cause);
    console.groupEnd(msg);

    return [];
  }
}

/**
 * Performance focused pure java implementation of the
 * SVG path parser.
 *
 * @author KALEIDOS INC
 * @license MPL-2.0 <https://www.mozilla.org/en-US/MPL/2.0/>
 */

package app.common.svg.path;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import clojure.lang.Keyword;
import clojure.lang.AMapEntry;
import clojure.lang.PersistentArrayMap;

public class Parser {
  static final Keyword MOVE_TO = Keyword.intern("move-to");
  static final Keyword CLOSE_PATH = Keyword.intern("close-path");
  static final Keyword LINE_TO = Keyword.intern("line-to");
  static final Keyword CURVE_TO = Keyword.intern("curve-to");

  static final Keyword K_COMMAND = Keyword.intern("command");
  static final Keyword K_PARAMS = Keyword.intern("params");

  static final Keyword K_X = Keyword.intern("x");
  static final Keyword K_Y = Keyword.intern("y");
  static final Keyword K_C1X = Keyword.intern("c1x");
  static final Keyword K_C1Y = Keyword.intern("c1y");
  static final Keyword K_C2X = Keyword.intern("c2x");
  static final Keyword K_C2Y = Keyword.intern("c2y");

  public static List<Segment> parsePathData(String string) {
    if (string == null || string.length() == 0) {
      return new ArrayList<>();
    }

    List<Segment> pdata = new ArrayList<>();
    Iterator<Segment> parser = new ParserImpl(string);
    parser.forEachRemaining(pdata::add);

    return pdata;
  }

  public static List<Segment> parse(final String data) {
    var result = parsePathData(data);
    result = absolutizePathData(result);
    result = simplifyPathData(result);
    return result;
  }

  public static class Segment {
    public char command;
    public double[] params;

    public Segment(final char cmd, final double[] vals) {
      this.command = cmd;
      this.params = vals;
    }

    public Object toPersistentMap() {
      Keyword command = null;
      Object[] params = null;

      switch(this.command) {
      case 'M':
        command = MOVE_TO;
        params = new Object[] {K_X, this.params[0], K_Y, this.params[1]};
        break;

      case 'Z':
        command = CLOSE_PATH;
        break;

      case 'L':
        command = LINE_TO;
        params = new Object[] {K_X, this.params[0], K_Y, this.params[1]};
        break;

      case 'C':
        command = CURVE_TO;
        params = new Object[] {
          K_C1X, this.params[0],
          K_C1Y, this.params[1],
          K_C2X, this.params[2],
          K_C2Y, this.params[3],
          K_X, this.params[4],
          K_Y, this.params[5]
        };
        break;
      }

      if (command == null) {
        throw new IllegalArgumentException("invalid segment:" + this.command);
      }

      if (params == null) {
        return new PersistentArrayMap(new Object[] {K_COMMAND, command});
      } else {
        var _params = new PersistentArrayMap(params);
        return new PersistentArrayMap(new Object[] {K_COMMAND, command, K_PARAMS, _params});
      }
    }
  }

  public static class ParserImpl implements Iterator<Segment> {
    private final char[] string;
    private int currentIndex;
    private final int endIndex;
    private Character prevCommand;

    public ParserImpl(String string) {
      this.string = string.toCharArray();
      this.endIndex = this.string.length;
      this.currentIndex = 0;
      this.prevCommand = null;
      this.skipOptionalSpaces();
    }

    public boolean hasNext() {
      if (this.currentIndex == 0) {
        char command = peekSegmentCommand();
        return ((this.currentIndex < this.endIndex) &&
                (command == 'M' || command == 'm'));
      } else {
        return this.currentIndex < this.endIndex;
      }
    }

    public Segment next() {
      char currentChar = this.string[this.currentIndex];
      char command = (validCommand(currentChar)) ? currentChar : '\u0000';

      if (command == '\u0000') {
        if (this.prevCommand == null) {
          return null;
        }

        if ((currentChar == '+' || currentChar == '-' || currentChar == '.' || (currentChar >= '0' && currentChar <= '9')) && this.prevCommand != 'Z') {
          if (this.prevCommand == 'M') {
            command = 'L';
          } else if (this.prevCommand == 'm') {
            command = 'l';
          } else {
            command = this.prevCommand;
          }
        } else {
          command = '\u0000';
        }

        if (command == '\u0000') {
          return null;
        }
      } else {
        this.currentIndex++;
      }

      this.prevCommand = command;

      double[] params = null;
      char cmd = Character.toUpperCase(command);

      if (cmd == 'H' || cmd == 'V') {
        params = new double[] {
          parseNumber()
        };

      } else if (cmd == 'M' || cmd == 'L' || cmd == 'T') {
        params = new double[] {
          parseNumber(),
          parseNumber()
        };
      } else if (cmd == 'S' || cmd == 'Q') {
        params = new double[] {
          parseNumber(),
          parseNumber(),
          parseNumber(),
          parseNumber()
        };
      } else if (cmd == 'C') {
        params = new double[] {
          parseNumber(),
          parseNumber(),
          parseNumber(),
          parseNumber(),
          parseNumber(),
          parseNumber()
        };
      } else if (cmd == 'A') {
        params = new double[] {
          parseNumber(),
          parseNumber(),
          parseNumber(),
          parseArcFlag(),
          parseArcFlag(),
          parseNumber(),
          parseNumber()
        };
      } else if (cmd == 'Z') {
        skipOptionalSpaces();
        params = new double[] {};
      }

      return new Segment(command, params);
    }

    private char peekSegmentCommand() {
      char currentChar = this.string[this.currentIndex];
      return validCommand(currentChar) ? currentChar : '\u0000';
    }

    private boolean isCurrentSpace() {
      char currentChar = this.string[this.currentIndex];
      return currentChar <= ' ' && (currentChar == ' ' || currentChar == '\n' || currentChar == '\t' || currentChar == '\r' || currentChar == '\f');
    }

    private boolean skipOptionalSpaces() {
      while (this.currentIndex < this.endIndex && isCurrentSpace()) {
        this.currentIndex++;
      }
      return this.currentIndex < this.endIndex;
    }

    private boolean skipOptionalSpacesOrDelimiter() {
      if (this.currentIndex < this.endIndex &&
          !isCurrentSpace() &&
          this.string[this.currentIndex] != ',') {
        return false;
      }

      if (skipOptionalSpaces()) {
        if (this.currentIndex < this.endIndex && this.string[this.currentIndex] == ',') {
          this.currentIndex++;
          skipOptionalSpaces();
        }
      }
      return this.currentIndex < this.endIndex;
    }

    private Double parseNumber() {
      int exponent = 0;
      int integer = 0;
      double frac = 1;
      double decimal = 0;
      int sign = 1;
      int expsign = 1;
      int startIndex = this.currentIndex;

      skipOptionalSpaces();

      if (this.currentIndex < this.endIndex && this.string[this.currentIndex] == '+') {
        this.currentIndex++;
      } else if (this.currentIndex < this.endIndex && this.string[this.currentIndex] == '-') {
        this.currentIndex++;
        sign = -1;
      }

      if (this.currentIndex == this.endIndex ||
          ((this.string[this.currentIndex] < '0' || this.string[this.currentIndex] > '9') &&
           this.string[this.currentIndex] != '.')) {
        return null;
      }

      int startIntPartIndex = this.currentIndex;

      while (this.currentIndex < this.endIndex &&
             this.string[this.currentIndex] >= '0' &&
             this.string[this.currentIndex] <= '9') {
        this.currentIndex++;
      }

      if (this.currentIndex != startIntPartIndex) {
        int scanIntPartIndex = this.currentIndex - 1;
        int multiplier = 1;

        while (scanIntPartIndex >= startIntPartIndex) {
          integer += multiplier * (this.string[scanIntPartIndex] - '0');
          scanIntPartIndex--;
          multiplier *= 10;
        }
      }

      if (this.currentIndex < this.endIndex && this.string[this.currentIndex] == '.') {
        this.currentIndex++;

        if (this.currentIndex >= this.endIndex ||
            this.string[this.currentIndex] < '0' ||
            this.string[this.currentIndex] > '9') {
          return null;
        }

        while (this.currentIndex < this.endIndex &&
               this.string[this.currentIndex] >= '0' &&
               this.string[this.currentIndex] <= '9') {
          frac *= 10;
          decimal += (this.string[this.currentIndex] - '0') / frac;
          this.currentIndex++;
        }
      }

      if (this.currentIndex != startIndex &&
          this.currentIndex + 1 < this.endIndex &&
          (this.string[this.currentIndex] == 'e' || this.string[this.currentIndex] == 'E') &&
          (this.string[this.currentIndex + 1] != 'x' && this.string[this.currentIndex + 1] != 'm')) {
        this.currentIndex++;

        if (this.string[this.currentIndex] == '+') {
          this.currentIndex++;
        } else if (this.string[this.currentIndex] == '-') {
          this.currentIndex++;
          expsign = -1;
        }

        if (this.currentIndex >= this.endIndex ||
            this.string[this.currentIndex] < '0' ||
            this.string[this.currentIndex] > '9') {
          return null;
        }

        while (this.currentIndex < this.endIndex &&
               this.string[this.currentIndex] >= '0' &&
               this.string[this.currentIndex] <= '9') {
          exponent *= 10;
          exponent += (this.string[this.currentIndex] - '0');
          this.currentIndex++;
        }
      }

      double number = integer + decimal;
      number *= sign;

      if (exponent != 0) {
        number *= Math.pow(10, expsign * exponent);
      }

      if (startIndex == this.currentIndex) {
        return null;
      }

      skipOptionalSpacesOrDelimiter();

      return number;
    }

    private double parseArcFlag() {
      if (this.currentIndex >= this.endIndex) {
        // return null;
        throw new RuntimeException("");
      }

      Integer flag = null;
      char flagChar = this.string[this.currentIndex];

      this.currentIndex++;

      if (flagChar == '0') {
        flag = 0;
      } else if (flagChar == '1') {
        flag = 1;
      } else {
        throw new RuntimeException("");
        // return null;
      }

      skipOptionalSpacesOrDelimiter();
      return (double) flag;
    }

    private boolean validCommand(char c) {
      switch (c) {
      case 'Z':
      case 'M':
      case 'L':
      case 'C':
      case 'Q':
      case 'A':
      case 'H':
      case 'V':
      case 'S':
      case 'T':
      case 'z':
      case 'm':
      case 'l':
      case 'c':
      case 'q':
      case 'a':
      case 'h':
      case 'v':
      case 's':
      case 't':
        return true;
      default:
        return false;
      }
    }

  }

  public static double degToRad(double degrees) {
    return (Math.PI * degrees) / 180;
  }

  public static double[] rotate(double x, double y, double angleRad) {
    double X = x * Math.cos(angleRad) - y * Math.sin(angleRad);
    double Y = x * Math.sin(angleRad) + y * Math.cos(angleRad);
    return new double[]{X, Y};
  }

  public static List<Segment> absolutizePathData(List<Segment> pdata) {
    double currentX = 0;
    double currentY = 0;

    double subpathX = 0;
    double subpathY = 0;
    double x = 0;
    double y = 0;
    double x1 = 0;
    double y1 = 0;
    double x2 = 0;
    double y2 = 0;

    var length = pdata.size();

    for (int index=0; index < length; index++) {
      Segment segment = pdata.get(index);
      char command = segment.command;
      double[] params = segment.params;

      switch (command) {
      case 'M':
        x = params[0];
        y = params[1];
        subpathX = x;
        subpathY = y;
        currentX = x;
        currentY = y;
        break;

      case 'm':
        x = currentX + params[0];
        y = currentY + params[1];

        segment.command = 'M';
        segment.params[0] = x;
        segment.params[1] = y;

        subpathX = x;
        subpathY = y;
        currentX = x;
        currentY = y;
        break;

      case 'L':
        x = params[0];
        y = params[1];
        currentX = x;
        currentY = y;
        break;


      case 'l':
        x = currentX + params[0];
        y = currentY + params[1];

        segment.command = 'L';
        segment.params[0] = x;
        segment.params[1] = y;

        currentX = x;
        currentY = y;
        break;

      case 'C':
        x = params[4];
        y = params[5];
        currentX = x;
        currentY = y;
        break;

      case 'c':
        x1 = currentX + params[0];
        y1 = currentY + params[1];
        x2 = currentX + params[2];
        y2 = currentY + params[3];
        x = currentX + params[4];
        y = currentY + params[5];

        segment.command = 'C';
        segment.params[0] = x1;
        segment.params[1] = y1;
        segment.params[2] = x2;
        segment.params[3] = y2;
        segment.params[4] = x;
        segment.params[5] = y;

        currentX = x;
        currentY = y;
        break;

      case 'Q':
        x = params[2];
        y = params[3];
        currentX = x;
        currentY = y;
        break;

      case 'q':
        x1 = currentX + params[0];
        y1 = currentY + params[1];
        x = currentX + params[2];
        y = currentY + params[3];

        segment.command = 'Q';
        segment.params[0] = x1;
        segment.params[1] = y1;
        segment.params[2] = x;
        segment.params[3] = y;

        currentX = x;
        currentY = y;
        break;

      case 'A':
        x = params[5];
        y = params[6];

        currentX = x;
        currentY = y;
        break;

      case 'a':
        x = currentX + params[5];
        y = currentY + params[6];

        segment.command = 'A';
        segment.params[5] = x;
        segment.params[6] = y;
        currentX = x;
        currentY = y;
        break;

      case 'H':
        x = params[0];
        currentX = x;
        break;

      case 'h':
        x = currentX + params[0];
        segment.command = 'H';
        segment.params[0] = x;
        currentX = x;
        break;

      case 'V':
        y = params[0];
        currentY = y;
        break;

      case 'v':
        y = currentY + params[0];
        segment.command = 'V';
        segment.params[0] = y;
        currentY = y;
        break;

      case 'S':
        x = params[2];
        y = params[3];
        currentX = x;
        currentY = y;
        break;

      case 's':
        x2 = currentX + params[0];
        y2 = currentY + params[1];
        x = currentX + params[2];
        y = currentY + params[3];

        segment.command = 'S';
        segment.params[0] = x2;
        segment.params[1] = y2;
        segment.params[2] = x;
        segment.params[3] = y;

        currentX = x;
        currentY = y;
        break;

      case 'T':
        x = params[0];
        y = params[1];
        currentX = x;
        currentY = y;
        break;

      case 't':
        x = currentX + params[0];
        y = currentY + params[1];

        segment.command = 'T';
        segment.params[0] = x;
        segment.params[1] = y;

        currentX = x;
        currentY = y;
        break;

      case 'Z':
      case 'z':
        currentX = subpathX;
        currentY = subpathY;
        segment.command = 'Z';

        break;
      }
    }

    return pdata;
  }

  public static List<Segment> simplifyPathData(List<Segment> pdata) {
    var result = new ArrayList<Segment>(pdata.size());

    char lastCommand = ' ';
    double lastControlX = 0;
    double lastControlY = 0;
    double currentX = 0;
    double currentY = 0;
    double subpathX = 0;
    double subpathY = 0;
    double x = 0;
    double y = 0;
    double x1 = 0;
    double y1 = 0;
    double x2 = 0;
    double y2 = 0;
    double cx1 = 0;
    double cy1 = 0;
    double cx2 = 0;
    double cy2 = 0;
    double fa = 0;
    double fs = 0;
    double phi = 0;

    for (int i=0; i<pdata.size(); i++) {
      Segment segment = pdata.get(i);
      var currentCommand = segment.command;

      switch(currentCommand) {
      case 'M':
        x = segment.params[0];
        y = segment.params[1];
        result.add(segment);
        subpathX = x;
        subpathY = y;
        currentX = x;
        currentY = y;
        break;

      case 'C':
        x2 = segment.params[2];
        y2 = segment.params[3];
        x = segment.params[4];
        y = segment.params[5];

        result.add(segment);

        lastControlX = x2;
        lastControlY = y2;
        currentX = x;
        currentY = y;
        break;

      case 'L':
        x = segment.params[0];
        y = segment.params[1];

        result.add(segment);
        currentX = x;
        currentY = y;
        break;

      case 'H':
        x = segment.params[0];

        segment.command = 'L';
        segment.params = new double[] {x, currentY};

        result.add(segment);
        currentX = x;
        break;

      case 'V':
        y = segment.params[0];

        segment.command = 'L';
        segment.params = new double[] {currentX, y};

        result.add(segment);
        currentY = y;
        break;

      case 'S':
        x2 = segment.params[0];
        y2 = segment.params[1];
        x = segment.params[2];
        y = segment.params[3];

        if (lastCommand == 'C' || lastCommand == 'S') {
          cx1 = currentX + (currentX - lastControlX);
          cy1 = currentY + (currentY - lastControlY);
        } else {
          cx1 = currentX;
          cy1 = currentY;
        }

        segment.command = 'C';
        segment.params = new double[] {cx1, cy1, x2, y2, x, y};

        result.add(segment);

        lastControlX = x2;
        lastControlY = y2;

        currentX = x;
        currentY = y;
        break;

      case 'T':
        x = segment.params[0];
        y = segment.params[1];

        if (lastCommand == 'Q' || lastCommand == 'T') {
          x1 = currentX + (currentX - lastControlX);
          y1 = currentY + (currentY - lastControlY);
        } else {
          x1 = currentX;
          y1 = currentY;
        }

        cx1 = currentX + 2 * (x1 - currentX) / 3;
        cy1 = currentY + 2 * (y1 - currentY) / 3;
        cx2 = x + 2 * (x1 - x) / 3;
        cy2 = y + 2 * (y1 - y) / 3;

        segment.command = 'C';
        segment.params = new double[] {cx1, cy1, cx2, cy2, x, y};

        result.add(segment);

        lastControlX = x1;
        lastControlY = y1;

        currentX = x;
        currentY = y;
        break;

      case 'Q':
        x1 = segment.params[0];
        y1 = segment.params[1];
        x = segment.params[2];
        y = segment.params[3];

        cx1 = currentX + 2 * (x1 - currentX) / 3;
        cy1 = currentY + 2 * (y1 - currentY) / 3;
        cx2 = x + 2 * (x1 - x) / 3;
        cy2 = y + 2 * (y1 - y) / 3;

        segment.command = 'C';
        segment.params = new double[] {cx1, cy1, cx2, cy2, x, y};
        result.add(segment);

        lastControlX = x1;
        lastControlY = y1;

        currentX = x;
        currentY = y;
        break;

      case 'A':
        double rx = Math.abs(segment.params[0]);
        double ry = Math.abs(segment.params[1]);
        phi = segment.params[2];
        fa = segment.params[3];
        fs = segment.params[4];
        x = segment.params[5];
        y = segment.params[6];

        var segments = arcToBeziers(currentX, currentY, x, y, fa, fs, rx, ry, phi);
        result.addAll(segments);

        currentX = x;
        currentY = y;


        break;

      case 'Z':
        result.add(segment);
        currentX = subpathX;
        currentY = subpathY;
        break;
      }

      lastCommand = currentCommand;
    }

    return result;
  }

  public static double unitVectorAngle(double ux, double uy, double vx, double vy) {
    double sign = (ux * vy - uy * vx) < 0 ? -1.0 : 1.0;
    double dot = ux * vx + uy * vy;
    dot = (dot > 1.0) ? 1.0 : (dot < -1.0) ? -1.0 : dot;
    return sign * Math.acos(dot);
  }

  public static double[] getArcCenter(double x1, double y1, double x2, double y2,
                                      double fa, double fs, double rx, double ry,
                                      double sinPhi, double cosPhi) {

    double x1p = (cosPhi * ((x1 - x2) / 2)) + (sinPhi * ((y1 - y2) / 2));
    double y1p = (-sinPhi * ((x1 - x2) / 2)) + (cosPhi * ((y1 - y2) / 2));
    double rxSq = rx * rx;
    double rySq = ry * ry;
    double x1pSq = x1p * x1p;
    double y1pSq = y1p * y1p;
    double radicant = rxSq * rySq - rxSq * y1pSq - rySq * x1pSq;

    radicant = (radicant < 0) ? 0 : radicant;
    radicant /= (rxSq * y1pSq + rySq * x1pSq);
    radicant = (Math.sqrt(radicant) * ((fa == fs) ? -1 : 1));

    double cxp = radicant * (rx / ry) * y1p;
    double cyp = radicant * (-ry / rx) * x1p;
    double cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2;
    double cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2;

    double v1x = (x1p - cxp) / rx;
    double v1y = (y1p - cyp) / ry;
    double v2x = (-x1p - cxp) / rx;
    double v2y = (-y1p - cyp) / ry;
    double theta1 = unitVectorAngle(1, 0, v1x, v1y);

    double dtheta = unitVectorAngle(v1x, v1y, v2x, v2y);
    dtheta = (fs == 0 && dtheta > 0) ? dtheta - Math.PI * 2 : dtheta;
    dtheta = (fs == 1 && dtheta < 0) ? dtheta + Math.PI * 2 : dtheta;

    return new double[] {cx, cy, theta1, dtheta};
  }

  public static double[] approximateUnitArc(double theta1, double dtheta) {
    double alpha = (4.0 / 3.0) * Math.tan(dtheta / 4);
    double x1 = Math.cos(theta1);
    double y1 = Math.sin(theta1);
    double x2 = Math.cos(theta1 + dtheta);
    double y2 = Math.sin(theta1 + dtheta);

    return new double[] {
      x1,
      y1,
      x1 - y1 * alpha,
      y1 + x1 * alpha,
      x2 + y2 * alpha,
      y2 - x2 * alpha,
      x2,
      y2
    };
  }

  private static void processCurve(double[] curve, double cx, double cy, double rx, double ry, double sinPhi, double cosPhi) {
    double x0 = curve[0] * rx;
    double y0 = curve[1] * ry;
    double x1 = curve[2] * rx;
    double y1 = curve[3] * ry;
    double x2 = curve[4] * rx;
    double y2 = curve[5] * ry;
    double x3 = curve[6] * rx;
    double y3 = curve[7] * ry;

    double xp0 = cosPhi * x0 - sinPhi * y0;
    double yp0 = sinPhi * x0 + cosPhi * y0;
    double xp1 = cosPhi * x1 - sinPhi * y1;
    double yp1 = sinPhi * x1 + cosPhi * y1;
    double xp2 = cosPhi * x2 - sinPhi * y2;
    double yp2 = sinPhi * x2 + cosPhi * y2;
    double xp3 = cosPhi * x3 - sinPhi * y3;
    double yp3 = sinPhi * x3 + cosPhi * y3;

    curve[0] = cx + xp0;
    curve[1] = cy + yp0;
    curve[2] = cx + xp1;
    curve[3] = cy + yp1;
    curve[4] = cx + xp2;
    curve[5] = cy + yp2;
    curve[6] = cx + xp3;
    curve[7] = cy + yp3;
  }

  public static List<Segment> arcToBeziers(double x1, double y1, double x2, double y2,
                                           double fa, double fs, double rx, double ry, double phi) {
    double tau = Math.PI * 2;
    double phiTau = phi * tau / 360;

    double sinPhi = Math.sin(phiTau);
    double cosPhi = Math.cos(phiTau);

    double x1p = ((cosPhi * (x1 - x2)) / 2) + ((sinPhi * (y1 - y2)) / 2);
    double y1p = ((-sinPhi * (x1 - x2)) / 2) + ((cosPhi * (y1 - y2)) / 2);

    if (x1p == 0 && y1p == 0) {
      // we're asked to draw line to itself
      return new ArrayList<>();
    }

    if (rx == 0 || ry == 0) {
      // one of the radii is zero
      return new ArrayList<>();
    }

    rx = Math.abs(rx);
    ry = Math.abs(ry);

    double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
    rx = (lambda > 1) ? rx * Math.sqrt(lambda) : rx;
    ry = (lambda > 1) ? ry * Math.sqrt(lambda) : ry;

    var cc = getArcCenter(x1, y1, x2, y2, fa, fs, rx, ry, sinPhi, cosPhi);
    var cx = cc[0];
    var cy = cc[1];
    var theta1 = cc[2];
    var dtheta = cc[3];

    int segments = Math.max((int) Math.ceil(Math.abs(dtheta) / (tau / 4)), 1);
    dtheta /= segments;

    var result = new ArrayList<Segment>();
    for (int i = 0; i < segments; i++) {
      var curve = approximateUnitArc(theta1, dtheta);
      processCurve(curve, cx, cy, rx, ry, sinPhi, cosPhi);
      result.add(new Segment('C', Arrays.copyOfRange(curve, 2, curve.length)));
      theta1 += dtheta;
    }

    return result;
  }
}

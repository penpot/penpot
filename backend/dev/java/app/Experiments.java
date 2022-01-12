package app;

import clojure.lang.Seqable;
import clojure.lang.RT;
import clojure.lang.ISeq;
import clojure.lang.ILookup;
import clojure.lang.Keyword;


  // static public double[] multiply(final double[] m1, final double m2[]) {
  //   var result = new double[6];
  //   result[0] = (m1[0] * m2[0]) + (m1[2] * m2[1]);
  //   result[1] = (m1[1] * m2[0]) + (m1[3] * m2[1]);
  //   result[2] = (m1[0] * m2[2]) + (m1[2] * m2[3]);
  //   result[3] = (m1[1] * m2[2]) + (m1[3] * m2[3]);o

  //   result[4] = (m1[0] * m2[4]) + (m1[2] * m2[5]) + m1[4];
  //   result[5] = (m1[1] * m2[4]) + (m1[3] * m2[5]) + m1[5];
  //   return result;
  // }


public class Experiments {
  static public class Matrix implements Seqable, ILookup {
    public final double[] buff;

    public Matrix(double a, double b, double c, double d, double e, double f) {
      buff = new double[6];
      buff[0] = a;
      buff[1] = b;
      buff[2] = c;
      buff[3] = d;
      buff[4] = e;
      buff[5] = f;
    }

    public Matrix(final double [] otherBuffer) {
      buff = otherBuffer;
    }

    public ISeq seq() {
      return RT.seq(this.buff);
    }

    public Double valAt(final Object key) {
      var name = ((Keyword) key).getName();
      switch(name) {
      case "a": return this.buff[0];
      case "b": return this.buff[1];
      case "c": return this.buff[2];
      case "d": return this.buff[3];
      case "e": return this.buff[4];
      default: return this.buff[5];
      }
    }

    public Double valAt(final Object key, Object notFound) {
      throw new IllegalArgumentException("not supported");
    }
  }

  static public Matrix create(double a, double b, double c, double d, double e, double f) {
    var buff = new double[6];
    buff[0] = a;
    buff[1] = b;
    buff[2] = c;
    buff[3] = d;
    buff[4] = e;
    buff[5] = f;
    return new Matrix(buff);
  }

  static public Matrix multiply(final Matrix mt1, final Matrix mt2) {
    var result = new double[6];
    var m1 = mt1.buff;
    var m2 = mt1.buff;

    result[0] = (m1[0] * m2[0]) + (m1[2] * m2[1]);
    result[1] = (m1[1] * m2[0]) + (m1[3] * m2[1]);
    result[2] = (m1[0] * m2[2]) + (m1[2] * m2[3]);
    result[3] = (m1[1] * m2[2]) + (m1[3] * m2[3]);
    result[4] = (m1[0] * m2[4]) + (m1[2] * m2[5]) + m1[4];
    result[5] = (m1[1] * m2[4]) + (m1[3] * m2[5]) + m1[5];
    return new Matrix(result);
  }

  static private void multiplyMutating(final double[] m1, final double[] m2) {

    var m1a = m1[0];
    var m1b = m1[1];
    var m1c = m1[2];
    var m1d = m1[3];
    var m1e = m1[4];
    var m1f = m1[5];

    double[] result = m1;
    result[0] = (m1a * m2[0]) + (m1c * m2[1]);
    result[1] = (m1b * m2[0]) + (m1d * m2[1]);
    result[2] = (m1a * m2[2]) + (m1c * m2[3]);
    result[3] = (m1b * m2[2]) + (m1d * m2[3]);
    result[4] = (m1a * m2[4]) + (m1c * m2[5]) + m1e;
    result[5] = (m1b * m2[4]) + (m1d * m2[5]) + m1f;
  }

  static public Matrix multiplyBulk(final Matrix m1) {
    return m1;
  }

  static public Matrix multiplyBulk(final Matrix m1, final Matrix m2) {
    return multiply(m1, m2);
  }

  static public Matrix multiplyBulk(final Matrix m1, final Matrix m2, final Matrix m3) {
    var result = multiply(m1, m2);
    multiplyMutating(result.buff, m3.buff);
    return result;
  }

  static public Matrix multiplyBulk(final Matrix m1, final Matrix m2, final Matrix m3, final Matrix m4) {
    var result = multiply(m1, m2);
    multiplyMutating(result.buff, m3.buff);
    multiplyMutating(result.buff, m4.buff);
    return result;
  };

  static public Matrix multiplyBulk(final Matrix m1, final Matrix m2, final Matrix m3, final Matrix m4, final Matrix m5) {
    var result = multiply(m1, m2);
    multiplyMutating(result.buff, m3.buff);
    multiplyMutating(result.buff, m4.buff);
    multiplyMutating(result.buff, m5.buff);
    return result;
  }
}

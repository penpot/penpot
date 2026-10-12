;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.tokenscript.css-functions-test
  "The CSS color functions of the bundle (`rgb`, `rgba`, `hsl`, `hsla`),
  written as tokenscript schema functions."
  (:require
   [app.common.tokenscript :as tsc]
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.processor :as proc]
   [app.common.tokenscript.symbols :as sym]
   [clojure.test :as t]
   [common-tests.tokenscript.helpers :as h]))

(defn- resolve-hex
  "Hex string of the color `value` resolves to (with `{color}` as
  `#ff0000` and `{hue}` as 120), or the error code."
  [value]
  (let [cfg (h/full-config)
        res (proc/build {"color" {:value "#ff0000"}
                         "hue" {:value "120"}
                         "t" {:value value}}
                        {:config cfg})
        v (get-in res [:resolved "t"])]
    (cond
      (err/error-map? v) (:code v)
      (sym/color? v) (sym/to-string (tsc/color->hex cfg v) cfg)
      :else v)))

(t/deftest rgb-test
  (t/testing "comma and space separated channels"
    (t/is (= "#ff0000" (resolve-hex "rgb(255, 0, 0)")))
    (t/is (= "#f4fbfc" (resolve-hex "rgb(244 251 252)")))
    (t/is (= "#ff0000" (resolve-hex "RGB(255, 0, 0)"))))
  (t/testing "percentages"
    (t/is (= "#ff0000" (resolve-hex "rgb(100%, 0%, 0%)")))
    (t/is (= "#808080" (resolve-hex "rgb(50%, 50%, 50%)"))))
  (t/testing "alpha as number or percentage, with rgb and rgba"
    (t/is (= "#ff000080" (resolve-hex "rgba(255, 0, 0, 0.5)")))
    (t/is (= "#ff000080" (resolve-hex "rgba(255, 0, 0, 50%)")))
    (t/is (= "#ff000080" (resolve-hex "rgba(255 0 0 50%)")))
    (t/is (= "#ff000080" (resolve-hex "rgb(255, 0, 0, 0.5)"))))
  (t/testing "a color and an alpha"
    (t/is (= "#ff000080" (resolve-hex "rgba({color}, 0.5)")))
    (t/is (= "#ff000080" (resolve-hex "rgba({color}, 50%)")))
    (t/is (= "#ff000033" (resolve-hex "rgba(red, 0.2)")))
    (t/is (= "#ff0000" (resolve-hex "rgb({color})"))))
  (t/testing "channels are clamped and rounded like CSS"
    (t/is (= "#ff0000" (resolve-hex "rgb(255.5, 0, 0)")))
    (t/is (= "#ff0000" (resolve-hex "rgb(300, -5, 0)")))
    (t/is (= "#ff0000" (resolve-hex "rgba(255, 0, 0, 2)"))))
  (t/testing "an alpha of 1 is opaque, also over a color with alpha"
    (t/is (= "#ff0000" (resolve-hex "rgba(255, 0, 0, 1)")))
    (t/is (= "#ff0000" (resolve-hex "rgba(rgba({color}, 0.5), 1)"))))
  (t/testing "invalid arguments are errors"
    (t/is (= :functions/execution-error (resolve-hex "rgb(1, 2)")))
    (t/is (= :functions/execution-error (resolve-hex "rgb(a, b, c)")))
    (t/is (= :functions/execution-error (resolve-hex "rgb(10px, 0, 0)")))))

(t/deftest hsl-test
  (t/testing "CSS syntaxes"
    (t/is (= "#ff0000" (resolve-hex "hsl(0, 100%, 50%)")))
    (t/is (= "#ff0000" (resolve-hex "hsl(0 100% 50%)")))
    (t/is (= "#00ff00" (resolve-hex "hsl(120deg, 100%, 50%)")))
    (t/is (= "#00ff00" (resolve-hex "hsl(120deg 100% 50%)")))
    (t/is (= "#40bf40" (resolve-hex "hsl({hue}, 50%, 50%)"))))
  (t/testing "the hue wraps around"
    (t/is (= "#0000ff" (resolve-hex "hsl(-120, 100%, 50%)")))
    (t/is (= "#00ff00" (resolve-hex "hsl(480, 100%, 50%)"))))
  (t/testing "plain numbers are fractions, or CSS percentages when above 1"
    (t/is (= "#ff0000" (resolve-hex "hsl(0, 1, 0.5)")))
    (t/is (= "#0000ff" (resolve-hex "hsl(240, 1, 0.5)")))
    (t/is (= "#ff0000" (resolve-hex "hsl(0, 100, 50)")))
    (t/is (= "#40bf40" (resolve-hex "hsl(120, 50, 50)")))
    (t/is (= "#ff0000" (resolve-hex "hsl(0, 100%, 0.5)"))))
  (t/testing "alpha"
    (t/is (= "#ff000080" (resolve-hex "hsla(0, 100%, 50%, 0.5)")))
    (t/is (= "#ff000080" (resolve-hex "hsla(0, 100%, 50%, 50%)")))
    (t/is (= "#ff000080" (resolve-hex "hsla(0 100% 50% 50%)")))
    (t/is (= "#ff000080" (resolve-hex "hsla({color}, 0.5)"))))
  (t/testing "out of range saturation is clamped"
    (t/is (= "#ff0000" (resolve-hex "hsl(0, 150%, 50%)"))))
  (t/testing "invalid arguments are errors"
    (t/is (= :functions/execution-error (resolve-hex "hsl(0, 100%)")))
    (t/is (= :functions/execution-error (resolve-hex "hsl(a, b, c)")))))

(t/deftest color-methods-still-convert-test
  (t/testing "the functions shadow the rgb/hsl initializers, not the conversions"
    (let [opts {:config (h/full-config)}]
      (t/is (= "#ff0000" (h/ev "return #ff0000.to.rgb().to.hex();" nil opts)))
      (t/is (= "#00ff00" (h/ev "return hsl(120, 1, 0.5).to.hex();" nil opts)))
      (t/is (= "#00ff00" (h/ev "variable c: Color.HSL = hsl(120deg, 100%, 50%); return c.to.hex();" nil opts))))))

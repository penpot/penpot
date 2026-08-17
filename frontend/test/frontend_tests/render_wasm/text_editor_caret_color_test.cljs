;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.render-wasm.text-editor-caret-color-test
  "Unit tests for the text-editor caret color resolution.

   The caret matches the topmost solid fill of the text at the caret. For
   anything else (no fill, gradient, image fills, mixed selection) it falls back
   to an inverted caret (white painted with a Difference blend)."
  (:require
   [app.common.render-wasm.serializers.color :as sr-clr]
   [app.render-wasm.text-editor :as text-editor]
   [cljs.test :as t :include-macros true]))

(def ^:private white 0xffffffff)

(defn- solid [color opacity]
  {:fill-color color :fill-opacity opacity})

(defn- gradient [stops]
  {:fill-color-gradient {:type :linear :stops stops}})

(t/deftest resolve-caret-color-solid
  (t/testing "a single solid fill is matched, painted normally"
    (t/is (= {:color (sr-clr/hex->u32argb "#ff0000" 1) :invert? false}
             (text-editor/resolve-caret-color [(solid "#ff0000" 1)]))))

  (t/testing "the topmost (first, visible) solid fill wins over the ones below"
    (t/is (= {:color (sr-clr/hex->u32argb "#ff0000" 1) :invert? false}
             (text-editor/resolve-caret-color [(solid "#ff0000" 1) (solid "#00ff00" 1)]))))

  (t/testing "the solid fill opacity is preserved"
    (t/is (= {:color (sr-clr/hex->u32argb "#00ff00" 0.5) :invert? false}
             (text-editor/resolve-caret-color [{:fill-image {:id "x"}} (solid "#00ff00" 0.5)]))))

  (t/testing "a solid fill takes precedence over a gradient"
    (t/is (= {:color (sr-clr/hex->u32argb "#ff0000" 1) :invert? false}
             (text-editor/resolve-caret-color
              [(solid "#ff0000" 1)
               (gradient [{:color "#000000" :opacity 1 :offset 0}
                          {:color "#ffffff" :opacity 1 :offset 1}])])))))

(t/deftest resolve-caret-color-inverted
  (t/testing "a gradient falls back to the inverted caret"
    (t/is (= {:color white :invert? true}
             (text-editor/resolve-caret-color
              [(gradient [{:color "#ff0000" :opacity 1 :offset 0}
                          {:color "#0000ff" :opacity 1 :offset 1}])]))))

  (t/testing "an image-only fill falls back to the inverted caret"
    (t/is (= {:color white :invert? true}
             (text-editor/resolve-caret-color [{:fill-image {:id "x"}}]))))

  (t/testing "no fills fall back to the inverted caret"
    (t/is (= {:color white :invert? true}
             (text-editor/resolve-caret-color []))))

  (t/testing "nil falls back to the inverted caret"
    (t/is (= {:color white :invert? true}
             (text-editor/resolve-caret-color nil))))

  (t/testing "a mixed selection (:multiple) falls back to the inverted caret"
    (t/is (= {:color white :invert? true}
             (text-editor/resolve-caret-color :multiple)))))

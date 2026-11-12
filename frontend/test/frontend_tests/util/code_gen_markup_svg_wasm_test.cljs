;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.util.code-gen-markup-svg-wasm-test
  "Unit tests for WASM SVG markup helpers used by Code inspect and Copy as SVG."
  (:require
   [app.common.uuid :as uuid]
   [app.render-wasm.api :as wasm.api]
   [app.util.code-gen.markup-svg-wasm :as svg.wasm]
   [cljs.test :as t :include-macros true]))

(defn- utf8-bytes
  [s]
  (.encode (js/TextEncoder.) s))

(t/deftest get-svg-markup-empty-ids
  (t/is (= "" (svg.wasm/get-svg-markup []))))

(t/deftest get-svg-markup-not-initialized
  (with-redefs [wasm.api/initialized? (constantly false)]
    (t/is (= "" (svg.wasm/get-svg-markup [(uuid/next)])))))

(t/deftest get-svg-markup-single-shape
  (let [id     (uuid/next)
        called (atom nil)]
    (with-redefs [wasm.api/initialized? (constantly true)
                  wasm.api/render-shape-svg
                  (fn [shape-id scale]
                    (reset! called [shape-id scale])
                    (utf8-bytes "<svg single=\"1\"/>"))]
      (t/is (= "<svg single=\"1\"/>" (svg.wasm/get-svg-markup [id])))
      (t/is (= [id 1] @called)))))

(t/deftest get-svg-markup-multi-shapes-one-document
  (let [ids    [(uuid/next) (uuid/next)]
        called (atom nil)]
    (with-redefs [wasm.api/initialized? (constantly true)
                  wasm.api/render-shapes-svg
                  (fn [shape-ids scale]
                    (reset! called [shape-ids scale])
                    (utf8-bytes "<svg multi=\"1\"/>"))]
      (t/is (= "<svg multi=\"1\"/>" (svg.wasm/get-svg-markup ids)))
      (t/is (= [ids 1] @called))
      (t/is (= 1 (count (re-seq #"<svg\b" (svg.wasm/get-svg-markup ids))))))))

(defn- wasm-panic
  []
  (throw (ex-info "WASM Error (panic)"
                  {:type :wasm-error
                   :code :panic
                   :hint "WASM Error (panic)"
                   :fn "_render_shape_svg"})))

(t/deftest get-svg-markup-swallows-wasm-errors
  (with-redefs [wasm.api/initialized? (constantly true)
                wasm.api/render-shape-svg (fn [_ _] (wasm-panic))]
    (t/is (= "" (svg.wasm/get-svg-markup [(uuid/next)]))))
  (with-redefs [wasm.api/initialized? (constantly true)
                wasm.api/render-shapes-svg (fn [_ _] (wasm-panic))]
    (t/is (= "" (svg.wasm/get-svg-markup [(uuid/next) (uuid/next)])))))

(t/deftest get-formatted-svg-markup-safe-on-empty
  (with-redefs [wasm.api/initialized? (constantly false)]
    (t/is (= "" (svg.wasm/get-formatted-svg-markup [(uuid/next)])))))

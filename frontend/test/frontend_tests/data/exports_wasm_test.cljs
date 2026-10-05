;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.exports-wasm-test
  (:require
   [app.common.render-wasm.api.props :as props]
   [app.common.render-wasm.api.select :as wselect]
   [app.common.uuid :as uuid]
   [app.main.data.exports.wasm :as exports]
   [app.main.refs :as refs]
   [app.render-wasm.api :as wasm.api]
   [app.util.webapi :as wapi]
   [cljs.test :as t :include-macros true]))

(t/deftest export-hides-only-the-requested-root-fill-and-restores-it
  (doseq [export-uri [exports/export-image-uri exports/export-svg-uri exports/export-pdf-uri]]
    (let [id      (uuid/next)
          child   (uuid/next)
          fills   [{:fill-color "#ffffff" :fill-opacity 1}]
          child-fills [{:fill-color "#6c5ce7" :fill-opacity 1}]
          selected (atom child)
          current (atom {id fills child child-fills})
          visible (atom nil)
          render  (fn [& _]
                    (reset! visible @current)
                    (js/Uint8Array. #js [1 2 3]))]
      (with-redefs [refs/workspace-page-objects (atom {id {:id id :fills fills :hide-fill-on-export true}})
                    wselect/use-shape (fn [id] (reset! selected id))
                    wasm.api/initialized? (constantly true)
                    props/write-shape-fills! (fn [fills] (swap! current assoc @selected fills))
                    wasm.api/render-shape-pixels render
                    wasm.api/render-shape-svg render
                    wasm.api/render-shape-pdf render
                    wapi/create-uri (fn [_] "blob:preview")]
        (t/is (= "blob:preview" (export-uri {:object-id id :type :png :scale 1})))
        (t/is (= {id [] child child-fills} @visible))
        (t/is (= {id fills child child-fills} @current))))))

(t/deftest failed-export-restores-the-workspace-fill
  (let [id      (uuid/next)
        fills   [{:fill-color "#ffffff" :fill-opacity 1}]
        current (atom fills)]
    (with-redefs [refs/workspace-page-objects (atom {id {:id id :fills fills :hide-fill-on-export true}})
                  wselect/use-shape (fn [_] nil)
                  wasm.api/initialized? (constantly true)
                  props/write-shape-fills! (fn [fills] (reset! current fills))
                  wasm.api/render-shape-pixels (fn [& _] (throw (js/Error. "render failed")))]
      (t/is (thrown-with-msg? js/Error #"render failed"
                              (exports/export-image-uri {:object-id id :type :png :scale 1})))
      (t/is (= fills @current)))))

(t/deftest export-keeps-visible-root-fills
  (let [id      (uuid/next)
        fills   [{:fill-color "#ffffff" :fill-opacity 1}]
        current (atom fills)
        visible (atom nil)]
    (with-redefs [refs/workspace-page-objects (atom {id {:id id :fills fills :hide-fill-on-export false}})
                  wselect/use-shape (fn [_] nil)
                  wasm.api/initialized? (constantly true)
                  props/write-shape-fills! (fn [fills] (reset! current fills))
                  wasm.api/render-shape-pixels (fn [& _]
                                                 (reset! visible @current)
                                                 (js/Uint8Array. #js [1 2 3]))
                  wapi/create-uri (fn [_] "blob:preview")]
      (exports/export-image-uri {:object-id id :type :png :scale 1})
      (t/is (= fills @visible))
      (t/is (= fills @current)))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-wasm-text-failed-font-test
  "Auto-grow WASM resize must not save a size measured with a fallback font
  when a content face failed to load (issue #13392)."
  (:require
   [app.common.render-wasm.api.select :as wselect]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.api.fonts :as wasm.fonts]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private failed-face-key
  {:font-id "custom-missing" :weight 400 :style 0 :emoji? false})

(def ^:private content-fonts
  [{:font-id "custom-missing"
    :font-variant-id "regular"
    :font-weight 400
    :font-style "normal"}])

(defn- auto-width-text
  []
  {:id (uuid/next)
   :type :text
   :grow-type :auto-width
   :selrect {:x 0 :y 0 :width 100 :height 20}
   :content {:type "root"
             :children [{:type "paragraph-set"
                         :children [{:type "paragraph"
                                     :children [{:text "Button label"
                                                 :font-id "custom-missing"
                                                 :font-variant-id "regular"}]}]}]}})

(t/deftest ^:async measure-skips-size-when-a-content-face-failed
  (let [shape (auto-width-text)]
    (await
     (mock/with-mocks*
       {wasm.api/initialized?           (mock/stub (constantly true))
        wselect/use-shape               (mock/stub (constantly nil))
        wasm.api/set-shape-text-content (mock/stub (constantly nil))
        wasm.api/set-shape-text-images  (mock/stub (constantly nil))
        wasm.api/get-text-dimensions    (mock/stub (constantly {:width 200 :height 30}))
        wasm.fonts/get-content-fonts    (mock/stub (constantly content-fonts))
        wasm.fonts/make-font-data       (mock/stub (constantly failed-face-key))
        wasm.fonts/font-failed?         (mock/stub (constantly true))}
       (t/is (nil? (dwwt/get-wasm-text-new-size shape))
             "a size laid out with a fallback font is not measured for save")
       (t/is (nil? (dwwt/resize-wasm-text-modifiers shape))
             "no resize modifiers when the face failed")))))

(t/deftest ^:async measure-returns-size-when-faces-loaded
  (let [shape (auto-width-text)]
    (await
     (mock/with-mocks*
       {wasm.api/initialized?           (mock/stub (constantly true))
        wselect/use-shape               (mock/stub (constantly nil))
        wasm.api/set-shape-text-content (mock/stub (constantly nil))
        wasm.api/set-shape-text-images  (mock/stub (constantly nil))
        wasm.api/get-text-dimensions    (mock/stub (constantly {:width 200 :height 30}))
        wasm.fonts/get-content-fonts    (mock/stub (constantly content-fonts))
        wasm.fonts/make-font-data       (mock/stub (constantly failed-face-key))
        wasm.fonts/font-failed?         (mock/stub (constantly false))}
       (t/is (= {:width 200 :height 30}
                (dwwt/get-wasm-text-new-size shape))
             "a loaded face still yields the measured auto-grow size")))))

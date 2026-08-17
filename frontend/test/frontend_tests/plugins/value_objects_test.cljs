;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

;; Value-object proxies (shadows, exports, grid tracks) returned by the Plugin
;; API. `format-shadows`, `format-exports` and `format-tracks` hand back live
;; proxies whose member setters persist back to the located shape. Each test
;; mutates a member on the returned proxy and reads the value back through a
;; fresh proxy from the live store, so a detached-snapshot regression fails.
(ns frontend-tests.plugins.value-objects-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [potok.v2.core :as ptk]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(defn- setup-context []
  (let [store   (ths/setup-store (cthf/sample-file :file1 :page-label :page1))
        _       (set! st/state store)
        _       (set! st/stream (ptk/input-stream store))
        context (api/create-context plugin-id)]
    {:store store :context context}))

;; ---------------------------------------------------------------------------
;; Shadows
;; ---------------------------------------------------------------------------

(t/deftest shadow-proxy-member-setters-persist-to-the-shape
  (thw/with-wasm-mocks*
    (fn []
      (let [{:keys [context]} (setup-context)
            ^js rect          (.createRectangle ^js context)]
        (set! (.-shadows rect)
              #js [#js {:style "drop-shadow"
                        :offsetX 1 :offsetY 1 :blur 2 :spread 0 :hidden false
                        :color #js {:color "#000000" :opacity 1}}])
        (let [^js shadow (aget (.-shadows rect) 0)]
          (set! (.-style shadow) "inner-shadow")
          (set! (.-offsetX shadow) 5)
          (set! (.-offsetY shadow) 6)
          (set! (.-blur shadow) 7)
          (set! (.-spread shadow) 2)
          (set! (.-hidden shadow) true))
        (let [^js persisted (aget (.-shadows rect) 0)]
          (t/is (= "inner-shadow" (.-style persisted)))
          (t/is (= 5 (.-offsetX persisted)))
          (t/is (= 6 (.-offsetY persisted)))
          (t/is (= 7 (.-blur persisted)))
          (t/is (= 2 (.-spread persisted)))
          (t/is (= true (.-hidden persisted))))))))

;; ---------------------------------------------------------------------------
;; Exports
;; ---------------------------------------------------------------------------

(t/deftest export-proxy-member-setters-persist-to-the-shape
  (thw/with-wasm-mocks*
    (fn []
      (let [{:keys [context]} (setup-context)
            ^js rect          (.createRectangle ^js context)]
        (set! (.-exports rect)
              #js [#js {:type "png" :scale 1 :suffix "" :skipChildren false}])
        (let [^js export (aget (.-exports rect) 0)]
          (set! (.-type export) "jpeg")
          (set! (.-scale export) 2)
          (set! (.-suffix export) "@2x")
          (set! (.-skipChildren export) true))
        (let [^js persisted (aget (.-exports rect) 0)]
          (t/is (= "jpeg" (.-type persisted)))
          (t/is (= 2 (.-scale persisted)))
          (t/is (= "@2x" (.-suffix persisted)))
          (t/is (= true (.-skipChildren persisted))))))))

;; ---------------------------------------------------------------------------
;; Grid tracks
;; ---------------------------------------------------------------------------

(defn- setup-grid []
  (let [{:keys [store context]} (setup-context)
        ^js board (.createBoard ^js context)
        ^js grid  (.addGridLayout board)]
    {:store store :context context :board board :grid grid}))

(t/deftest track-proxy-member-setters-update-the-track
  (thw/with-wasm-mocks*
    (fn []
      (let [{:keys [^js grid]} (setup-grid)]
        (.addColumn grid "flex" 1)
        (let [^js track (aget (.-columns grid) 0)]
          (set! (.-type track) "fixed")
          (set! (.-value track) 120))
        (let [^js persisted (aget (.-columns grid) 0)]
          (t/is (= "fixed" (.-type persisted)))
          (t/is (= 120 (.-value persisted))))))))

(t/deftest track-proxy-member-setters-reject-invalid-input
  (thw/with-wasm-mocks*
    (fn []
      (let [{:keys [store ^js grid]} (setup-grid)]
        (swap! store assoc-in [:plugins :flags plugin-id :throw-validation-errors] true)
        (.addColumn grid "flex" 1)
        (let [^js track (aget (.-columns grid) 0)]
          (t/is (thrown? js/Error (set! (.-type track) "not-a-track-type")))
          (t/is (thrown? js/Error (set! (.-value track) "not-a-number"))))))))

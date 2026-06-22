;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.shape-bugfixes-test
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.files :as cthf]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.variants :as dwv]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.plugins.public-utils :as public-utils]
   [app.plugins.shape :as shape]
   [app.plugins.utils :as u]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- child-shapes
  "Ordered child shape ids of `board`, read back from the live store
   (the observable result of a z-order operation)."
  [store ^js context ^js board]
  (let [file-id  (aget (. context -currentFile) "$id")
        page-id  (aget (. context -currentPage) "$id")
        board-id (aget board "$id")]
    (get-in @store [:files file-id :data :pages-index page-id
                    :objects board-id :shapes])))

(defn- page-guides
  "The guides map of the current page, read back from the live store."
  [store ^js context]
  (let [file-id (aget (. context -currentFile) "$id")
        page-id (aget (. context -currentPage) "$id")]
    (get-in @store [:files file-id :data :pages-index page-id :guides])))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(t/deftest trigger-setter-updates-the-interaction-event-type
  ;; Regression: the `trigger` setter must update the interaction of the
  ;; located shape. Asserting on the observable interaction (read back through
  ;; the proxy from the live store) covers that without coupling to which
  ;; internal action gets emitted.
  (thw/with-wasm-mocks*
    (fn []
      (let [store       (ths/setup-store (cthf/sample-file :file1 :page-label :page1))
            ^js context (api/create-context plugin-id)
            _           (set! st/state store)
            ^js board   (.createBoard context)]
        (.addInteraction board "click" #js {:type "open-url" :url "https://example.com"})
        (let [^js interaction (aget (.-interactions board) 0)]
          (t/is (= "click" (.-trigger interaction))
                "the interaction starts with the click trigger")
          (set! (.-trigger interaction) "mouse-over")
          (t/is (= "mouse-over" (.-trigger interaction))
                "the trigger setter updates the interaction event-type"))))))

(t/deftest center-shapes-empty-input-returns-nil
  (t/is (nil? (public-utils/centerShapes #js []))))

(t/deftest background-blur-reads-background-blur-key
  (let [file-id  (uuid/next)
        page-id  (uuid/next)
        shape-id (uuid/next)
        blur-id  (uuid/next)
        proxy    (shape/shape-proxy plugin-id file-id page-id shape-id)]
    (with-redefs [u/proxy->shape (constantly {:background-blur {:id blur-id
                                                                :value 12
                                                                :hidden false}})]
      (let [blur (.-backgroundBlur proxy)]
        (t/is (= (str blur-id) (aget blur "id")))
        (t/is (= 12 (aget blur "value")))))))

(t/deftest flatten-returns-proxies-for-converted-shapes
  ;; `convert-selected-to-path` runs the WASM boolean/path pipeline, so this
  ;; test stays at the proxy boundary: it verifies `flatten` forwards the
  ;; selected ids to the conversion and wraps the result back into proxies.
  (let [file-id  (uuid/next)
        page-id  (uuid/next)
        shape-id (uuid/next)
        input    (shape/shape-proxy plugin-id file-id page-id shape-id)
        emitted  (atom nil)
        context  (api/create-context plugin-id)]
    (set! st/state (atom {:current-file-id file-id
                          :current-page-id page-id}))
    (with-redefs [dw/convert-selected-to-path
                  (mock/stub (fn [ids]
                               (reset! emitted ids)
                               :convert-selected-to-path))
                  st/emit! mock/noop
                  shape/shape-proxy
                  (mock/stub (fn [_plugin file page id]
                               #js {"$file" file "$page" page "$id" id}))]
      (let [result (.flatten context #js [input])]
        (t/is (= #{shape-id} @emitted))
        (t/is (array? result))
        (t/is (= shape-id (aget result 0 "$id")))
        (t/is (= file-id (aget result 0 "$file")))
        (t/is (= page-id (aget result 0 "$page")))))))

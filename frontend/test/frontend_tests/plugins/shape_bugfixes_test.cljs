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

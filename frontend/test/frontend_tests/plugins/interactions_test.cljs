;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.interactions-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [potok.v2.core :as ptk]))

(defn- flows-of
  "The vals of the current page flows from the store."
  [store ^js context ^js page]
  (let [file-id (aget (. context -currentFile) "$id")
        page-id (aget page "$id")]
    (vals (get-in @store [:files file-id :data :pages-index page-id :flows]))))

(t/deftest add-interaction-creates-implicit-flow
  (thw/with-wasm-mocks*
    (fn []
      (let [store       (ths/setup-store (cthf/sample-file :file1 :page-label :page1))
            ^js context (api/create-context "00000000-0000-0000-0000-000000000000")
            _           (set! st/state store)
            ^js page    (. context -currentPage)
            ^js board1  (.createBoard context)
            ^js board2  (.createBoard context)]

        (t/testing "a page has no flows before any interaction is added"
          (t/is (empty? (flows-of store context page))))

        (t/testing "addInteraction outside a flow creates an implicit flow"
          (.addInteraction board1 "click" #js {:type "navigate-to" :destination board2})
          (let [flows (flows-of store context page)]
            (t/is (= 1 (count flows))
                  "an implicit flow is created for the origin board")
            (t/is (= (aget board1 "$id") (:starting-frame (first flows)))
                  "the implicit flow starts at the origin board")))

        (t/testing "adding another interaction from the same board does not duplicate the flow"
          (.addInteraction board1 "click" #js {:type "navigate-to" :destination board2})
          (t/is (= 1 (count (flows-of store context page)))
                "no duplicate flow is created"))))))

(t/deftest add-interaction-does-not-duplicate-explicit-flow
  (thw/with-wasm-mocks*
    (fn []
      (let [store       (ths/setup-store (cthf/sample-file :file1 :page-label :page1))
            ^js context (api/create-context "00000000-0000-0000-0000-000000000000")
            _           (set! st/state store)
            ^js page    (. context -currentPage)
            ^js board1  (.createBoard context)
            ^js board2  (.createBoard context)]

        ;; board1 is already the starting frame of an explicitly created flow
        (.createFlow page "My flow" board1)

        (t/testing "addInteraction from a board already in a flow keeps a single flow"
          (.addInteraction board1 "click" #js {:type "navigate-to" :destination board2})
          (let [flows (flows-of store context page)]
            (t/is (= 1 (count flows))
                  "no duplicate flow is created alongside the explicit one")
            (t/is (= "My flow" (:name (first flows)))
                  "the explicit flow is preserved")))))))

(t/deftest add-interaction-without-destination-does-not-create-flow
  (thw/with-wasm-mocks*
    (fn []
      (let [store       (ths/setup-store (cthf/sample-file :file1 :page-label :page1))
            ^js context (api/create-context "00000000-0000-0000-0000-000000000000")
            _           (set! st/state store)
            ^js page    (. context -currentPage)
            ^js board1  (.createBoard context)]

        (t/testing "a non flow-origin interaction (open-url) creates no flow"
          (.addInteraction board1 "click" #js {:type "open-url" :url "https://example.com"})
          (t/is (empty? (flows-of store context page))
                "open-url interactions do not create a flow"))))))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(defn- throws?
  [thunk]
  (try (thunk) false (catch :default _ true)))

(t/deftest interaction-delay-accepts-zero
  ;; Regression: the InteractionProxy `:delay` setter rejected 0 via
  ;; `(not (pos? value))`, but the model (`set-delay` -> `check-safe-int`) allows
  ;; 0 (an immediate after-delay interaction). With `throwValidationErrors`
  ;; enabled, setting 0 must NOT throw (its validation guard passes), while a
  ;; negative value must still be rejected.
  (thw/with-wasm-mocks*
    (fn []
      (let [store       (ths/setup-store (cthf/sample-file :file1 :page-label :page1))
            ^js context (api/create-context plugin-id)
            _           (set! st/state store)
            ^js board1  (.createBoard context)
            ^js board2  (.createBoard context)
            ^js inter   (.addInteraction board1 "after-delay" #js {:type "navigate-to" :destination board2} 300)]
        (ptk/emit! store #(assoc-in % [:plugins :flags plugin-id :throw-validation-errors] true))
        (t/is (not (throws? #(set! (.-delay inter) 0))) "delay = 0 must be accepted")
        (t/is (throws? #(set! (.-delay inter) -1)) "negative delay must be rejected")))))

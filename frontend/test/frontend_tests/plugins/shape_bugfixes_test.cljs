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

(t/deftest z-order-methods-reorder-the-shape-within-its-parent
  ;; Asserts the observable child order in the parent after each z-order
  ;; method, instead of merely checking which location keyword was emitted.
  ;; The assertions are independent of the parent's `:shapes` ordering
  ;; convention: a reorder is verified by relative movement and extremes.
  (thw/with-wasm-mocks*
    (fn []
      (let [store       (ths/setup-store (cthf/sample-file :file1 :page-label :page1))
            ^js context (api/create-context plugin-id)
            _           (set! st/state store)
            ^js board   (.createBoard context)
            children    (mapv (fn [_] (.createRectangle context)) (range 4))
            ids         (mapv #(aget % "$id") children)
            order       #(child-shapes store context board)]
        (doseq [^js c children] (.appendChild board c))

        ;; Operate on a shape that is currently interior (so both a forward
        ;; and a backward step are observable).
        (let [mid-id  (nth (order) 1)
              ^js mid (nth children (d/index-of ids mid-id))]

          (t/testing "bringForward and sendBackward move in opposite directions"
            (let [i0 (d/index-of (order) mid-id)
                  _  (.bringForward mid)
                  i1 (d/index-of (order) mid-id)
                  _  (.sendBackward mid)
                  i2 (d/index-of (order) mid-id)]
              (t/is (not= i0 i1) "bringForward changes the order")
              (t/is (not= i1 i2) "sendBackward changes the order")
              (t/is (= (pos? (- i1 i0)) (neg? (- i2 i1)))
                    "the two steps move the shape in opposite directions")))

          (t/testing "bringToFront and sendToBack move to opposite extremes"
            (let [n  (count (order))
                  _  (.bringToFront mid)
                  p1 (d/index-of (order) mid-id)
                  _  (.sendToBack mid)
                  p2 (d/index-of (order) mid-id)]
              (t/is (contains? #{0 (dec n)} p1) "bringToFront moves to an extreme")
              (t/is (contains? #{0 (dec n)} p2) "sendToBack moves to an extreme")
              (t/is (not= p1 p2) "front and back are opposite extremes"))))))))

(t/deftest is-variant-container-predicate-returns-boolean
  (t/is (false? (ctk/is-variant-container? {})))
  (t/is (true? (ctk/is-variant-container? {:is-variant-container true}))))

(t/deftest combine-as-variants-uses-the-passed-component-ids
  ;; `combine-as-variants` needs real main components and the variant pipeline,
  ;; so this stays at the proxy boundary and verifies the component ids that
  ;; the head proxy collects from its argument before delegating.
  (let [file-id  (uuid/next)
        page-id  (uuid/next)
        head-id  (uuid/next)
        other-id (uuid/next)
        proxy    (shape/shape-proxy plugin-id file-id page-id head-id)
        captured (atom nil)]
    (with-redefs [u/locate-shape (fn [_file _page id] {:id id :component-id id})
                  u/locate-library-component (constantly {:id (uuid/next)})
                  ctk/is-variant? (constantly false)
                  dwv/combine-as-variants
                  (fn [ids opts]
                    (reset! captured {:ids ids :opts opts})
                    ;; return value flows through `se/add-event` (which
                    ;; calls `with-meta`), so it must support metadata
                    {:event :combine-as-variants})
                  st/emit! mock/noop
                  shape/shape-proxy (mock/stub (fn [& _] #js {}))]
      (.combineAsVariants proxy #js [(str other-id)])
      (t/is (= #{head-id other-id} (:ids @captured))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.shape-bugfixes-test
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.variants :as cthv]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.variants :as dwv]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.plugins.public-utils :as public-utils]
   [app.plugins.register :as r]
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
  (let [file-id   (uuid/next)
        page-id   (uuid/next)
        head-id   (uuid/next)
        other-id  (uuid/next)
        third-id  (uuid/next)
        proxy     (shape/shape-proxy plugin-id file-id page-id head-id)
        captured  (atom nil)]
    (with-redefs [u/locate-shape (fn [_file _page id] {:id id :component-id id})
                  u/locate-library-component (constantly {:id (uuid/next)})
                  ctk/is-variant? (constantly false)
                  dwv/valid-components-for-variants? (constantly true)
                  dwv/combine-as-variants
                  (fn [ids opts]
                    (reset! captured {:ids ids :opts opts})
                    ;; return value flows through `se/add-event` (which
                    ;; calls `with-meta`), so it must support metadata
                    {:event :combine-as-variants})
                  st/emit! mock/noop
                  shape/shape-proxy (mock/stub (fn [& _] #js {}))]
      ;; the collected ids must be ordered (head shape first, then the passed
      ;; ids in their given order, deduplicated), since the order determines
      ;; the order of the resulting variant components
      (.combineAsVariants proxy #js [(str other-id) (str head-id) (str third-id) (str other-id)])
      (t/is (= [head-id other-id third-id] (:ids @captured))))))

(t/deftest remove-ruler-guide-deletes-the-guide-from-the-page
  ;; Adds a real ruler guide through the API and asserts it is gone from the
  ;; page guides after removeRulerGuide, rather than checking the removal call.
  (thw/with-wasm-mocks*
    (fn []
      (let [store       (ths/setup-store (cthf/sample-file :file1 :page-label :page1))
            ^js context (api/create-context plugin-id)
            _           (set! st/state store)
            ^js board   (.createBoard context)
            ^js guide   (.addRulerGuide board "horizontal" 10)]
        (t/is (= 1 (count (page-guides store context)))
              "addRulerGuide stores one guide on the page")
        (.removeRulerGuide board guide)
        (t/is (empty? (page-guides store context))
              "removeRulerGuide deletes the guide from the page")))))

(t/deftest group-empty-input-returns-nil
  (let [context (api/create-context plugin-id)]
    (t/is (nil? (.group context #js [])))))

;; ---------------------------------------------------------------------------
;; Permission checks (T9-F-04)
;; ---------------------------------------------------------------------------

(t/deftest commit-fills-text-shape-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        shape-id  (uuid/next)
        errors    (atom [])]
    (with-redefs [u/proxy->shape (constantly {:id shape-id :type :text})
                  u/not-valid    (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  r/check-permission (constantly false)
                  st/emit!       mock/noop]
      (let [proxy (shape/shape-proxy plugin-id file-id page-id shape-id)]
        (set! (.-fills proxy) #js [#js {:fillColor "#ff0000" :fillOpacity 1}])
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :fills "Plugin doesn't have 'content:write' permission"]
                 (first @errors)))))))

(t/deftest interaction-trigger-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        shape-id  (uuid/next)
        errors    (atom [])]
    (with-redefs [u/not-valid (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  r/check-permission (constantly false)
                  st/emit!    mock/noop]
      (let [inter (shape/interaction-proxy plugin-id file-id page-id shape-id 0)]
        (set! (.-trigger inter) "click")
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :trigger "Plugin doesn't have 'content:write' permission"]
                 (first @errors)))))))

(t/deftest interaction-delay-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        shape-id  (uuid/next)
        errors    (atom [])]
    (with-redefs [u/not-valid (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  r/check-permission (constantly false)
                  st/emit!    mock/noop]
      (let [inter (shape/interaction-proxy plugin-id file-id page-id shape-id 0)]
        (set! (.-delay inter) 100)
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :delay "Plugin doesn't have 'content:write' permission"]
                 (first @errors)))))))

(t/deftest interaction-action-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        shape-id  (uuid/next)
        errors    (atom [])]
    (with-redefs [u/proxy->interaction (constantly {:event-type :click :delay 0 :action-type :open-url :url "https://example.com"})
                  u/not-valid (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  r/check-permission (constantly false)
                  st/emit!    mock/noop]
      (let [inter (shape/interaction-proxy plugin-id file-id page-id shape-id 0)]
        (set! (.-action inter) #js {:type "open-url" :url "https://example.com"})
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :action "Plugin doesn't have 'content:write' permission"]
                 (first @errors)))))))

(t/deftest interaction-remove-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        shape-id  (uuid/next)
        errors    (atom [])]
    (with-redefs [u/not-valid (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  r/check-permission (constantly false)
                  st/emit!    mock/noop]
      (let [inter (shape/interaction-proxy plugin-id file-id page-id shape-id 0)]
        (.remove inter)
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :remove "Plugin doesn't have 'content:write' permission"]
                 (first @errors)))))))

(t/deftest add-interaction-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        shape-id  (uuid/next)
        errors    (atom [])]
    (with-redefs [u/locate-shape (constantly {:id shape-id})
                  u/not-valid    (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  r/check-permission (constantly false)
                  st/emit!       mock/noop]
      (let [proxy (shape/shape-proxy plugin-id file-id page-id shape-id)]
        (.addInteraction proxy "click" #js {:type "open-url" :url "https://example.com"})
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :addInteraction "Plugin doesn't have 'content:write' permission"]
                 (first @errors)))))))

(t/deftest remove-interaction-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        shape-id  (uuid/next)
        errors    (atom [])]
    (with-redefs [u/not-valid (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  r/check-permission (constantly false)
                  st/emit!    mock/noop]
      (let [proxy (shape/shape-proxy plugin-id file-id page-id shape-id)
            inter (shape/interaction-proxy plugin-id file-id page-id shape-id 0)]
        (.removeInteraction proxy inter)
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :removeInteraction "Plugin doesn't have 'content:write' permission"]
                 (first @errors)))))))

(t/deftest detach-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        shape-id  (uuid/next)
        errors    (atom [])]
    (with-redefs [u/page-active? (constantly true)
                  u/not-valid    (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  r/check-permission (constantly false)
                  st/emit!       mock/noop]
      (let [proxy (shape/shape-proxy plugin-id file-id page-id shape-id)]
        (.detach proxy)
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :detach "Plugin doesn't have 'content:write' permission"]
                 (first @errors)))))))

(t/deftest export-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        shape-id  (uuid/next)
        errors    (atom [])]
    (with-redefs [u/not-valid (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  r/check-permission (constantly false)]
      (let [proxy (shape/shape-proxy plugin-id file-id page-id shape-id)]
        (.export proxy #js {:type "png" :scale 1})
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :export "Plugin doesn't have 'content:read' permission"]
                 (first @errors)))))))

(t/deftest apply-token-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        shape-id  (uuid/next)
        set-id    (uuid/next)
        token-id  (uuid/next)
        errors    (atom [])]
    (with-redefs [u/locate-token (constantly {:id token-id :name "test" :type :color})
                  shape/token-proxy? (constantly true)
                  u/not-valid    (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  r/check-permission (constantly false)
                  st/emit!       mock/noop]
      (let [proxy (shape/shape-proxy plugin-id file-id page-id shape-id)
            token #js {"$set-id" (str set-id) "$id" (str token-id)}]
        (.applyToken proxy token #js [])
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :applyToken "Plugin doesn't have 'content:write' permission"]
                 (first @errors)))))))

(t/deftest switch-variant-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        shape-id  (uuid/next)
        errors    (atom [])]
    (with-redefs [u/locate-shape (constantly {:id shape-id :component-id shape-id})
                  u/locate-library-component (constantly {:id (uuid/next)})
                  u/page-active? (constantly true)
                  u/not-valid    (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  r/check-permission (constantly false)
                  st/emit!       mock/noop]
      (let [proxy (shape/shape-proxy plugin-id file-id page-id shape-id)]
        (.switchVariant proxy 0 "value")
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :switchVariant "Plugin doesn't have 'content:write' permission"]
                 (first @errors)))))))

(t/deftest combine-as-variants-checks-permission
  (let [plugin-id "test-plugin"
        file-id   (uuid/next)
        page-id   (uuid/next)
        shape-id  (uuid/next)
        other-id  (uuid/next)
        errors    (atom [])]
    (with-redefs [u/locate-shape (fn [_file _page id] {:id id :component-id id})
                  u/locate-library-component (constantly {:id (uuid/next)})
                  ctk/is-variant? (constantly false)
                  u/not-valid    (mock/stub (fn [pid prop msg] (swap! errors conj [pid prop msg])))
                  r/check-permission (constantly false)
                  st/emit!       mock/noop]
      (let [proxy (shape/shape-proxy plugin-id file-id page-id shape-id)]
        (.combineAsVariants proxy #js [(str other-id)])
        (t/is (= 1 (count @errors)))
        (t/is (= [plugin-id :combineAsVariants "Plugin doesn't have 'content:write' permission"]
                 (first @errors)))))))

(defn- plugin-errors-of
  "The errors the plugin reports when `call` runs on the proxy of the shape
   `shape-id` of `objects`, with every check passing but the shape's own."
  [objects shape-id call]
  (let [file-id (uuid/next)
        page-id (uuid/next)
        errors  (atom [])
        emitted (atom [])]
    (with-redefs [u/locate-shape      (fn [_ _ id] (get objects id))
                  u/locate-objects    (fn ([] objects) ([_ _] objects))
                  u/page-active?      (constantly true)
                  r/check-permission  (constantly true)
                  u/not-valid         (mock/stub (fn [_ prop msg] (swap! errors conj [prop msg])))
                  st/emit!            (mock/stub (fn [& events] (swap! emitted into events)))]
      (call (shape/shape-proxy plugin-id file-id page-id shape-id)))
    {:errors @errors :emitted @emitted}))

(defn- nested-copy-objects
  "A copy root holding a nested copy head, which holds a child."
  []
  (let [root  (uuid/next)
        head  (uuid/next)
        child (uuid/next)]
    {:root  root
     :head  head
     :child child
     :objects {root  {:id root :type :frame :parent-id uuid/zero :shapes [head]
                      :component-id (uuid/next) :component-file (uuid/next)
                      :component-root true :shape-ref (uuid/next)}
               head  {:id head :type :frame :parent-id root :shapes [child]
                      :component-id (uuid/next) :component-file (uuid/next)
                      :shape-ref (uuid/next)}
               child {:id child :type :rect :parent-id head :shape-ref (uuid/next)}}}))

(t/deftest swap-component-rejects-a-shape-that-is-no-copy-head
  (let [{:keys [objects child]} (nested-copy-objects)
        {:keys [errors emitted]} (plugin-errors-of objects child #(.swapComponent ^js % #js {}))]
    (t/is (= [[:swapComponent "The shape is not a component copy head"]] errors))
    (t/is (empty? emitted))))

(t/deftest reset-overrides-rejects-a-shape-that-is-no-copy-head
  (let [{:keys [objects child]} (nested-copy-objects)
        {:keys [errors emitted]} (plugin-errors-of objects child #(.resetOverrides ^js %))]
    (t/is (= [[:resetOverrides "The shape is not a component copy head"]] errors))
    (t/is (empty? emitted))))

(t/deftest detach-rejects-a-copy-nested-in-a-copy
  (let [{:keys [objects head]} (nested-copy-objects)
        {:keys [errors emitted]} (plugin-errors-of objects head #(.detach ^js %))]
    (t/is (= [[:detach "Only a copy that is not inside another copy can be detached"]] errors))
    (t/is (empty? emitted))))

(t/deftest swap-component-keeps-overrides-only-within-a-variant-set
  (let [set-a   (uuid/next)
        set-b   (uuid/next)
        variant #(hash-map :id (uuid/next) :variant-id %)]
    (t/is (true? (shape/swap-keeps-touched? (variant set-a) (variant set-a))))
    (t/is (false? (shape/swap-keeps-touched? (variant set-a) (variant set-b))))
    (t/is (not (shape/swap-keeps-touched? {:id (uuid/next)} {:id (uuid/next)})))
    (t/is (not (shape/swap-keeps-touched? (variant set-a) {:id (uuid/next)})))))

(t/deftest is-variant-head-reads-the-component-file-of-the-shape
  ;; a copy, in this file, of a variant of a library
  (let [library-id (uuid/next)
        copy-id    (uuid/next)
        rect-id    (uuid/next)
        objects    {copy-id {:id copy-id :type :frame :component-id (uuid/next)
                             :component-file library-id :component-root true :shape-ref (uuid/next)}
                    rect-id {:id rect-id :type :rect}}
        head?      (fn [id]
                     (with-redefs [u/locate-shape (fn [_ _ id] (get objects id))
                                   u/locate-library-component
                                   (fn [file-id _] (when (= file-id library-id) {:variant-id (uuid/next)}))]
                       (.isVariantHead ^js (shape/shape-proxy "test-plugin" (uuid/next) (uuid/next) id))))]
    (t/is (true? (head? copy-id)))
    (t/is (false? (head? rect-id)))))

(t/deftest swap-component-rejects-a-value-that-is-no-component
  (let [{:keys [objects head]} (nested-copy-objects)
        {:keys [errors emitted]} (plugin-errors-of objects head #(.swapComponent ^js % #js {}))]
    (t/is (= [[:swapComponent "Component not valid"]] errors))
    (t/is (empty? emitted))))

(t/deftest switch-variant-into-a-nesting-loop-reports-it
  (t/async
    done
    (let [file   (-> (cthf/sample-file :file1)
                     (cthv/add-variant :v01 :c01 :m01 :c02 :m02)
                     (cthc/instantiate-component :c01 :nested :parent-label :m02))
          store  (ths/setup-store file)
          errors (atom [])]
      (set! st/state store)
      (mock/with-mocks
        {u/not-valid        (fn [_ prop msg] (swap! errors conj [prop msg]))
         r/check-permission (constantly true)
         u/page-active?     (constantly true)}
        (fn [done']
          (.switchVariant (shape/shape-proxy plugin-id (:id file) (cthf/current-page-id file)
                                             (cthi/id :nested))
                          0 "Value2")
          (js/setTimeout
           (fn []
             (t/is (= [[:switchVariant "The switch would create a component nesting loop"]] @errors))
             (done'))
           50))
        done))))

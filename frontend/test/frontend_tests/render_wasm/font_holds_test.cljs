;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.font-holds-test
  "A page opens at once while the fonts of its texts load. Only the texts
  waiting for a font stay off the canvas, each until its own faces are
  stored or have failed, and the fonts start loading with the shapes that
  use them."
  (:require
   [app.common.render-wasm.serialize-shape :as serialize-shape]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.uuid :as uuid]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.api.fonts :as wasm.fonts]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]))

(defn- text-shape
  []
  {:id (uuid/next) :type :text :parent-id uuid/zero :grow-type :fixed})

(defn- page-objects
  [& shapes]
  (into {uuid/zero {:id uuid/zero
                    :type :frame
                    :parent-id uuid/zero
                    :shapes (mapv :id shapes)}}
        (map (juxt :id identity))
        shapes))

(defn- quartet->uuid
  [a b c d]
  (uuid/from-unsigned-parts a b c d))

(defn- host-attrs-with
  "Stands in for the per-shape upload: `faces` maps a shape id to the faces
  it still waits on and `fonts` to the font fetches it starts."
  [faces fonts]
  (fn [shape & _]
    (let [id (:id shape)]
      {:fonts (get fonts id [])
       :thumbnails []
       :full []
       :font-face-keys (get faces id #{})
       :pending-font-face-keys (get faces id #{})})))

(defn- run-with-wasm
  "Runs `f` with the WASM boundaries mocked. `calls` collects each
  `[:awaiting id awaiting?]` and `[:relayout id]` sent to WASM, and
  `pending` each call to `process-pending`."
  [{:keys [faces fonts calls pending]} f]
  (mock/with-mocks*
    {wasm/live?                             (mock/stub (constantly true))
     wasm/internal-module                   #js {"_set_view_end"   (fn [& _] nil)
                                                 "_begin_loading"  (fn [& _] nil)
                                                 "_end_loading"    (fn [& _] nil)
                                                 "_update_shape_text_layout_for"
                                                 (fn [& _] nil)
                                                 "_force_update_shape_text_layout_for"
                                                 (fn [a b c d]
                                                   (swap! calls conj [:relayout (quartet->uuid a b c d)])
                                                   nil)
                                                 "_set_shape_awaiting_fonts"
                                                 (fn [a b c d awaiting?]
                                                   (swap! calls conj [:awaiting (quartet->uuid a b c d) awaiting?])
                                                   nil)}
     serialize-shape/serialize-shapes-batch (mock/stub (fn [shapes _] shapes))
     ;; Private: the per-shape upload that reports the faces of a text.
     #_{:clj-kondo/ignore [:private-call]}
     wasm.api/set-object-host-attrs         (host-attrs-with faces fonts)
     wasm.api/process-pending               (mock/stub (fn [& args] (swap! pending conj args) nil))
     wasm.api/request-render                (mock/stub (constantly nil))
     wasm.api/get-text-dimensions           (mock/stub (constantly nil))}
    (await (f))))

(defn- clear-holds
  []
  (@#'wasm.api/clear-font-holds))

(defn- awaiting-calls
  [calls]
  (into [] (filter #(= :awaiting (first %))) @calls))

(t/deftest ^:async a-page-shows-at-once-while-a-text-waits-for-its-font
  (let [text    (text-shape)
        calls   (atom [])
        pending (atom [])
        ready   (atom false)]
    (await (run-with-wasm
            {:faces {(:id text) #{"face-a"}} :calls calls :pending pending}
            #(do (wasm.api/set-objects (page-objects text) nil (fn [] (reset! ready true)) true)
                 (t/is (true? @ready) "the page is revealed without waiting for the font")
                 (t/is (= [[:awaiting (:id text) true]] (awaiting-calls calls))
                       "the text stays off the canvas")
                 (js/Promise.resolve nil))))
    (clear-holds)))

(t/deftest ^:async a-held-text-shows-once-all-its-faces-arrive
  (let [text    (text-shape)
        calls   (atom [])
        pending (atom [])]
    (await (run-with-wasm
            {:faces {(:id text) #{"face-a" "face-b"}} :calls calls :pending pending}
            (fn []
              (wasm.api/set-objects (page-objects text) nil (fn []) true)
              (rx/push! wasm.fonts/font-stored-stream "face-a")
              (t/is (= [[:awaiting (:id text) true]] (awaiting-calls calls))
                    "one face is not enough")
              (rx/push! wasm.fonts/font-storage-failed-stream "face-b")
              (t/is (= [[:awaiting (:id text) true] [:awaiting (:id text) false]]
                       (awaiting-calls calls))
                    "a stored face and a failed one release it")
              (t/is (some #{[:relayout (:id text)]} @calls)
                    "it is laid out again with the faces it got")
              (js/Promise.resolve nil))))
    (clear-holds)))

(t/deftest ^:async each-text-shows-when-its-own-faces-arrive
  (let [text-a  (text-shape)
        text-b  (text-shape)
        calls   (atom [])
        pending (atom [])]
    (await (run-with-wasm
            {:faces {(:id text-a) #{"face-a"} (:id text-b) #{"face-b"}}
             :calls calls
             :pending pending}
            (fn []
              (wasm.api/set-objects (page-objects text-a text-b) nil (fn []) true)
              (rx/push! wasm.fonts/font-stored-stream "face-a")
              (t/is (some #{[:awaiting (:id text-a) false]} @calls) "the first text shows")
              (t/is (not (some #{[:awaiting (:id text-b) false]} @calls))
                    "the second keeps waiting for its face")
              (js/Promise.resolve nil))))
    (clear-holds)))

(t/deftest ^:async texts-whose-fonts-are-loaded-are-not-held
  (let [text    (text-shape)
        calls   (atom [])
        pending (atom [])]
    (await (run-with-wasm
            {:faces {} :calls calls :pending pending}
            #(do (wasm.api/set-objects (page-objects text) nil (fn []) true)
                 (js/Promise.resolve nil))))
    (t/is (empty? (awaiting-calls calls)))
    (clear-holds)))

(t/deftest ^:async loading-shapes-outside-a-page-load-holds-nothing
  "Thumbnails load a subtree without `on-shapes-ready`: their texts must
  not leave the canvas."
  (let [text    (text-shape)
        calls   (atom [])
        pending (atom [])]
    (await (run-with-wasm
            {:faces {(:id text) #{"face-a"}} :calls calls :pending pending}
            #(do (wasm.api/set-objects (page-objects text) nil nil true)
                 (js/Promise.resolve nil))))
    (t/is (empty? (awaiting-calls calls)))
    (clear-holds)))

(t/deftest ^:async opening-another-page-drops-the-holds-of-the-last
  (let [text    (text-shape)
        other   (text-shape)
        calls   (atom [])
        pending (atom [])]
    (await (run-with-wasm
            {:faces {(:id text) #{"face-a"}} :calls calls :pending pending}
            (fn []
              (wasm.api/set-objects (page-objects text) nil (fn []) true)
              (wasm.api/set-objects (page-objects other) nil (fn []) true)
              (t/is (= [[:awaiting (:id text) true] [:awaiting (:id text) false]]
                       (awaiting-calls calls))
                    "the text of the last page is drawn again if it comes back")
              (reset! calls [])
              (rx/push! wasm.fonts/font-stored-stream "face-a")
              (t/is (empty? @calls) "its face no longer touches it")
              (js/Promise.resolve nil))))
    (clear-holds)))

(t/deftest ^:async fonts-start-loading-before-images-and-the-first-render
  (let [text    (text-shape)
        calls   (atom [])
        pending (atom [])
        fetched (atom 0)
        fetch   {:key "font-url" :callback (fn [] (swap! fetched inc) (rx/empty))}]
    (await (run-with-wasm
            {:faces {(:id text) #{"face-a"}}
             :fonts {(:id text) [fetch]}
             :calls calls
             :pending pending}
            #(do (wasm.api/set-objects (page-objects text) nil (fn []) true)
                 (js/Promise.resolve nil))))
    (t/is (= 1 @fetched) "the font fetch started during the load")
    (let [[_shapes thumbnails] (first @pending)]
      (t/is (empty? thumbnails) "and is not started again with the images"))
    (await (async/settle))
    (clear-holds)))

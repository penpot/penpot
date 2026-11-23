;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.editable-select-test
  "A typed value in a number `editable-select` is kept within `:min` and
  `:max`, as the arrow keys already do. The frame grid size and the
  typography font size depend on it: a value out of range fails the
  backend schema and the save is rejected. The documents come from jsdom
  since the runner has no DOM."
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react-dom" :as rd]
   ["react-dom/client" :as rdc]
   [app.main.ui.components.editable-select :refer [editable-select]]
   [cljs.test :as t :include-macros true]
   [rumext.v2 :as mf]))

(defn- next-immediate
  "Resolves after the tasks React's scheduler queued with `setImmediate`
  so far have run."
  []
  (js/Promise. (fn [resolve] (js/setImmediate resolve))))

(defn- ^:async type-value
  "Mounts a number `editable-select` with `props`, types `text` in its
  input and presses Enter. Resolves to the values passed to `:on-change`.

  react-dom reads the global `window` and `document` while it renders and
  handles events, so the jsdom ones stay installed until the root is
  unmounted and the scheduler has run."
  [props text]
  (let [changes   (volatile! [])
        window    (.-window (JSDOM. "<!doctype html><div id=\"root\"></div>"))
        document  (.-document window)
        container (.getElementById document "root")
        root      (rdc/createRoot container)
        prev-win  (.-window js/globalThis)
        prev-doc  (.-document js/globalThis)]
    (set! (.-window js/globalThis) window)
    (set! (.-document js/globalThis) document)
    (try
      (rd/flushSync
       (fn []
         (.render root (mf/element editable-select
                                   (clj->js (merge {:type "number"
                                                    :value 12
                                                    :options [8 12 16]
                                                    :on-change #(vswap! changes conj %)}
                                                   props))))))
      (let [input (.querySelector container "input")]
        ;; react-dom loaded without a document, so it uses its old IE
        ;; fallback for change events, which calls these on focus.
        (set! (.-attachEvent input) (fn [_ _]))
        (set! (.-detachEvent input) (fn [_ _]))
        (rd/flushSync #(.focus input))
        (set! (.-value input) text)
        (rd/flushSync
         #(.dispatchEvent input (new (.-KeyboardEvent window) "keydown"
                                     #js {:key "Enter" :bubbles true}))))
      (rd/flushSync #(.unmount root))
      (await (next-immediate))
      @changes
      (finally
        (set! (.-window js/globalThis) prev-win)
        (set! (.-document js/globalThis) prev-doc)))))

(t/deftest ^:async typed-value-below-min-is-raised-to-min
  (t/is (= [1] (await (type-value {:min 1} "0"))))
  (t/is (= [1] (await (type-value {:min 1} "-5")))))

(t/deftest ^:async typed-value-above-max-is-lowered-to-max
  (t/is (= [1000] (await (type-value {:min 3 :max 1000} "5000")))))

(t/deftest ^:async typed-value-in-range-is-kept
  (t/is (= [7] (await (type-value {:min 1} "7")))))

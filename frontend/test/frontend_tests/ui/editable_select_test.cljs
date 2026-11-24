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

(defn- ^:async with-editable-select
  "Mounts a number `editable-select` with `props`, calls `f` with its
  input and the jsdom window, then unmounts it. Resolves to the result of
  `f` and the values passed to `:on-change`, as `[result changes]`.

  react-dom reads the global `window` and `document` while it renders and
  handles events, so the jsdom ones stay installed until the root is
  unmounted and the scheduler has run."
  [props f]
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
         (let [props   (merge {:type "number"
                               :value 12
                               :options [8 12 16]
                               :on-change #(vswap! changes conj %)}
                              props)
               js-props (clj->js props)]
           ;; clj->js turns keywords into strings: keep the :multiple
           ;; value of a mixed selection as the menus pass it.
           (set! (.-value js-props) (:value props))
           (.render root (mf/element editable-select js-props)))))
      (let [input  (.querySelector container "input")
            _      (set! (.-attachEvent input) (fn [_ _]))
            _      (set! (.-detachEvent input) (fn [_ _]))
            ;; react-dom loaded without a document, so it uses its old IE
            ;; fallback for change events, which calls the two above on
            ;; focus.
            result (f input window)]
        (rd/flushSync #(.unmount root))
        (await (next-immediate))
        [result @changes])
      (finally
        (set! (.-window js/globalThis) prev-win)
        (set! (.-document js/globalThis) prev-doc)))))

(defn- ^:async type-value
  "Focuses the input of a number `editable-select` with `props`, types
  `text` and presses Enter. With a nil `text` it only focuses the input
  and leaves it. Resolves to the values passed to `:on-change`."
  [props text]
  (let [[_ changes]
        (await (with-editable-select
                 props
                 (fn [input window]
                   (rd/flushSync #(.focus input))
                   (if (some? text)
                     (do
                       (set! (.-value input) text)
                       (rd/flushSync
                        #(.dispatchEvent input (new (.-KeyboardEvent window) "keydown"
                                                    #js {:key "Enter" :bubbles true}))))
                     (rd/flushSync #(.blur input))))))]
    changes))

(defn- ^:async press-key
  "Focuses the input of a number `editable-select` with `props`, presses
  `key` without typing and leaves the input. Resolves to the values passed
  to `:on-change`."
  [props key]
  (let [[_ changes]
        (await (with-editable-select
                 props
                 (fn [input window]
                   (rd/flushSync #(.focus input))
                   (rd/flushSync
                    #(.dispatchEvent input (new (.-KeyboardEvent window) "keydown"
                                                #js {:key key :bubbles true})))
                   (rd/flushSync #(.blur input)))))]
    changes))

(defn- ^:async rendered-text
  "Resolves to the text shown by the input of a number `editable-select`
  with `props`."
  [props]
  (let [[text _] (await (with-editable-select props (fn [input _] (.-value input))))]
    text))

(t/deftest ^:async typed-value-below-min-is-raised-to-min
  (t/is (= [1] (await (type-value {:min 1} "0"))))
  (t/is (= [1] (await (type-value {:min 1} "-5")))))

(t/deftest ^:async typed-value-above-max-is-lowered-to-max
  (t/is (= [1000] (await (type-value {:min 3 :max 1000} "5000")))))

(t/deftest ^:async typed-value-in-range-is-kept
  (t/is (= [7] (await (type-value {:min 1} "7")))))

(t/deftest ^:async leaving-an-auto-field-keeps-it-auto
  ;; An empty grid size means Auto. Focusing the field and leaving it
  ;; without typing must not save the minimum.
  (t/is (= [] (await (type-value {:value nil :min 1 :is-nillable true} nil)))))

(t/deftest ^:async leaving-a-field-keeps-a-stored-value-out-of-range
  (t/is (= [] (await (type-value {:value 2 :min 3 :max 1000} nil)))))

(t/deftest ^:async clearing-a-nillable-field-saves-nil
  ;; The grid size is nillable: an empty field means Auto.
  (t/is (= [nil] (await (type-value {:value 4 :min 1 :is-nillable true} "")))))

(t/deftest ^:async an-auto-nillable-field-shows-its-placeholder
  ;; Auto is a nil size: the field stays empty so the placeholder shows,
  ;; instead of the input default 0.
  (t/is (= "" (await (rendered-text {:value nil :is-nillable true :placeholder "Auto"}))))
  (t/is (= "0" (await (rendered-text {:value nil})))))

(t/deftest ^:async tab-or-escape-keeps-a-stored-value-out-of-range
  ;; Only typed and stepped values are clamped: leaving a font size of 2
  ;; with Tab or Escape must not save the minimum.
  (t/is (= [] (await (press-key {:value 2 :min 3 :max 1000} "Tab"))))
  (t/is (= [] (await (press-key {:value 2 :min 3 :max 1000} "Escape")))))

(t/deftest ^:async leaving-a-mixed-field-saves-nothing
  ;; A mixed font size has no value. Leaving it without typing must not
  ;; save the input default 0, which the backend rejects.
  (t/is (= [] (await (type-value {:value :multiple :min 3 :max 1000} nil))))
  (t/is (= [] (await (press-key {:value :multiple :min 3 :max 1000} "Tab")))))

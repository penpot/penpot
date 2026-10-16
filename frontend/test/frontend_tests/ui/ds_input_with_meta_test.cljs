;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.ds-input-with-meta-test
  "The blur of `input-with-meta*`, mounted in a jsdom document since the
   runner has no DOM. React loads before the test sets up the document, so
   it cannot dispatch DOM events here: the container stays out of the
   document (autofocus does nothing) and a blur calls the input's handler."
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react" :as react]
   ["react-dom/client" :as rdc]
   [app.main.ui.ds.product.input-with-meta :refer [input-with-meta*]]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private dom-globals ["window" "document" "IS_REACT_ACT_ENVIRONMENT"])

(defn- with-dom
  "Call `f` with a container of a jsdom document set as the global one."
  [f]
  (let [window   (.-window (JSDOM. "<!DOCTYPE html><body></body>"))
        previous (into {} (map (fn [k] [k (unchecked-get js/globalThis k)])) dom-globals)]
    (doseq [[k v] {"window"                   window
                   "document"                 (.-document window)
                   "IS_REACT_ACT_ENVIRONMENT" true}]
      (unchecked-set js/globalThis k v))
    (try
      (f (.createElement (.-document window) "div"))
      (finally
        (doseq [[k v] previous]
          (unchecked-set js/globalThis k v))))))

(defn- blur!
  "Blur the input of `container` the way React would on a real blur."
  [container]
  (let [input (.querySelector container "input")
        props (->> (js/Object.keys input)
                   (some #(when (str/starts-with? % "__reactProps$") (unchecked-get input %))))]
    (.onBlur props #js {:target input :currentTarget input :stopPropagation (fn [])})))

(defn- element
  [value on-blur]
  (mf/element input-with-meta* #js {:value value :isEditing true :onBlur on-blur}))

(t/deftest unmount-after-a-committed-edit-sends-no-blur
  ;; The edit commits on blur; the value then changes (the rows the inputs
  ;; belong to are reordered) and the input goes away. The committed edit
  ;; must not be sent again with the text it had.
  (with-dom
    (fn [container]
      (let [blurs   (atom [])
            on-blur (fn [event] (swap! blurs conj (.. event -target -value)))
            root    (rdc/createRoot container)]
        (react/act #(.render root (element "Property 2" on-blur)))
        (react/act #(blur! container))
        (react/act #(.render root (element "Property 1" on-blur)))
        (react/act #(.unmount root))
        (t/is (= ["Property 2"] @blurs))))))

(t/deftest unmount-during-an-edit-commits-it
  (with-dom
    (fn [container]
      (let [blurs   (atom [])
            on-blur (fn [event] (swap! blurs conj (.. event -target -value)))
            root    (rdc/createRoot container)]
        (react/act #(.render root (element "Property 1" on-blur)))
        (react/act #(.unmount root))
        (t/is (= ["Property 1"] @blurs))))))

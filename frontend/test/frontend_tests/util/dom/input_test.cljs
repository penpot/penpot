;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.util.dom.input-test
  "Tests how penpot sets the text of an input as typing does, and how it
  reads the text of a paste. The input comes from jsdom, because the
  test runner has no DOM."
  (:require
   ["jsdom" :refer [JSDOM]]
   [app.util.dom :as dom]
   [cljs.test :as t :include-macros true]))

(def ^:private globals
  "The globals that `set-input-value!` reads."
  ["HTMLInputElement" "Event"])

(defn- with-input
  "Calls `f` with an input of a jsdom document. During the call, the
  globals of the jsdom window replace the globals of the browser."
  [f]
  (let [window   (.-window (JSDOM. "<!doctype html><input id=\"field\">"))
        previous (mapv #(unchecked-get js/globalThis %) globals)]
    (doseq [global globals]
      (unchecked-set js/globalThis global (unchecked-get window global)))
    (try
      (f (.getElementById (.-document window) "field"))
      (finally
        (doseq [[global value] (map vector globals previous)]
          (unchecked-set js/globalThis global value))
        (.close window)))))

(t/deftest set-input-value-test
  (t/testing "the input has the text, and says so with an input event"
    (with-input
      (fn [input]
        (let [seen (atom [])]
          (.addEventListener input "input" (fn [event] (swap! seen conj [(.-type event) (.-value input)])))
          (dom/set-input-value! input "linear-gradient(red, blue)")
          (t/is (= "linear-gradient(red, blue)" (.-value input)))
          (t/is (= [["input" "linear-gradient(red, blue)"]] @seen))))))

  (t/testing "the event reaches the ones that listen above the input"
    (with-input
      (fn [input]
        (let [seen (atom 0)]
          (.addEventListener (.-parentNode input) "input" (fn [_] (swap! seen inc)))
          (dom/set-input-value! input "a")
          (t/is (= 1 @seen))))))

  (t/testing "without an input nothing happens"
    (t/is (nil? (dom/set-input-value! nil "a")))))

(t/deftest get-clipboard-text-test
  (t/testing "the plain text of the paste"
    (let [asked (atom nil)
          event #js {:clipboardData #js {:getData (fn [type] (reset! asked type) "red, blue")}}]
      (t/is (= "red, blue" (dom/get-clipboard-text event)))
      (t/is (= "text" @asked))))

  (t/testing "a paste without clipboard data has no text"
    (t/is (nil? (dom/get-clipboard-text #js {})))))
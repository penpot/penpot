;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.workspace-shortcuts-test
  (:require
   [app.config :as cf]
   [app.main.data.workspace.shortcuts :as shortcuts]
   [app.main.store :as st]
   [cljs.test :as t :include-macros true]))

(defn- keyboard-event
  [{:keys [ctrl? meta? shift? code prevented?]}]
  #js {:ctrlKey        (boolean ctrl?)
       :metaKey        (boolean meta?)
       :shiftKey       (boolean shift?)
       :code           code
       :preventDefault #(reset! prevented? true)})

(t/deftest display-guides-handler-detects-physical-keys
  (t/testing "US layout uses Ctrl+Quote"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly false)
                    st/emit! #(swap! emitted conj %)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:ctrl? true :code "Quote" :prevented? prevented?})))
      (t/is (true? @prevented?))
      (t/is (= 1 (count @emitted)))))

  (t/testing "German layout uses Ctrl+Shift+Backslash"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly false)
                    st/emit! #(swap! emitted conj %)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:ctrl? true :shift? true :code "Backslash" :prevented? prevented?})))
      (t/is (true? @prevented?))
      (t/is (= 1 (count @emitted)))))

  (t/testing "macOS uses Meta+Quote"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly true)
                    st/emit! #(swap! emitted conj %)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:meta? true :code "Quote" :prevented? prevented?})))
      (t/is (true? @prevented?))
      (t/is (= 1 (count @emitted)))))

  (t/testing "macOS German layout uses Meta+Shift+Backslash"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly true)
                    st/emit! #(swap! emitted conj %)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:meta? true :shift? true :code "Backslash" :prevented? prevented?})))
      (t/is (true? @prevented?))
      (t/is (= 1 (count @emitted)))))

  (t/testing "macOS ignores Ctrl+Quote"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly true)
                    st/emit! #(swap! emitted conj %)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:ctrl? true :code "Quote" :prevented? prevented?})))
      (t/is (false? @prevented?))
      (t/is (empty? @emitted))))

  (t/testing "macOS snap-guides shortcut stays out of this handler"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly true)
                    st/emit! #(swap! emitted conj %)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:meta? true :shift? true :code "Quote" :prevented? prevented?})))
      (t/is (false? @prevented?))
      (t/is (empty? @emitted))))

  (t/testing "matching physical keys without the platform modifier are ignored"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly false)
                    st/emit! #(swap! emitted conj %)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:code "Quote" :prevented? prevented?}))
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:shift? true :code "Backslash" :prevented? prevented?})))
      (t/is (false? @prevented?))
      (t/is (empty? @emitted))))

  (t/testing "snap-guides and alignment shortcuts stay out of this handler"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly false)
                    st/emit! #(swap! emitted conj %)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:ctrl? true :shift? true :code "Quote" :prevented? prevented?}))
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:ctrl? true :code "Backslash" :prevented? prevented?})))
      (t/is (false? @prevented?))
      (t/is (empty? @emitted)))))

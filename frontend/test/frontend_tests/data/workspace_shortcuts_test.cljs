;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-shortcuts-test
  (:require
   [app.config :as cf]
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.shortcuts :as path-shortcuts]
   [app.main.data.workspace.shortcuts :as shortcuts]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [cljs.test :as t :include-macros true]))

(defn- keyboard-event
  [{:keys [ctrl? meta? shift? code prevented?]}]
  #js {:ctrlKey        (boolean ctrl?)
       :metaKey        (boolean meta?)
       :shiftKey       (boolean shift?)
       :code           code
       :preventDefault #(reset! prevented? true)})

(defn- mock-emit!
  [emitted]
  (fn
    ([] nil)
    ([event] (swap! emitted conj event))
    ([event & _more] nil)))

(t/deftest display-guides-handler-detects-physical-keys
  (t/testing "US layout uses Ctrl+Quote"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly false)
                    st/emit! (mock-emit! emitted)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:ctrl? true :code "Quote" :prevented? prevented?})))
      (t/is (true? @prevented?))
      (t/is (= 1 (count @emitted)))))

  (t/testing "German layout uses Ctrl+Shift+Backslash"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly false)
                    st/emit! (mock-emit! emitted)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:ctrl? true :shift? true :code "Backslash" :prevented? prevented?})))
      (t/is (true? @prevented?))
      (t/is (= 1 (count @emitted)))))

  (t/testing "macOS uses Meta+Quote"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly true)
                    st/emit! (mock-emit! emitted)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:meta? true :code "Quote" :prevented? prevented?})))
      (t/is (true? @prevented?))
      (t/is (= 1 (count @emitted)))))

  (t/testing "macOS German layout uses Meta+Shift+Backslash"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly true)
                    st/emit! (mock-emit! emitted)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:meta? true :shift? true :code "Backslash" :prevented? prevented?})))
      (t/is (true? @prevented?))
      (t/is (= 1 (count @emitted)))))

  (t/testing "macOS ignores Ctrl+Quote"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly true)
                    st/emit! (mock-emit! emitted)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:ctrl? true :code "Quote" :prevented? prevented?})))
      (t/is (false? @prevented?))
      (t/is (empty? @emitted))))

  (t/testing "macOS snap-guides shortcut stays out of this handler"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly true)
                    st/emit! (mock-emit! emitted)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:meta? true :shift? true :code "Quote" :prevented? prevented?})))
      (t/is (false? @prevented?))
      (t/is (empty? @emitted))))

  (t/testing "matching physical keys without the platform modifier are ignored"
    (let [prevented? (atom false)
          emitted    (atom [])]
      (with-redefs [cf/check-platform? (constantly false)
                    st/emit! (mock-emit! emitted)]
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
                    st/emit! (mock-emit! emitted)]
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:ctrl? true :shift? true :code "Quote" :prevented? prevented?}))
        (shortcuts/on-display-guides-keydown
         (keyboard-event {:ctrl? true :code "Backslash" :prevented? prevented?})))
      (t/is (false? @prevented?))
      (t/is (empty? @emitted)))))

(def ^:private arrows
  {:up    ds/up-arrow
   :down  ds/down-arrow
   :left  ds/left-arrow
   :right ds/right-arrow})

(defn- move-shortcuts
  "The move shortcuts of `shortcuts-map` as `[id direction fast?]`."
  [shortcuts-map]
  (for [dir   (keys arrows)
        fast? [false true]
        :let  [id (keyword (str (if fast? "move-fast-" "move-unit-") (name dir)))]
        :when (contains? shortcuts-map id)]
    [id dir fast?]))

(defn- run-move-shortcut
  "Runs the :fn of shortcut `id` in `shortcuts-map` with the moves stubbed
  and returns the moves it emitted, as `[direction fast?]`."
  [shortcuts-map id]
  (let [emitted (atom [])]
    (with-redefs [dwt/move-selected         (fn [dir fast?] [::move dir fast?])
                  drp/move-selected         (fn [dir fast?] [::move dir fast?])
                  refs/permissions          (atom {:can-edit true})
                  refs/workspace-read-only? (atom false)
                  st/emit!                  (mock-emit! emitted)]
      ((:fn (get shortcuts-map id))))
    (into [] (keep (fn [[tag dir fast?]] (when (= ::move tag) [dir fast?]))) @emitted)))

(t/deftest move-shortcuts-move-in-their-direction
  (doseq [[map-name shortcuts-map] [["workspace" shortcuts/shortcuts]
                                    ["path editor" path-shortcuts/shortcuts]]
          [id dir fast?]           (move-shortcuts shortcuts-map)]
    (t/is (= [[dir fast?]] (run-move-shortcut shortcuts-map id))
          (str map-name " " id " moves " (name dir) (when fast? " fast")))))

(t/deftest move-shortcuts-show-the-arrow-of-their-direction
  (doseq [[map-name shortcuts-map] [["workspace" shortcuts/shortcuts]
                                    ["path editor" path-shortcuts/shortcuts]]
          [id dir fast?]           (move-shortcuts shortcuts-map)]
    (t/is (= (cond-> (get arrows dir) fast? ds/shift)
             (:tooltip (get shortcuts-map id)))
          (str map-name " " id " shows the " (name dir) " arrow"))))

(t/deftest move-shortcuts-cover-every-direction
  (t/is (= 8 (count (move-shortcuts shortcuts/shortcuts))))
  (t/is (= 8 (count (move-shortcuts path-shortcuts/shortcuts)))))

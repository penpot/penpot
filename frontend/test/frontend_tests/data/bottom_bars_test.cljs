;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.bottom-bars-test
  (:require
   [app.main.data.workspace.layout :as dwlo]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(defn- apply-events
  [state & events]
  (reduce #(ptk/update %2 %1) state events))

(defn- bottom-bar
  [state]
  (get-in state [:workspace-global :bottom-bar]))

(t/deftest the-bar-of-a-branch-comes-open
  (t/is (= :branch (bottom-bar (apply-events {} (dwlo/mount-bottom-bars))))))

(t/deftest opening-a-bar-folds-the-other
  (let [state (apply-events {} (dwlo/mount-bottom-bars))]
    (t/testing "the palettes fold the bar of the branch"
      (t/is (= :palette (bottom-bar (apply-events state (dwlo/toggle-bottom-bar :palette))))))
    (t/testing "and the other way around"
      (t/is (= :branch (bottom-bar (apply-events state
                                                 (dwlo/toggle-bottom-bar :palette)
                                                 (dwlo/toggle-bottom-bar :branch))))))
    (t/testing "a shortcut opens the palettes"
      (t/is (= :palette (bottom-bar (apply-events state (dwlo/open-bottom-bar :palette))))))))

(t/deftest folding-a-bar-opens-none
  (let [state (apply-events {} (dwlo/mount-bottom-bars))]
    (t/testing "the bar of the branch"
      (t/is (= :none (bottom-bar (apply-events state (dwlo/toggle-bottom-bar :branch))))))
    (t/testing "the palettes"
      (t/is (= :none (bottom-bar (apply-events state
                                               (dwlo/toggle-bottom-bar :palette)
                                               (dwlo/toggle-bottom-bar :palette))))))))

(t/deftest without-a-branch-nothing-changes
  (let [state {:workspace-global {:options-mode :design}}]
    (t/is (= state (apply-events state (dwlo/toggle-bottom-bar :palette))))
    (t/is (= state (apply-events state (dwlo/open-bottom-bar :palette))))))

(t/deftest leaving-the-branch-forgets-the-bars
  (let [state (apply-events {}
                            (dwlo/mount-bottom-bars)
                            (dwlo/unmount-bottom-bars))]
    (t/is (nil? (bottom-bar state)))))

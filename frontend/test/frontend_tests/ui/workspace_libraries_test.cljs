;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.workspace-libraries-test
  (:require
   [app.main.ui.workspace.libraries :as wl]
   [cljs.test :as t :include-macros true]))

(defn- lib
  [id indirect?]
  {:id id :name (name id) :is-indirect indirect?})

(defn- summarize
  [libraries]
  (mapv (juxt :id :nested? :parent-name) libraries))

(t/deftest sort-linked-libraries-cycle-with-direct-root
  ;; file -> App, Foundations; App -> Foundations;
  ;; Foundations <-> Comments (cycle, as left by an import)
  (let [libraries    [(lib :foundations false)
                      (lib :app false)
                      (lib :comments true)]
        dependencies {:file        #{:app :foundations}
                      :app         #{:foundations}
                      :foundations #{:comments}
                      :comments    #{:foundations}}]
    (t/is (= [[:app false nil]
              [:foundations false nil]
              [:comments true "foundations"]]
             (summarize (wl/sort-linked-libraries libraries dependencies))))))

(t/deftest sort-linked-libraries-cycle-without-root
  ;; Opened from App: both libraries are only reachable through the cycle
  ;; when the direct flag is unknown.
  (let [libraries    [(lib :foundations nil)
                      (lib :comments nil)]
        dependencies {:app         #{:foundations}
                      :foundations #{:comments}
                      :comments    #{:foundations}}]
    (t/is (= [[:comments false nil]
              [:foundations true "comments"]]
             (summarize (wl/sort-linked-libraries libraries dependencies))))))

(t/deftest sort-linked-libraries-deep-chain
  ;; file -> a -> b -> c
  (let [libraries    [(lib :c true) (lib :b true) (lib :a false)]
        dependencies {:a #{:b} :b #{:c}}]
    (t/is (= [[:a false nil]
              [:b true "a"]
              [:c true "b"]]
             (summarize (wl/sort-linked-libraries libraries dependencies))))))

(t/deftest sort-linked-libraries-without-dependencies
  ;; Before get-team-shared-files answers, all libraries are flat
  (let [libraries [(lib :b true) (lib :a false)]]
    (t/is (= [[:a false nil]
              [:b false nil]]
             (summarize (wl/sort-linked-libraries libraries {}))))))

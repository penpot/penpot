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
  "A linked library; `connected-to` lists the ids of the files using it."
  [id indirect? connected-to]
  {:id id :name (name id) :is-indirect indirect? :connected-to connected-to})

(defn- summarize
  [libraries]
  (mapv (juxt :id :nested? :parent-name) libraries))

(t/deftest sort-linked-libraries-cycle-with-direct-root
  ;; file -> App, Foundations; App -> Foundations;
  ;; Foundations <-> Comments (cycle, as left by an import)
  (let [libraries [(lib :foundations false [:file :app :comments])
                   (lib :app false [:file])
                   (lib :comments true [:foundations])]
        result    (wl/sort-linked-libraries libraries)]
    (t/is (= [[:app false nil]
              [:foundations false nil]
              [:comments true "foundations"]]
             (summarize result)))
    (t/is (= (count libraries) (count result)))))

(t/deftest sort-linked-libraries-cycle-without-root
  ;; The direct flag is still unknown (nil) while the shared files load,
  ;; so both libraries are only reachable through the cycle. `:app` is
  ;; not a linked library, so it is ignored as a parent.
  (let [libraries [(lib :foundations nil [:app :comments])
                   (lib :comments nil [:foundations])]
        result    (wl/sort-linked-libraries libraries)]
    (t/is (= [[:comments false nil]
              [:foundations true "comments"]]
             (summarize result)))
    (t/is (= (count libraries) (count result)))))

(t/deftest sort-linked-libraries-cycle-of-three-without-root
  ;; a -> b -> c -> a, all indirect: no root, nothing is lost
  (let [libraries [(lib :c true [:b])
                   (lib :b true [:a])
                   (lib :a true [:c])]
        result    (wl/sort-linked-libraries libraries)]
    (t/is (= [[:a false nil]
              [:b true "a"]
              [:c true "b"]]
             (summarize result)))
    (t/is (= (count libraries) (count result)))))

(t/deftest sort-linked-libraries-deep-chain
  ;; file -> a -> b -> c
  (let [libraries [(lib :c true [:b])
                   (lib :b true [:a])
                   (lib :a false [:file])]]
    (t/is (= [[:a false nil]
              [:b true "a"]
              [:c true "b"]]
             (summarize (wl/sort-linked-libraries libraries))))))

(t/deftest sort-linked-libraries-diamond
  ;; a -> b, c; b -> d; c -> d: d is listed once, under its first parent
  (let [libraries [(lib :d true [:b :c])
                   (lib :c true [:a])
                   (lib :b true [:a])
                   (lib :a false [:file])]
        result    (wl/sort-linked-libraries libraries)]
    (t/is (= [[:a false nil]
              [:b true "a"]
              [:d true "b"]
              [:c true "a"]]
             (summarize result)))
    (t/is (= (count libraries) (count result)))))

(t/deftest sort-linked-libraries-self-dependency
  (let [libraries [(lib :a true [:a])]]
    (t/is (= [[:a false nil]]
             (summarize (wl/sort-linked-libraries libraries))))))

(t/deftest sort-linked-libraries-parent-not-linked
  ;; The only user of each library is not a linked library, so both are roots
  (let [libraries [(lib :b true [:other])
                   (lib :a true [:file])]]
    (t/is (= [[:a false nil]
              [:b false nil]]
             (summarize (wl/sort-linked-libraries libraries))))))

(t/deftest sort-linked-libraries-without-dependencies
  ;; Before get-team-shared-files answers, all libraries are flat
  (let [libraries [(lib :b true nil)
                   (lib :a false nil)]]
    (t/is (= [[:a false nil]
              [:b false nil]]
             (summarize (wl/sort-linked-libraries libraries))))))

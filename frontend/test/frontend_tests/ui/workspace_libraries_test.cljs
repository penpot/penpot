;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.workspace-libraries-test
  (:require
   [app.common.data :as d]
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

(defn- disconnect-hints
  "The disconnect hint of every library, by id, once sorted as the
  Libraries modal shows them."
  [libraries]
  (let [sorted (wl/sort-linked-libraries libraries)
        by-id  (d/index-by :id sorted)]
    (into {} (map (juxt :id #(wl/library-disconnect-hint % by-id))) sorted)))

(t/deftest library-disconnect-hint-none-when-no-linked-library-uses-it
  ;; Only the file uses it, so the user can disconnect it
  (t/is (= {:a nil}
           (disconnect-hints [(lib :a false [:file])]))))

(t/deftest library-disconnect-hint-nested-library-is-inherited-from-its-parent
  ;; file -> a -> b: b stays while a is connected
  (t/is (= {:a nil
            :b {:reason :inherited :library-name "a" :removable ["a"]}}
           (disconnect-hints [(lib :a false [:file])
                              (lib :b true [:a])]))))

(t/deftest library-disconnect-hint-direct-library-is-used-by-another-one
  ;; file -> a, b; a -> b: b is linked directly but a needs it
  (t/is (= {:a nil
            :b {:reason :used-by :library-name "a" :removable ["a"]}}
           (disconnect-hints [(lib :a false [:file])
                              (lib :b false [:file :a])]))))

(t/deftest library-disconnect-hint-names-the-first-user-by-name
  (t/is (= {:c {:reason :used-by :library-name "a" :removable ["a" "b"]}
            :a nil
            :b nil}
           (disconnect-hints [(lib :c false [:file :b :a])
                              (lib :b false [:file])
                              (lib :a false [:file])]))))

(t/deftest library-disconnect-hint-ignores-users-that-are-not-linked
  ;; `other` uses a, but it is not one of the file's libraries
  (t/is (= {:a nil}
           (disconnect-hints [(lib :a false [:file :other])]))))

(t/deftest library-disconnect-hint-shared-dependency-lists-every-removable-parent
  ;; file -> a, b; a -> c; b -> c: disconnecting a alone leaves c linked
  ;; through b, so both have to go
  (t/is (= {:a nil
            :b nil
            :c {:reason :inherited :library-name "a" :removable ["a" "b"]}}
           (disconnect-hints [(lib :a false [:file])
                              (lib :b false [:file])
                              (lib :c true [:a :b])]))))

(t/deftest library-disconnect-hint-deep-chain-points-to-the-removable-library
  ;; file -> a -> b -> c: b is nested too and has no button, so the
  ;; removal step names a, the only library the user can disconnect
  (t/is (= {:a nil
            :b {:reason :inherited :library-name "a" :removable ["a"]}
            :c {:reason :inherited :library-name "b" :removable ["a"]}}
           (disconnect-hints [(lib :c true [:b])
                              (lib :b true [:a])
                              (lib :a false [:file])]))))

(t/deftest library-disconnect-hint-lists-a-directly-linked-intermediate-library
  ;; file -> a, b; a -> b -> c: disconnecting a leaves the direct link to b,
  ;; which keeps c, so b has to go as well. b only becomes removable once a is gone.
  (t/is (= {:a nil
            :b {:reason :used-by :library-name "a" :removable ["a"]}
            :c {:reason :inherited :library-name "b" :removable ["a" "b"]}}
           (disconnect-hints [(lib :a false [:file])
                              (lib :b false [:file :a])
                              (lib :c true [:b])]))))

(t/deftest library-disconnect-hint-cycle-falls-back-to-the-parent
  ;; a <-> b, nothing removable: name the parent rather than nothing
  (t/is (= {:a {:reason :used-by :library-name "b" :removable ["b"]}
            :b {:reason :inherited :library-name "a" :removable ["a"]}}
           (disconnect-hints [(lib :a true [:b])
                              (lib :b true [:a])]))))

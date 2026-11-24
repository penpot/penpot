;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.color-selection-test
  (:require
   [app.main.ui.workspace.sidebar.options.menus.color-selection :as color-selection]
   [clojure.test :as t :include-macros true]))

(def ^:private color-a
  {:color "#f9f06b" :opacity 1})

(def ^:private color-b
  {:color "#f8e45c" :opacity 1})

(def ^:private color-c
  {:color "#f6d32d" :opacity 1})

(def ^:private groups
  {color-a [{:prop :fill :shape-id :shape-a :index 0}]
   color-b [{:prop :fill :shape-id :shape-b :index 0}]
   color-c [{:prop :fill :shape-id :shape-c :index 0}]})

(t/deftest direct-edit-targets-the-edited-row-color
  ;; Regression for https://github.com/penpot/penpot/issues/12759: after
  ;; editing one row, its color stays cached and a direct edit on another row
  ;; must not reuse those operations.
  (let [prev-colors [color-a]]
    (t/is (= [(get groups color-b) color-b]
             (color-selection/resolve-color-operations
              groups color-b prev-colors false)))))

(t/deftest direct-edit-ignores-cached-picker-colors
  (let [stale-color (assoc color-a :opacity 0.5)]
    (t/is (= [(get groups color-c) color-c]
             (color-selection/resolve-color-operations
              groups color-c [stale-color] false)))))

(t/deftest picker-edit-falls-back-to-the-previous-color
  ;; While dragging the colorpicker the row color can be stale, so the
  ;; previously applied color is used to find the target operations.
  (t/is (= [(get groups color-b) color-b]
           (color-selection/resolve-color-operations
            groups color-a [color-b] true))))

(t/deftest picker-edit-uses-the-row-color-on-the-first-change
  (t/is (= [(get groups color-a) color-a]
           (color-selection/resolve-color-operations
            groups color-a [] true))))

(t/deftest color-name-and-path-do-not-affect-operation-lookup
  (let [named-color (assoc color-a :name "Yellow 1" :path "palette")]
    (t/is (= [(get groups color-a) color-a]
             (color-selection/resolve-color-operations
              groups named-color nil false)))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.keyboard)

(defn is-key?
  [key]
  (fn [e]
    (= (.-key e) key)))

(defn ^boolean alt?
  [event]
  (.-altKey event))

(defn ^boolean ctrl?
  [event]
  (.-ctrlKey event))

(defn ^boolean meta?
  [event]
  (.-metaKey event))

(defn ^boolean shift?
  [event]
  (.-shiftKey event))

(def esc? (is-key? "Escape"))
(def enter? (is-key? "Enter"))
(def space? (is-key? " "))
(def up-arrow? (is-key? "ArrowUp"))
(def down-arrow? (is-key? "ArrowDown"))
(def altKey? (is-key? "Alt"))
(def ctrlKey? (or (is-key? "Control")
                  (is-key? "Meta")))

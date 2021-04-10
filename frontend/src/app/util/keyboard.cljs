;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.keyboard)

(defn is-keycode?
  [keycode]
  (fn [e]
    (= (.-keyCode e) keycode)))

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

(def esc? (is-keycode? 27))
(def enter? (is-keycode? 13))
(def space? (is-keycode? 32))
(def up-arrow? (is-keycode? 38))
(def down-arrow? (is-keycode? 40))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.ui.keyboard)

(defn is-keycode?
  [keycode]
  (fn [e]
    (= (.-keyCode e) keycode)))

(defn ctrl?
  [event]
  (.-ctrlKey event))

(defn shift?
  [event]
  (.-shiftKey event))

(def esc? (is-keycode? 27))
(def enter? (is-keycode? 13))
(def space? (is-keycode? 32))

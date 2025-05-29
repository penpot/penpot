;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.theme
  (:require
   [app.util.globals :as globals]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set the preferred color scheme based on the user's system settings.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private color-scheme-mq
  (.matchMedia globals/window "(prefers-color-scheme: dark)"))

;; This atom is referenced in app.main.ui.app
(defonce preferred-color-scheme
  (atom (if (.-matches color-scheme-mq) "dark" "light")))

(defonce prefers-color-scheme-sub
  (let [sub (rx/behavior-subject "dark")
        ob  (->> (rx/from-event color-scheme-mq "change")
                 (rx/map #(if (.-matches %) "dark" "light")))]
    (rx/sub! ob sub)
    sub))

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/WatchEvent
    (watch [_ _ _]
      (->> prefers-color-scheme-sub
           (rx/map #(reset! preferred-color-scheme %))))))
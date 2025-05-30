;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
;; Copyright (c) Mathieu BRUNOT <mathieu.brunot@monogramm.io>

(ns app.util.theme
  "A theme manager."
  (:require
   [app.config :as cfg]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(defonce theme (get storage/global ::theme cfg/default-theme))
(defonce theme-sub (rx/subject))
(defonce themes #js {})

(defn init!
  [data]
  (set! themes data))

(defn set-current-theme!
  [v]
  (when (not= theme v)
    (when-some [el (dom/get-element "theme")]
      (set! (.-href el) (str "css/main-" v ".css")))
    (swap! storage/global assoc ::theme v)
    (set! theme v)
    (rx/push! theme-sub v)))

(defn set-default-theme!
  []
  (set-current-theme! cfg/default-theme))

(defn use-theme
  []
  (let [[theme set-theme] (mf/useState theme)]
    (mf/useEffect (fn []
                    (let [sub (rx/sub! theme-sub #(set-theme %))]
                      #(rx/dispose! sub)))
                  #js [])
    theme))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Set the preferred color scheme based on the user's system settings.
;; TODO: this is unrelated to the theme support above, which seems unused as
;;       of v2.7
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
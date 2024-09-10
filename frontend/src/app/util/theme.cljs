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
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
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


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.loader
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.main.state :as st]
            [uxbox.common.rstore :as rs]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.mixins :as mx]
            [uxbox.main.ui.shapes]))

;; --- Error Handling

(defn- on-error
  [error]
  ;; Disable loader in case of error.
  (reset! st/loader false))

(rs/add-error-watcher :loader on-error)

;; --- Component

(defn loader-render
  [own]
  (when (rum/react st/loader)
    (html
     [:div.loader-content i/loader])))

(def loader
  (mx/component
   {:render loader-render
    :name "loader"
    :mixins [rum/reactive mx/static]}))


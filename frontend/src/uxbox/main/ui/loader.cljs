;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.loader
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.store :as st]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.shapes]))

;; --- Component

(defn loader-render
  [own]
  (when (mx/react st/loader)
    (html
     [:div.loader-content i/loader])))

(def loader
  (mx/component
   {:render loader-render
    :name "loader"
    :mixins [mx/reactive mx/static]}))


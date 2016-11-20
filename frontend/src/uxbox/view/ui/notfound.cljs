;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.notfound
  (:require [sablono.core :refer-macros [html]]
            [uxbox.util.mixins :as mx :include-macros true]))

(defn notfound-page-render
  [own]
  (html
   [:div
    [:strong "Not Found"]]))

(def notfound-page
  (mx/component
   {:render notfound-page-render
    :name "notfound-page"
    :mixins [mx/static]}))


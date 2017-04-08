;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.viewer
  (:require [lentes.core :as l]
            [uxbox.builtins.icons :as i]
            [uxbox.util.i18n :refer [tr]]
            [rumext.core :as mx :include-macros true]
            [uxbox.view.store :as st]
            [uxbox.view.data.viewer :as dv]
            [uxbox.view.ui.viewer.nav :refer [nav]]
            [uxbox.view.ui.viewer.canvas :refer [canvas]]
            [uxbox.view.ui.viewer.sitemap :refer [sitemap]]))

;; --- Refs

(def flags-ref
  (-> (l/key :flags)
      (l/derive st/state)))

(def pages-ref
  (-> (l/key :pages)
      (l/derive st/state)))

;; --- Component

(defn- viewer-page-will-mount
  [own]
  (let [[token] (:rum/args own)]
    (st/emit! (dv/initialize token))
    own))

(defn- viewer-page-did-remount
  [oldown own]
  (let [[old-token] (:rum/args oldown)
        [new-token] (:rum/args own)]
    (when (not= old-token new-token)
      (st/emit! (dv/initialize old-token)))
    own))

(mx/defc viewer-page
  {:mixins [mx/static mx/reactive]
   :will-mount viewer-page-will-mount
   :did-remount viewer-page-did-remount}
  [token index]
  (let [flags (mx/react flags-ref)
        sitemap? (contains? flags :sitemap)
        get-order #(get-in % [:metadata :order])
        pages (mx/react pages-ref)]
    [:section.view-content
     (when sitemap?
       (sitemap pages index))
     (nav flags)
     (canvas (nth pages index))]))

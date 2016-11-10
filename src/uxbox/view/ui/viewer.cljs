;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.viewer
  (:require [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as rt]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.state :as st]
            [uxbox.view.data.viewer :as dv]
            [uxbox.view.ui.viewer.nav :refer (nav)]
            [uxbox.view.ui.viewer.canvas :refer (canvas)]
            [uxbox.view.ui.viewer.sitemap :refer (sitemap)]))

;; --- Refs

(def flags-ref
  (-> (l/key :flags)
      (l/derive st/state)))

(def token-ref
  (-> (l/in [:route :params :token])
      (l/derive st/state)))

;; --- Component

(defn- viewer-page-will-mount
  [own]
  (letfn [(on-change [token]
            (rs/emit! (dv/initialize token)))]
    (add-watch token-ref ::wkey #(on-change %4))
    (on-change @token-ref)
    own))

(defn- viewer-page-will-unmount
  [own]
  (remove-watch token-ref ::wkey)
  own)

(mx/defc viewer-page
  {:mixins [mx/static mx/reactive]
   :will-unmount viewer-page-will-unmount
   :will-mount viewer-page-will-mount}
  [own]
  (let [flags (mx/react flags-ref)
        sitemap? (contains? flags :sitemap)]
    [:section.view-content
     (when sitemap?
       (sitemap))
     (nav flags)
     (canvas)]))

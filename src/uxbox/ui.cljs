;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui
  (:require [sablono.core :as html :refer-macros [html]]
            [promesa.core :as p]
            [goog.dom :as gdom]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.state :as s]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.data.projects :as dp]
            [uxbox.data.auth :as uda]
            [uxbox.ui.lightbox :as ui-lightbox]
            [uxbox.ui.auth :as ui-auth]
            [uxbox.ui.dashboard :as ui-dashboard]
            [uxbox.ui.settings :as ui-settings]
            [uxbox.ui.workspace :refer (workspace)]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.shapes]))

(def ^:const auth-data
  (as-> (l/key :auth) $
    (l/focus-atom $ s/state)))

(def ^:const +unrestricted+
  #{:auth/login})

(def ^:const restricted?
  (complement +unrestricted+))

(defn app-render
  [own]
  (let [route (rum/react r/route-l)
        auth (rum/react auth-data)
        location (:id route)
        params (:params route)]
    (if (and (restricted? location) (not auth))
      (do (p/schedule 0 #(r/go :auth/login)) nil)
      (case location
        :auth/login (ui-auth/login)
        :dashboard/projects (ui-dashboard/projects-page)
        :dashboard/elements (ui-dashboard/elements-page)
        :dashboard/icons (ui-dashboard/icons-page)
        :dashboard/colors (ui-dashboard/colors-page)
        :settings/profile (ui-settings/profile-page)
        :settings/password (ui-settings/password-page)
        :settings/notifications (ui-settings/notifications-page)
        :workspace/page (let [projectid (:project-uuid params)
                              pageid (:page-uuid params)]
                          (workspace projectid pageid))
        nil
        ))))

(defn app-will-mount
  [own]
  (rs/emit! (uda/fetch-profile)
            (dp/fetch-projects))
  own)

(def app
  (mx/component
   {:render app-render
    :will-mount app-will-mount
    :mixins [rum/reactive]
    :name "app"}))

(defn init
  []
  (println "ui/init")
  (let [app-dom (gdom/getElement "app")
        lb-dom (gdom/getElement "lightbox")]
    (rum/mount (app) app-dom)
    (rum/mount (ui-lightbox/lightbox) lb-dom)))

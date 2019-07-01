;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [potok.core :as ptk]
            [uxbox.builtins.icons :as i]
            [uxbox.main.store :as st]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.data.users :as udu]
            [uxbox.main.data.auth :refer [logout]]
            [uxbox.main.ui.loader :refer [loader]]
            [uxbox.main.ui.lightbox :refer [lightbox]]
            [uxbox.main.ui.auth :as auth]
            [uxbox.main.ui.dashboard :as dashboard]
            [uxbox.main.ui.settings :as settings]
            [uxbox.main.ui.workspace :refer [workspace]]
            [uxbox.main.ui.shapes]
            [uxbox.util.messages :as uum]
            [uxbox.util.html-history :as html-history]
            [uxbox.util.router :as rt]
            [uxbox.util.timers :as ts]
            [uxbox.util.i18n :refer [tr]]
            [uxbox.util.data :refer [parse-int uuid-str?]]
            [uxbox.util.dom :as dom]
            [rumext.core :as mx :include-macros true]))

;; --- Constants

;; (def +unrestricted+
;;   #{:auth/login
;;     :auth/register
;;     :auth/recovery-request
;;     :auth/recovery})

;; (def restricted?
;;   (complement +unrestricted+))

(def route-ref
  (-> (l/key :route)
      (l/derive st/state)))

;; --- Main App (Component)

(defn app-will-mount
  [own]
  (when @st/auth-ref
    (st/emit! (udu/fetch-profile)))
  own)

(mx/defc app
  {:will-mount app-will-mount
   :mixins [mx/reactive]}
  []
  (let [route (mx/react route-ref)
        auth (mx/react st/auth-ref)]
    (prn "app" (:path route) (:params route))
    ;; (if (and (restricted? location) (not auth))
    ;; (do (ts/schedule 0 #(st/emit! (rt/navigate :auth/login))) nil)
    (case (get-in route [:data :name])
      :auth/login (auth/login-page)
      :auth/register (auth/register-page)
      :auth/recovery-request (auth/recovery-request-page)
      :auth/recovery (let [token (get-in route [:params :path :token])]
                       (auth/recovery-page token))
      :dashboard/projects (dashboard/projects-page)
      ;; ;; :dashboard/elements (dashboard/elements-page)

      :dashboard/icons (let [{:keys [id type]} (get-in route [:params :query ])
                             id (cond
                                  (str/digits? id) (parse-int id)
                                  (uuid-str? id) (uuid id)
                                   :else nil)
                             type (when (str/alpha? type) (keyword type))]
                         (dashboard/icons-page type id))

      ;; :dashboard/images (let [{:keys [id type]} params
      ;;                         type (when (str/alpha? type) (keyword type))
      ;;                         id (cond
      ;;                              (str/digits? id) (parse-int id)
      ;;                              (uuid-str? id) (uuid id)
      ;;                              :else nil)]
      ;;                     (dashboard/images-page type id))

      ;; :dashboard/colors (let [{:keys [id type]} params
      ;;                         type (when (str/alpha? type) (keyword type))
      ;;                         id (cond
      ;;                              (str/digits? id) (parse-int id)
      ;;                              (uuid-str? id) (uuid id)
      ;;                              :else nil)]
      ;;                     (dashboard/colors-page type id))
      ;; :settings/profile (settings/profile-page)
      ;; :settings/password (settings/password-page)
      ;; :settings/notifications (settings/notifications-page)
      :workspace/page (let [projectid (uuid (get-in route [:params :path :project]))
                            pageid (uuid (get-in route [:params :path :page]))]
                        (workspace projectid pageid))
      nil
      )))

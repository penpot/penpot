;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main
  (:require
   [hashp.core :include-macros true]
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [rumext.alpha :as mf]
   [app.common.uuid :as uuid]
   [app.main.data.auth :refer [logout]]
   [app.main.data.users :as udu]
   [app.main.store :as st]
   [app.main.ui :as ui]
   [app.main.ui.modal :refer [modal]]
   [app.main.worker]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n]
   [app.util.theme :as theme]
   [app.util.router :as rt]
   [app.util.object :as obj]
   [app.util.storage :refer [storage]]
   [app.util.timers :as ts]

   ;; MODALS
   [app.main.ui.settings.delete-account]
   [app.main.ui.settings.change-email]
   [app.main.ui.confirm]
   [app.main.ui.workspace.colorpicker]
   [app.main.ui.workspace.libraries]))

(declare reinit)

(defn on-navigate
  [router path]
  (let [match (rt/match router path)
        profile (:profile storage)
        authed? (and (not (nil? profile))
                     (not= (:id profile) uuid/zero))]
    (cond
      (and (or (= path "")
               (nil? match))
           (not authed?))
      (st/emit! (rt/nav :auth-login))

      (and (nil? match) authed?)
      (st/emit! (rt/nav :dashboard-projects {:team-id (:default-team-id profile)}))

      (nil? match)
      (st/emit! (rt/nav :not-found))

      :else
      (st/emit! #(assoc % :route match)))))

(defn init-ui
  []
  (st/emit! (rt/initialize-router ui/routes)
            (rt/initialize-history on-navigate))

  (st/emit! udu/fetch-profile)
  (mf/mount (mf/element ui/app-wrapper) (dom/get-element "app"))
  (mf/mount (mf/element modal) (dom/get-element "modal")))

(defn ^:export init
  []
  (let [translations (obj/get js/window "appTranslations")
        themes       (obj/get js/window "appThemes")]
    (i18n/init! translations)
    (theme/init! themes)
    (st/init)
    (init-ui)))

(defn reinit
  []
  (mf/unmount (dom/get-element "app"))
  (mf/unmount (dom/get-element "modal"))
  (init-ui))

(defn ^:dev/after-load after-load
  []
  (reinit))


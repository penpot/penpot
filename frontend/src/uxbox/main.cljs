;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [goog.object :as gobj]
   [rumext.alpha :as mf]
   [uxbox.common.uuid :as uuid]
   [uxbox.main.data.auth :refer [logout]]
   [uxbox.main.data.users :as udu]
   [uxbox.main.store :as st]
   [uxbox.main.ui :as ui]
   [uxbox.main.ui.modal :refer [modal]]
   [uxbox.main.worker]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n]
   [uxbox.util.theme :as theme]
   [uxbox.util.router :as rt]
   [uxbox.util.object :as obj]
   [uxbox.util.storage :refer [storage]]
   [uxbox.util.timers :as ts]))

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
      (st/emit! (rt/nav :dashboard-team {:team-id (:default-team-id profile)}))

      (nil? match)
      (st/emit! (rt/nav :not-found))

      :else
      (st/emit! #(assoc % :route match)))))

(defn init-ui
  []
  (st/emit! (rt/initialize-router ui/routes)
            (rt/initialize-history on-navigate))

  (st/emit! udu/fetch-profile)
  (mf/mount (mf/element ui/app) (dom/get-element "app"))
  (mf/mount (mf/element modal) (dom/get-element "modal")))

(defn ^:export init
  []
  (let [translations (obj/get js/window "uxboxTranslations")
        themes (gobj/get js/window "uxboxThemes")]
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


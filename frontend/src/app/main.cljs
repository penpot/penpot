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
   [app.common.uuid :as uuid]
   [app.common.spec :as us]
   [app.main.repo :as rp]
   [app.main.data.auth :refer [logout]]
   [app.main.data.users :as udu]
   [app.main.store :as st]
   [app.main.ui :as ui]
   [app.main.ui.confirm]
   [app.main.ui.modal :refer [modal]]
   [app.main.worker]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [app.util.storage :refer [storage]]
   [app.util.theme :as theme]
   [app.util.timers :as ts]
   [app.util.logging :as log]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(log/initialize!)
(log/set-level! :root :warn)
(log/set-level! :app :info)

(declare reinit)

(s/def ::any any?)

(defn match-path
  [router path]
  (when-let [match (rt/match router path)]
    (if-let [conform (get-in match [:data :conform])]
      (let [spath  (get conform :path-params ::any)
            squery (get conform :query-params ::any)]
        (-> (dissoc match :params)
            (assoc :path-params (us/conform spath (get match :path-params))
                   :query-params (us/conform squery (get match :query-params)))))
      match)))

(defn on-navigate
  [router path]
  (let [match   (match-path router path)
        profile (:profile storage)
        nopath? (or (= path "") (= path "/"))
        authed? (and (not (nil? profile))
                     (not= (:id profile) uuid/zero))]

    (cond
      (and nopath? authed? (nil? match))
      (->> (rp/query! :profile)
           (rx/subs (fn [profile]
                      (if (not= uuid/zero profile)
                        (st/emit! (rt/nav :dashboard-projects {:team-id (:default-team-id profile)}))
                        (st/emit! (rt/nav :auth-login))))))

      (and (not authed?) (nil? match))
      (st/emit! (rt/nav :auth-login))

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

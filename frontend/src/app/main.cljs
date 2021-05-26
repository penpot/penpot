;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.events :as ev]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui :as ui]
   [app.main.ui.confirm]
   [app.main.ui.modal :refer [modal]]
   [app.main.worker]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n]
   [app.util.logging :as log]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [app.util.storage :refer [storage]]
   [app.util.theme :as theme]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
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
        profile (:profile @storage)
        nopath? (or (= path "") (= path "/"))
        authed? (and (not (nil? profile))
                     (not= (:id profile) uuid/zero))]

    (cond
      (and nopath? authed? (nil? match))
      (if (not= uuid/zero profile)
        (st/emit! (rt/nav :dashboard-projects {:team-id (du/get-current-team-id profile)}))
        (st/emit! (rt/nav :auth-login)))

      (and (not authed?) (nil? match))
      (st/emit! (rt/nav :auth-login))

      (nil? match)
      (st/emit! (dm/assign-exception {:type :not-found}))

      :else
      (st/emit! (rt/navigated match)))))

(defn init-ui
  []
  (mf/mount (mf/element ui/app) (dom/get-element "app"))
  (mf/mount (mf/element modal)  (dom/get-element "modal")))


(defn initialize
  []
  (letfn [(on-profile [profile]
            (rx/of (rt/initialize-router ui/routes)
                   (rt/initialize-history on-navigate)))]
    (ptk/reify ::initialize
      ptk/UpdateEvent
      (update [_ state]
        (assoc state :session-id (uuid/next)))

      ptk/WatchEvent
      (watch [_ state stream]
        (rx/merge
         (rx/of
          (ptk/event ::ev/initialize)
          (du/initialize-profile))
         (->> stream
              (rx/filter (ptk/type? ::du/profile-fetched))
              (rx/take 1)
              (rx/map deref)
              (rx/mapcat on-profile)))))))

(defn ^:export init
  []
  (i18n/init! cfg/translations)
  (theme/init! cfg/themes)
  (init-ui)
  (st/emit! (initialize)))

(defn reinit
  []
  (mf/unmount (dom/get-element "app"))
  (mf/unmount (dom/get-element "modal"))
  (init-ui))

(add-watch i18n/locale "locale" (fn [_ _ o v]
                                  (when (not= o v)
                                    (reinit))))

(defn ^:dev/after-load after-load
  []
  (reinit))


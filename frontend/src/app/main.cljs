;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main
  (:require
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.auth :as da]
   [app.main.data.event :as ev]
   [app.main.data.profile :as dp]
   [app.main.data.websocket :as ws]
   [app.main.errors]
   [app.main.rasterizer :as thr]
   [app.main.store :as st]
   [app.main.ui :as ui]
   [app.main.ui.alert]
   [app.main.ui.confirm]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.delete-shared]
   [app.main.ui.routes :as rt]
   [app.main.worker :as mw]
   [app.plugins :as plugins]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [debug]
   [features]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(log/setup! {:app :info})
(log/set-level! :debug)

(when (= :browser cf/target)
  (log/inf :version (:full cf/version)
           :asserts *assert*
           :build-date cf/build-date
           :public-uri (dm/str cf/public-uri))
  (doseq [flag cf/flags]
    (log/dbg :hint "flag enabled" :flag (name flag))))

(declare reinit)

(defonce app-root
  (let [el (dom/get-element "app")]
    (mf/create-root el)))

(defn init-ui
  []
  (mf/render! app-root (mf/element ui/app)))

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :session-id (uuid/next)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (rx/of (ev/initialize)
              (dp/refresh-profile))

       ;; Watch for profile deletion events
       (->> stream
            (rx/filter dp/profile-deleted-event?)
            (rx/map da/logged-out))

       ;; Once profile is fetched, initialize all penpot application
       ;; routes
       (->> stream
            (rx/filter dp/profile-fetched?)
            (rx/take 1)
            (rx/map #(rt/init-routes)))

       ;; Once profile fetched and the current user is authenticated,
       ;; proceed to initialize the websockets connection.
       (->> stream
            (rx/filter dp/profile-fetched?)
            (rx/map deref)
            (rx/filter dp/is-authenticated?)
            (rx/take 1)
            (rx/map #(ws/initialize)))))))

(defn ^:export init
  []
  (mw/init!)
  (i18n/init! cf/translations)
  (cur/init-styles)
  (thr/init!)
  (init-ui)
  (st/emit! (plugins/initialize)
            (initialize)))

(defn ^:export reinit
  ([]
   (reinit false))
  ([hard?]
   ;; The hard flag will force to unmount the whole UI and will redraw every component
   (when hard?
     (mf/unmount! app-root)
     (set! app-root (mf/create-root (dom/get-element "app"))))
   (st/emit! (ev/initialize))
   (init-ui)))

(defn ^:dev/after-load after-load
  []
  (reinit))

;; Reload the UI when the language changes
(add-watch
 i18n/locale "locale"
 (fn [_ _ old-value current-value]
   (when (not= old-value current-value)
     (reinit))))

(set! (.-stackTraceLimit js/Error) 50)

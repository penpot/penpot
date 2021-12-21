;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main
  (:require
   [app.common.logging :as log]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.events :as ev]
   [app.main.data.users :as du]
   [app.main.errors]
   [app.main.sentry :as sentry]
   [app.main.store :as st]
   [app.main.ui :as ui]
   [app.main.ui.confirm]
   [app.main.ui.modal :refer [modal]]
   [app.main.ui.routes :as rt]
   [app.main.worker :as worker]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n]
   [app.util.theme :as theme]
   [beicon.core :as rx]
   [debug]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(log/initialize!)
(log/set-level! :root :warn)
(log/set-level! :app :info)

(when (= :browser @cf/target)
  (log/info :message "Welcome to penpot" :version (:full @cf/version) :public-uri (str cf/public-uri)))

(declare reinit)

(defn init-ui
  []
  (mf/mount (mf/element ui/app) (dom/get-element "app"))
  (mf/mount (mf/element modal)  (dom/get-element "modal")))

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :session-id (uuid/next)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (rx/of (ptk/event ::ev/initialize)
              (du/initialize-profile))
       (->> stream
            (rx/filter du/profile-fetched?)
            (rx/take 1)
            (rx/map #(rt/init-routes)))))))

(defn ^:export init
  []
  (worker/init!)
  (sentry/init!)
  (i18n/init! cf/translations)
  (theme/init! cf/themes)
  (init-ui)
  (st/emit! (initialize)))

(defn reinit
  []
  (mf/unmount (dom/get-element "app"))
  (mf/unmount (dom/get-element "modal"))
  (init-ui))

(defn ^:dev/after-load after-load
  []
  (reinit))

;; Reload the UI when the language changes
(add-watch
 i18n/locale "locale"
 (fn [_ _ old-value current-value]
   (when (not= old-value current-value)
     (reinit))))


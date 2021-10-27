;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.sentry
  "Sentry integration."
  (:require
   ["@sentry/browser" :as sentry]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.refs :as refs]))

(defn- setup-profile!
  [profile]
  (if (or (= uuid/zero (:id profile))
          (nil? profile))
    (sentry/setUser nil)
    (sentry/setUser #js {:id (str (:id profile))})))

(defn init!
  []
  (setup-profile! @refs/profile)
  (when cf/sentry-dsn
    (sentry/init
     #js {:dsn cf/sentry-dsn
          :autoSessionTracking false
          :attachStacktrace false
          :release (str "frontend@" (:base @cf/version))
          :maxBreadcrumbs 20
          :beforeBreadcrumb (fn [breadcrumb _hint]
                              (let [category (.-category ^js breadcrumb)]
                                (if (= category "navigate")
                                  breadcrumb
                                  nil)))
          :tracesSampleRate 1.0})

    (add-watch refs/profile ::profile
               (fn [_ _ _ profile]
                 (setup-profile! profile)))

    (add-watch refs/route ::route
               (fn [_ _ _ route]
                 (sentry/addBreadcrumb
                  #js {:category "navigate",
                       :message (str "path: " (:path route))
                       :level (.-Info ^js sentry/Severity)})))))

(defn capture-exception
  [err]
  (when cf/sentry-dsn
    (when (ex/ex-info? err)
      (sentry/setContext "ex-data", (clj->js (ex-data err))))
    (sentry/captureException err))
  err)




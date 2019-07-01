;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns ^:figwheel-hooks uxbox.main
  (:require
   [rumext.core :as mx :include-macros true]
   [uxbox.main.data.auth :refer [logout]]
   [uxbox.main.locales.en :as en]
   [uxbox.main.locales.fr :as fr]
   [uxbox.main.store :as st]
   [uxbox.main.ui :refer [app]]
   [uxbox.main.ui.lightbox :refer [lightbox]]
   [uxbox.main.ui.loader :refer [loader]]
   [uxbox.util.dom :as dom]
   [uxbox.util.html-history :as html-history]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.messages :as uum]
   [uxbox.util.router :as rt]
   [uxbox.util.timers :as ts]))

;; --- i18n

(declare reinit)

(i18n/update-locales! (fn [locales]
                        (-> locales
                            (assoc :en en/locales)
                            (assoc :fr fr/locales))))

(i18n/on-locale-change!
 (fn [new old]
   (println "Locale changed from" old " to " new)
   (reinit)))

;; --- Error Handling

(defn- on-error
  "A default error handler."
  [{:keys [status] :as error}]
  (js/console.error "on-error:" (pr-str error))
  (js/console.error (.-stack error))
  (reset! st/loader false)
  (cond
    ;; Unauthorized or Auth timeout
    (and (:status error)
         (or (= (:status error) 403)
             (= (:status error) 419)))
    (ts/schedule 100 #(st/emit! (logout)))

    ;; Conflict
    (= status 412)
    (ts/schedule 100 #(st/emit! (uum/error (tr "errors.conflict"))))

    ;; Network error
    (= (:status error) 0)
    (ts/schedule 100 #(st/emit! (uum/error (tr "errors.network"))))

    ;; Something else
    :else
    (ts/schedule 100 #(st/emit! (uum/error (tr "errors.generic"))))))

(set! st/*on-error* on-error)

(def routes
  [["/auth/login" :auth/login]
   ["/auth/register" :auth/register]
   ["/auth/recovery/request" :auth/recovery-request]
   ["/auth/recovery/token/:token" :auth/recovery]
   ["/settings/profile" :settings/profile]
   ["/settings/password" :settings/password]
   ["/settings/notifications" :settings/notifications]
   ["/dashboard/projects" :dashboard/projects]
   ["/dashboard/elements" :dashboard/elements]

   ["/dashboard/icons" :dashboard/icons]
   ;; ["/dashboard/icons/:type/:id" :dashboard/icons]
   ;; ["/dashboard/icons/:type" :dashboard/icons]

   ;; ["/dashboard/images" :dashboard/images]
   ;; ["/dashboard/images/:type/:id" :dashboard/images]
   ;; ["/dashboard/images/:type" :dashboard/images]

   ;; ["/dashboard/colors" :dashboard/colors]
   ;; ["/dashboard/colors/:type/:id" :dashboard/colors]
   ;; ["/dashboard/colors/:type" :dashboard/colors]

   ["/workspace/:project/:page" :workspace/page]])

(defn- on-navigate
  [router path]
  (let [match (rt/match router path)]
    (prn "on-navigate" path match)
    (cond
      (and (= path "") (nil? match))
      (html-history/set-path! "/dashboard/projects")

      (nil? match)
      (prn "TODO 404")

      :else
      (st/emit! #(assoc % :route match)))))

(defn init-store
  []
  (let [router (rt/init routes)
        cpath (deref html-history/path)]
    (st/init {:router router})
    (add-watch html-history/path ::main #(on-navigate router %4))
    (on-navigate router cpath)))

(defn init-ui
  []
  (mx/mount (app) (dom/get-element "app"))
  (mx/mount (lightbox) (dom/get-element "lightbox"))
  (mx/mount (loader) (dom/get-element "loader")))

(defn ^:export init
  []
  (init-store)
  (init-ui))

(defn reinit
  []
  (.unmountComponentAtNode js/ReactDOM (dom/get-element "app"))
  (.unmountComponentAtNode js/ReactDOM (dom/get-element "lightbox"))
  (.unmountComponentAtNode js/ReactDOM (dom/get-element "loader"))
  (init-ui))

(defn ^:after-load my-after-reload-callback []
  (reinit))






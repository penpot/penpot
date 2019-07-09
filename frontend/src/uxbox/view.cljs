;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns ^:figwheel-hooks uxbox.view
  (:require
   [rumext.core :as mx :include-macros true]
   [uxbox.config]
   [uxbox.util.dom :as dom]
   [uxbox.util.html.history :as html-history]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.messages :as uum]
   [uxbox.util.router :as rt]
   [uxbox.view.locales.en :as en]
   [uxbox.view.locales.fr :as fr]
   [uxbox.view.store :as st]
   [uxbox.view.ui :refer [app]]
   [uxbox.view.ui.lightbox :refer [lightbox]]
   [uxbox.view.ui.loader :refer [loader]]))

(i18n/update-locales! (fn [locales]
                        (-> locales
                            (assoc :en en/locales)
                            (assoc :fr fr/locales))))

(declare reinit)

(i18n/on-locale-change!
 (fn [new old]
   (println "Locale changed from" old " to " new)
   (reinit)))

(defn- on-error
  "A default error handler."
  [error]
  (cond
    ;; Network error
    (= (:status error) 0)
    (do
      (st/emit! (uum/error (tr "errors.network")))
      (js/console.error "Stack:" (.-stack error)))

    ;; Something else
    :else
    (do
      (st/emit! (uum/error (tr "errors.generic")))
      (js/console.error "Stack:" (.-stack error)))))

(set! st/*on-error* on-error)

;; --- Routes

(def routes
  [["/preview/:token/:index" :view/viewer]
   ["/not-found" :view/notfound]])

(defn- on-navigate
  [router path]
  (let [match (rt/match router path)]
    (prn "on-navigate" path match)
    (cond
      (and (= path "") (nil? match))
      (html-history/set-path! "/not-found")

      (nil? match)
      (prn "TODO 404")

      :else
      (st/emit! #(assoc % :route match)))))

(defn init-ui
  []
  (let [router (rt/init routes)
        cpath (deref html-history/path)]

    (st/emit! #(assoc % :router router))
    (add-watch html-history/path ::view #(on-navigate router %4))

    (mx/mount (app) (dom/get-element "app"))
    (mx/mount (lightbox) (dom/get-element "lightbox"))
    (mx/mount (loader) (dom/get-element "loader"))

    (on-navigate router cpath)))

(defn ^:export init
  []
  (st/init)
  (init-ui))

(defn reinit
  []
  (remove-watch html-history/path ::view)
  (.unmountComponentAtNode js/ReactDOM (dom/get-element "app"))
  (.unmountComponentAtNode js/ReactDOM (dom/get-element "lightbox"))
  (.unmountComponentAtNode js/ReactDOM (dom/get-element "loader"))
  (init-ui))

(defn ^:after-load after-load
  []
  (reinit))



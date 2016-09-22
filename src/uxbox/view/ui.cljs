;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.ui
  (:require [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as rt]
            [uxbox.util.mixins :as mx]
            [uxbox.util.dom :as dom]
            [uxbox.main.ui.loader :refer (loader)]
            [uxbox.main.ui.lightbox :refer (lightbox)]
            [uxbox.main.state :as st]
            [uxbox.main.data.messages :as dmsg]
            [uxbox.main.ui.icons :as i]
            [uxbox.view.ui.notfound :refer (notfound-page)]
            [uxbox.view.ui.viewer :refer (viewer-page)]))

(def route-id-ref
  (-> (l/in [:route :id])
      (l/derive st/state)))

(defn- on-error
  "A default error handler."
  [error]
  (cond
    ;; Network error
    (= (:status error) 0)
    (do
      (dmsg/error! (tr "errors.network"))
      (js/console.error "Stack:" (.-stack error)))

    ;; Something else
    :else
    (do
      (dmsg/error! (tr "errors.generic"))
      (js/console.error "Stack:" (.-stack error)))))

(rs/add-error-watcher :ui on-error)

;; --- Main App (Component)

(mx/defc app
  {:mixins [mx/static mx/reactive]}
  []
  (let [location (mx/react route-id-ref)]
    (case location
      :view/notfound (notfound-page)
      :view/viewer (viewer-page)
      nil)))

;; --- Routes

(def routes
  [["/:token/:id" :view/viewer]
   ["/:token" :view/viewer]
   ["/not-found" :view/notfound]])

;; --- Main Entry Point

(defn init-routes
  []
  (rt/init routes {:default :view/notfound}))

(defn init
  []
  (mx/mount (app) (dom/get-element "app"))
  (mx/mount (lightbox) (dom/get-element "lightbox"))
  (mx/mount (loader) (dom/get-element "loader")))

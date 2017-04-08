;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.ui
  (:require [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.builtins.icons :as i]
            [uxbox.view.store :as st]
            [uxbox.view.ui.loader :refer [loader]]
            [uxbox.view.ui.notfound :refer [notfound-page]]
            [uxbox.view.ui.viewer :refer [viewer-page]]
            [uxbox.util.router :as rt]
            [uxbox.util.i18n :refer [tr]]
            [uxbox.util.data :refer [parse-int]]
            [uxbox.util.messages :as uum]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.dom :as dom]))


(def route-ref
  (-> (l/key :route)
      (l/derive st/state)))

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

;; --- Main App (Component)

(mx/defc app
  {:mixins [mx/static mx/reactive]}
  []
  (let [{loc :id params :params}  (mx/react route-ref)]
    (case loc
      :view/notfound (notfound-page)
      :view/viewer (let [{:keys [index token]} params]
                     (viewer-page token (parse-int index 0)))
      nil)))

;; --- Routes

(def routes
  [["/:token/:index" :view/viewer]
   ["/:token" :view/viewer]
   ["/not-found" :view/notfound]])

;; --- Main Entry Point

(defn init-routes
  []
  (rt/init st/store routes {:default :view/notfound}))

(defn init
  []
  (mx/mount (app) (dom/get-element "app"))
  (mx/mount (loader) (dom/get-element "loader")))

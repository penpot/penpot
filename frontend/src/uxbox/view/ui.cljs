;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.ui
  (:require
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.core :as mx]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.util.data :refer [parse-int]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.messages :as uum]
   [uxbox.util.router :as rt]
   [uxbox.view.store :as st]
   [uxbox.view.ui.lightbox :refer [lightbox]]
   [uxbox.view.ui.loader :refer [loader]]
   [uxbox.view.ui.notfound :refer [notfound-page]]
   [uxbox.view.ui.viewer :refer [viewer-page]]))

;; --- Error Handling

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
  [["/preview/:token/:id" :view/viewer]
   ["/not-found" :view/notfound]])

;; --- Main App (Component)

(def route-ref
  (-> (l/key :route)
      (l/derive st/state)))

(mf/defc app
  []
  (let [route (mf/deref route-ref)]
    (case (get-in route [:data :name])
      :view/notfound (notfound-page)
      :view/viewer (let [{:keys [token id]} (get-in route [:params :path])]
                     (mf/element viewer-page {:token token
                                              :id (uuid id)
                                              :key [token id]}))
      nil)))


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
            [uxbox.view.ui.lightbox :refer [lightbox]]
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

;; --- Main App (Component)

(mx/defc app
  {:mixins [mx/static mx/reactive]}
  []
  (let [route (mx/react route-ref)]
    (prn "view$app" route)
    (case (get-in route [:data :name])
      :view/notfound (notfound-page)
      :view/viewer (let [{:keys [index token]} (get-in route [:params :path])]
                     (viewer-page token (parse-int index 0)))
      nil)))


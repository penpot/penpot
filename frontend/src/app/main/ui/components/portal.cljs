;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.portal
  (:require
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc portal-on-document*
  [{:keys [children]}]
  (let [container (mf/use-memo #(dom/create-element "div"))]
    (mf/with-effect []
      (let [body (dom/get-body)]
        (dom/append-child! body container)
        #(dom/remove-child! body container)))
    (mf/portal
     (mf/html [:* children])
     container)))

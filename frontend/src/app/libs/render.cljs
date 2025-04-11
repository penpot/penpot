;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.libs.render
  (:require
   [app.common.uuid :as uuid]
   [app.main.render :as r]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

(defn render-page-export
  [file ^string page-id]

  ;; Better to expose the api as a promise to be consumed from JS
  (let [page-id (uuid/parse page-id)
        file-data (.-file file)
        data (get-in file-data [:data :pages-index page-id])]
    (p/create
     (fn [resolve reject]
       (->> (r/render-page data)
            (rx/take 1)
            (rx/subs! resolve reject))))))

(defn exports []
  #js {:renderPage render-page-export})

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.renderer.pdf
  "A pdf renderer."
  (:require
   [app.browser :as bw]
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [promesa.core :as p]))

(defn render
  [{:keys [file-id page-id share-id token scale type objects] :as params} on-object]
  (letfn [(prepare-options [uri]
            #js {:screen #js {:width bw/default-viewport-width
                              :height bw/default-viewport-height}
                 :viewport #js {:width bw/default-viewport-width
                                :height bw/default-viewport-height}
                 :locale "en-US"
                 :storageState #js {:cookies (bw/create-cookies uri {:token token})}
                 :deviceScaleFactor scale
                 :userAgent bw/default-user-agent})

          (prepare-uri [base-uri object-id]
            (let [params {:file-id file-id
                          :page-id page-id
                          :share-id share-id
                          :object-id object-id
                          :route "objects"}]
              (-> base-uri
                  (assoc :path "/render.html")
                  (assoc :query (u/map->query-string params)))))

          (render-object [page base-uri {:keys [id] :as object}]
            (p/let [uri  (prepare-uri base-uri id)
                    path (sh/tempfile :prefix "penpot.tmp.pdf." :suffix (mime/get-extension type))]
              (l/info :uri uri)
              (bw/nav! page uri)
              (p/let [dom (bw/select page (dm/str "#screenshot-" id))]
                (bw/wait-for dom)
                (bw/screenshot dom {:full-page? true})
                (bw/sleep page 2000) ; the good old fix with sleep
                (bw/pdf page {:path path})
                path)))

          (render [base-uri page]
            (p/loop [objects (seq objects)]
              (when-let [object (first objects)]
                (p/let [path (render-object page base-uri object)]
                  (on-object (assoc object :path path))
                  (p/recur (rest objects))))))]

    (let [base-uri (cf/get :public-uri)]
      (bw/exec! (prepare-options base-uri)
                (partial render base-uri)))))

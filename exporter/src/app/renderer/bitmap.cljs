;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.renderer.bitmap
  "A bitmap renderer."
  (:require
   [app.browser :as bw]
   [app.common.logging :as l]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(defn render
  [{:keys [file-id page-id share-id token scale type objects skip-children] :as params} on-object]
  (letfn [(prepare-options [uri]
            #js {:screen #js {:width bw/default-viewport-width
                              :height bw/default-viewport-height}
                 :viewport #js {:width bw/default-viewport-width
                                :height bw/default-viewport-height}
                 :locale "en-US"
                 :storageState #js {:cookies (bw/create-cookies uri {:token token})}
                 :deviceScaleFactor scale
                 :userAgent bw/default-user-agent})

          (render-object [page {:keys [id] :as object}]
            (p/let [path (sh/tempfile :prefix "penpot.tmp.bitmap." :suffix (mime/get-extension type))
                    node (bw/select page (str/concat "#screenshot-" id))]
              (bw/wait-for node)
              (case type
                :png  (bw/screenshot node {:omit-background? true :type type :path path})
                :jpeg (bw/screenshot node {:omit-background? false :type type :path path})
                :webp (p/let [png-path (sh/tempfile :prefix "penpot.tmp.bitmap." :suffix ".png")]
                        ;; playwright only supports jpg and png, we need to convert it afterwards
                        (bw/screenshot node {:omit-background? true :type :png :path png-path})
                        (sh/run-cmd! (str "convert " png-path " -quality 100 WEBP:" path))))
              (on-object (assoc object :path path))))

          (render [uri page]
            (l/info :uri uri)
            (p/do
              ;; navigate to the page and perform basic setup
              (bw/nav! page (str uri))
              (bw/sleep page 1000) ; the good old fix with sleep
              (bw/eval! page (js* "() => document.body.style.background = 'transparent'"))

              ;; take the screnshot of requested objects, one by one
              (p/run (partial render-object page) objects)
              nil))]

    (p/let [params {:file-id file-id
                    :page-id page-id
                    :share-id share-id
                    :object-id (mapv :id objects)
                    :route "objects"
                    :skip-children skip-children}
            uri    (-> (cf/get :public-uri)
                       (assoc :path "/render.html")
                       (assoc :query (u/map->query-string params)))]
      (bw/exec! (prepare-options uri) (partial render uri)))))

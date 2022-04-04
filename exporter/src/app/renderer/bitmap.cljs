;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.renderer.bitmap
  "A bitmap renderer."
  (:require
   ["path" :as path]
   [app.browser :as bw]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(defn render
  [{:keys [file-id page-id token scale type uri objects] :as params} on-object]
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
            (p/let [tmpdir (sh/mktmpdir! "bitmap-render")
                    path   (path/join tmpdir (str/concat id (mime/get-extension type)))
                    node   (bw/select page (str/concat "#screenshot-" id))]
              (bw/wait-for node)
              (case type
                :png  (bw/screenshot node {:omit-background? true :type type :path path})
                :jpeg (bw/screenshot node {:omit-background? false :type type :path path}))
              (on-object (assoc object :path path))))

          (render [uri page]
            (l/info :uri uri)
            (p/do
              ;; navigate to the page and perform basic setup
              (bw/nav! page (str uri))
              (bw/sleep page 1000) ; the good old fix with sleep
              (bw/eval! page (js* "() => document.body.style.background = 'transparent'"))

              ;; take the screnshot of requested objects, one by one
              (p/run! (partial render-object page) objects)
              nil))]

    (p/let [params {:file-id file-id
                    :page-id page-id
                    :object-id (mapv :id objects)
                    :route "objects"}
            uri    (-> (or uri (cf/get :public-uri))
                       (assoc :path "/render.html")
                       (assoc :query (u/map->query-string params)))]
      (bw/exec! (prepare-options uri) (partial render uri)))))

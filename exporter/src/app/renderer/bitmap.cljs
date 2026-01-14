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
  [{:keys [file-id page-id share-id token scale type quality renderer objects skip-children] :as params} on-object]
  (letfn [(prepare-options [uri]
            #js {:screen #js {:width bw/default-viewport-width
                              :height bw/default-viewport-height}
                 :viewport #js {:width bw/default-viewport-width
                                :height bw/default-viewport-height}
                 :locale "en-US"
                 :storageState #js {:cookies (bw/create-cookies uri {:token token})}
                 :deviceScaleFactor scale
                 :userAgent bw/default-user-agent})

          (data-url->buffer [data-url]
            (if-let [[_ base64] (re-matches #"^data:.*;base64,(.*)$" data-url)]
              (js/Buffer.from base64 "base64")
              (js/Buffer.from data-url "base64")))

          (render-with-export-api [page {:keys [id]} target-type path]
            (let [method (case renderer
                           :rasterizer "rasterize"
                           :render-wasm "renderWasm")
                  payload #js {:method method
                               :objectId (str id)
                               :scale scale
                               :type (name target-type)
                               :quality quality
                               :skipChildren skip-children}
                  runner (js* "(payload) => { const api = globalThis.penpotExport; if (!api) { throw new Error('penpotExport not available'); } const fn = api[payload.method]; if (typeof fn !== 'function') { throw new Error(`renderer '${payload.method}' not available`); } return fn(payload); }")]
              (p/let [data-url (bw/eval-with-args! page runner payload)
                      buffer (data-url->buffer data-url)]
                (sh/write-file! path buffer))))

          (render-with-screenshot [node target-type path]
            (case target-type
              :png  (bw/screenshot node {:omit-background? true :type target-type :path path})
              :jpeg (bw/screenshot node {:omit-background? false :type target-type :quality quality :path path})))

          (render-with-fallback [page object node target-type path]
            (if (#{:rasterizer :render-wasm} renderer)
              (-> (render-with-export-api page object target-type path)
                  (p/catch (fn [cause]
                             (l/error :hint "renderer failed, falling back to screenshot"
                                      :renderer renderer
                                      :object-id (:id object)
                                      :cause cause)
                             (render-with-screenshot node target-type path))))
              (render-with-screenshot node target-type path)))

          (render-object [page {:keys [id] :as object}]
            (let [target-type (if (= type :webp) :png type)]
              (p/let [path (sh/tempfile :prefix "penpot.tmp.bitmap." :suffix (mime/get-extension target-type))
                      node (bw/select page (str/concat "#screenshot-" id))]
              (bw/wait-for node)
              (p/let [_ (render-with-fallback page object node target-type path)
                      final-path (if (= type :webp)
                                   (p/let [webp-path (sh/tempfile :prefix "penpot.tmp.bitmap." :suffix ".webp")]
                                     (sh/run-cmd! (str "convert " path " -quality 100 WEBP:" webp-path))
                                     webp-path)
                                   path)]
                (on-object (assoc object :path final-path))))))

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

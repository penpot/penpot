;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.renderer.pdf
  "A pdf renderer."
  (:require
   [app.browser :as bw]
   [app.common.exceptions :as ex :include-macros true]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.config :as cf]
   [cljs.spec.alpha :as s]
   [promesa.core :as p]))

(defn pdf-from-object
  [{:keys [file-id page-id object-id token scale type save-path uri] :as params}]
  (p/let [params {:file-id file-id
                  :page-id page-id
                  :object-id object-id
                  :route "render-object"}
          uri    (-> (or uri (cf/get :public-uri))
                     (assoc :path "/render.html")
                     (assoc :query (u/map->query-string params)))]

    (bw/exec!
     #js {:screen #js {:width bw/default-viewport-width
                       :height bw/default-viewport-height}
          :viewport #js {:width bw/default-viewport-width
                         :height bw/default-viewport-height}
          :locale "en-US"
          :storageState #js {:cookies (bw/create-cookies uri {:token token})}
          :deviceScaleFactor scale
          :userAgent bw/default-user-agent}
     (fn [page]
       (l/info :uri uri)
       (p/do!
        (bw/nav! page uri)
        (p/let [dom (bw/select page "#screenshot")]
          (bw/wait-for dom)
          (bw/screenshot dom {:full-page? true})
          (bw/sleep page 2000) ; the good old fix with sleep
          (if save-path
            (bw/pdf page {:save-path save-path})
            (bw/pdf page))))))))

(s/def ::name ::us/string)
(s/def ::suffix ::us/string)
(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::token ::us/string)
(s/def ::save-path ::us/string)
(s/def ::uri ::us/uri)

(s/def ::render-params
  (s/keys :req-un [::name ::suffix ::object-id ::page-id ::scale ::token ::file-id]
          :opt-un [::save-path ::uri]))

(defn render
  [params]
  (us/assert ::render-params params)
  (p/let [content (pdf-from-object params)]
    {:data content
     :name (str (:name params)
                (:suffix params "")
                ".pdf")
     :size (alength content)
     :mtype "application/pdf"}))


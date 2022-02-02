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
   [app.config :as cf]
   [cljs.spec.alpha :as s]
   [lambdaisland.uri :as u]
   [promesa.core :as p]))

(defn create-cookie
  [uri token]
  (let [domain (str (:host uri)
                (when (:port uri)
                  (str ":" (:port uri))))]
    {:domain domain
     :key "auth-token"
     :value token}))

(defn pdf-from-object
  [{:keys [file-id page-id object-id token scale type save-path]}]
  (letfn [(handle [page]
            (let [path   (str "/render-object/" file-id "/" page-id "/" object-id)
                  uri    (-> (u/uri (cf/get :public-uri))
                             (assoc :path "/")
                             (assoc :query "essential=t")
                             (assoc :fragment path))

                  cookie (create-cookie uri token)]
              (pdf-from page (str uri) cookie)))

          (pdf-from [page uri cookie]
            (l/info :uri uri)
            (p/let [options {:cookie cookie}]
              (bw/configure-page! page options)
              (bw/navigate! page uri)
              (bw/wait-for page "#screenshot")
              ;; taking png screenshot before pdf, helps to make the
              ;; pdf rendering works as expected.
              (p/let [dom (bw/select page "#screenshot")]
                (bw/screenshot dom {:full-page? true}))

              (if save-path
                (bw/pdf page {:save-path save-path})
                (bw/pdf page))))]

    (bw/exec! handle)))

(s/def ::name ::us/string)
(s/def ::suffix ::us/string)
(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::token ::us/string)
(s/def ::filename ::us/string)
(s/def ::save-path ::us/string)

(s/def ::render-params
  (s/keys :req-un [::name ::suffix ::object-id ::page-id ::scale ::token ::file-id]
          :opt-un [::filename ::save-path]))

(defn render
  [params]
  (us/assert ::render-params params)
  (p/let [content (pdf-from-object params)]
    {:content content
     :filename (or (:filename params)
                   (str (:name params)
                        (:suffix params "")
                        ".pdf"))
     :length (alength content)
     :mime-type "application/pdf"}))


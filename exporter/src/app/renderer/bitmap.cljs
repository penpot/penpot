;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.renderer.bitmap
  "A bitmap renderer."
  (:require
   [app.browser :as bw]
   [app.common.data :as d]
   [app.common.exceptions :as ex :include-macros true]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.config :as cf]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lambdaisland.uri :as u]
   [lambdaisland.glogi :as log]
   [promesa.core :as p]))

(defn create-cookie
  [uri token]
  (let [domain (str (:host uri)
                (when (:port uri)
                  (str ":" (:port uri))))]
    {:domain domain
     :key "auth-token"
     :value token}))

(defn screenshot-object
  [browser {:keys [file-id page-id object-id token scale type]}]
  (letfn [(handle [page]
            (let [path   (str "/render-object/" file-id "/" page-id "/" object-id)
                  uri    (-> (u/uri (cf/get :public-uri))
                             (assoc :path "/")
                             (assoc :fragment path))
                  cookie (create-cookie uri token)]
              (screenshot page (str uri) cookie)))

          (screenshot [page uri cookie]
            (log/info :uri uri)
            (p/do!
             (bw/emulate! page {:viewport [1920 1080]
                                 :scale scale})
             (bw/set-cookie! page cookie)
             (bw/navigate! page uri)
             (bw/eval! page (js* "() => document.body.style.background = 'transparent'"))
             (p/let [dom (bw/select page "#screenshot")]
               (case type
                 :png  (bw/screenshot dom {:omit-background? true :type type})
                 :jpeg (bw/screenshot dom {:omit-background? false :type type})))))]

    (bw/exec! browser handle)))

(s/def ::name ::us/string)
(s/def ::suffix ::us/string)
(s/def ::type #{:jpeg :png})
(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::token ::us/string)
(s/def ::filename ::us/string)

(s/def ::render-params
  (s/keys :req-un [::name ::suffix ::type ::object-id ::page-id ::scale ::token ::file-id]
          :opt-un [::filename]))

(defn render
  [params]
  (us/assert ::render-params params)
  (let [browser @bw/instance]
    (when-not browser
      (ex/raise :type :internal
                :code :browser-not-ready
                :hint "browser cluster is not initialized yet"))

    (p/let [content (screenshot-object browser params)]
      {:content content
       :filename (or (:filename params)
                     (str (:name params)
                          (:suffix params "")
                          (case (:type params)
                            :png ".png"
                            :jpeg ".jpg")))
       :length (alength content)
       :mime-type (case (:type params)
                    :png "image/png"
                    :jpeg "image/jpeg")})))


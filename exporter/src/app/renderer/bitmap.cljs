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
   [app.common.logging :as l]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.config :as cf]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(defn screenshot-object
  [{:keys [file-id page-id object-id token scale type uri]}]
  (p/let [path (str "/render-object/" file-id "/" page-id "/" object-id)
          uri  (-> (or uri (cf/get :public-uri))
                   (assoc :path "/")
                   (assoc :fragment path))]
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
        (bw/nav! page (str uri))
        (p/let [node (bw/select page "#screenshot")]
          (bw/wait-for node)
          (bw/eval! page (js* "() => document.body.style.background = 'transparent'"))
          (bw/sleep page 2000) ; the good old fix with sleep
          (case type
            :png  (bw/screenshot node {:omit-background? true :type type})
            :jpeg (bw/screenshot node {:omit-background? false :type type}))))))))

(s/def ::name ::us/string)
(s/def ::suffix ::us/string)
(s/def ::type #{:jpeg :png})
(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::token ::us/string)
(s/def ::origin ::us/string)
(s/def ::uri ::us/uri)

(s/def ::params
  (s/keys :req-un [::name ::suffix ::type ::object-id ::page-id ::scale ::token ::file-id]
          :opt-un [::origin ::uri]))

(defn render
  [params]
  (us/verify ::params params)
  (when (and (:origin params)
             (not (contains? (cf/get :origin-white-list) (:origin params))))
    (ex/raise :type :validation
              :code :invalid-origin
              :hint "invalid origin"
              :origin (:origin params)))

  (p/let [content (screenshot-object params)]
    {:data content
     :name (str (:name params)
                (:suffix params "")
                (case (:type params)
                  :png ".png"
                  :jpeg ".jpg"))
     :size (alength content)
     :mtype (case (:type params)
              :png "image/png"
              :jpeg "image/jpeg")}))


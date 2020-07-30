(ns app.http.export-bitmap
  (:require
   [cuerdas.core :as str]
   [app.browser :as bwr]
   [app.config :as cfg]
   [lambdaisland.glogi :as log]
   [cljs.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.exceptions :as exc :include-macros true]
   [uxbox.common.data :as d]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us])
  (:import
   goog.Uri))

(defn- screenshot-object
  [browser {:keys [page-id object-id token scale suffix type]}]
  (letfn [(handle [page]
            (let [path   (str "/render-object/" page-id "/" object-id)
                  uri    (doto (Uri. (:public-uri cfg/config))
                           (.setPath "/")
                           (.setFragment path))
                  cookie {:domain (str (.getDomain uri)
                                       ":"
                                       (.getPort uri))
                          :key "auth-token"
                          :value token}]
              (log/info :uri (.toString uri))
              (screenshot page (.toString uri) cookie)))

          (screenshot [page uri cookie]
            (p/do!
             (bwr/emulate! page {:viewport [1920 1080]
                                 :scale scale})
             (bwr/set-cookie! page cookie)
             (bwr/navigate! page uri)
             (bwr/eval! page (js* "() => document.body.style.background = 'transparent'"))
             (p/let [dom (bwr/select page "#screenshot")]
               (case type
                 :png  (bwr/screenshot dom {:omit-background? true :type type})
                 :jpeg (bwr/screenshot dom {:omit-background? false :type type})))))]

    (bwr/exec! browser handle)))

(s/def ::name ::us/string)
(s/def ::suffix ::us/string)
(s/def ::type #{:jpeg :png})
(s/def ::page-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::token ::us/string)
(s/def ::filename ::us/string)

(s/def ::export-params
  (s/keys :req-un [::name ::suffix ::type ::object-id ::page-id ::scale ::token]
          :opt-un [::filename]))

(defn export
  [browser params]
  (us/assert ::export-params params)
  (p/let [content (screenshot-object browser params)]
    {:content content
     :filename (or (:filename params)
                   (str (str/slug (:name params))
                        (str/trim (:suffix params ""))
                        (case (:type params)
                          :png ".png"
                          :jpeg ".jpg")))
     :length (alength content)
     :mime-type (case (:type params)
                  :png "image/png"
                  :jpeg "image/jpeg")}))


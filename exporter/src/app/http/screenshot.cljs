(ns app.http.screenshot
  (:require
   [app.browser :as bwr]
   [app.config :as cfg]
   [lambdaisland.glogi :as log]
   [cljs.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.exceptions :as exc :include-macros true]
   [uxbox.common.spec :as us])
  (:import
   goog.Uri))

(defn- load-and-screenshot
  [page url cookie]
  (p/do!
   (bwr/emulate! page {:viewport [1920 1080]})
   (bwr/set-cookie! page cookie)
   (bwr/navigate! page url)
   (bwr/sleep page 500)
   (.evaluate page (js* "() => document.body.style.background = 'transparent'"))
   ;; (.screenshot ^js page #js {:omitBackground true :fullPage true})
   (p/let [dom (.$ page "#screenshot")]
     (.screenshot ^js dom #js {:omitBackground true}))))

(defn- take-screenshot
  [browser {:keys [page-id object-id token]}]
  (letfn [(on-browser [page]
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
              (load-and-screenshot page (.toString uri) cookie)))]
    (bwr/exec! browser on-browser)))

(s/def ::page-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::bitmap-handler-params
  (s/keys :req-un [::page-id ::object-id]))

(defn bitmap-handler
  [{:keys [params browser cookies] :as request}]
  (let [params (us/conform ::bitmap-handler-params (:query params))
        token  (.get ^js cookies "auth-token")]
    (-> (take-screenshot browser {:page-id (:page-id params)
                                  :object-id (:object-id params)
                                  :token token})
        (p/then (fn [result]
                  {:status 200
                   :body result
                   :headers {"content-type" "image/png"
                             "content-length" (alength result)}})))))

(defn page-handler
  [{:keys [params browser] :as request}]
  (letfn [(screenshot [page uri]
            (p/do!
             (bwr/emulate! page {:viewport [1920 1080]})
             (bwr/navigate! page uri)
             (bwr/sleep page 500)
             ;; (.evaluate page (js* "() => document.body.style.background = 'transparent'"))
             (.screenshot ^js page #js {:omitBackground false})))]
    (p/let [uri (get-in params [:query :uri])
            sht (bwr/exec! browser #(screenshot % uri))]
      {:status 200
       :body sht
       :headers {"content-type" "image/png"
                 "content-length" (alength sht)}})))

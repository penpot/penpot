(ns app.http.screenshot
  (:require
   [app.browser :as bwr]
   [app.config :as cfg]
   [cljs.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.exceptions :as exc :include-macros true]
   [uxbox.common.spec :as us]))

(defn- load-and-screenshot
  [page url cookie]
  (p/do!
   (bwr/emulate! page {:viewport [1920 1080]})
   (bwr/set-cookie! page cookie)
   (bwr/navigate! page url)
   (bwr/sleep page 500)
   (.evaluate page (js* "() => document.body.style.background = 'transparent'"))
   (p/let [dom (.$ page "#screenshot")]
     (.screenshot ^js dom #js {:omitBackground true}))))

(defn- take-screenshot
  [browser {:keys [page-id object-id token]}]
  (letfn [(on-browser [page]
            (let [url    (str "http://" (:domain cfg/config)
                              "/#/render-object/"
                              page-id "/" object-id)
                  cookie {:domain (:domain cfg/config)
                          :key "auth-token"
                          :value token}]
              (load-and-screenshot page url cookie)))]
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


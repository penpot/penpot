(ns app.http.screenshot
  (:require
   [app.browser :as bwr]
   [app.config :as cfg]
   [promesa.core :as p]))

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

(defn bitmap-handler
  [{:keys [params browser cookies] :as request}]
  (let [page-id   (get-in params [:query :page-id])
        object-id (get-in params [:query :object-id])
        token     (.get ^js cookies "auth-token")]
    (-> (take-screenshot browser {:page-id page-id
                                  :object-id object-id
                                  :token token})
        (p/then (fn [result]
                  {:status 200
                   :body result
                   :headers {"content-type" "image/png"
                             "content-length" (alength result)}})))))


(ns app.browser
  (:require
   [lambdaisland.glogi :as log]
   [promesa.core :as p]
   ["puppeteer-cluster" :as ppc]))

(def USER-AGENT
  (str "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36"))

(defn exec!
  [browser f]
  (.execute ^js browser (fn [props]
                          (let [page (unchecked-get props "page")]
                            (f page)))))

(defn emulate!
  [page {:keys [viewport user-agent]
         :or {user-agent USER-AGENT}}]
  (let [[width height] viewport]
    (.emulate page #js {:viewport #js {:width width
                                       :height height}
                        :userAgent user-agent})))

(defn navigate!
  ([page url] (navigate! page url nil))
  ([page url {:keys [wait-until]
              :or {wait-until "networkidle2"}}]
   (.goto ^js page url #js {:waitUntil wait-until})))

(defn sleep
  [page ms]
  (.waitFor ^js page ms))

(defn screenshot
  ([page] (screenshot page nil))
  ([page {:keys [full-page?]
          :or {full-page? true}}]
   (.screenshot ^js page #js {:fullPage full-page? :omitBackground true})))

(defn set-cookie!
  [page {:keys [key value domain]}]
  (.setCookie ^js page #js {:name key
                            :value value
                            :domain domain}))

(defn start!
  ([] (start! nil))
  ([{:keys [concurrency concurrency-strategy]
     :or {concurrency 2
          concurrency-strategy :browser}}]
   (let [ccst (case concurrency-strategy
                :browser (.-CONCURRENCY_BROWSER ^js ppc/Cluster)
                :incognito (.-CONCURRENCY_CONTEXT ^js ppc/Cluster)
                :page (.-CONCURRENCY_PAGE ^js ppc/Cluster))
         opts #js {:concurrency ccst
                   :maxConcurrency concurrency}]
     (.launch ^js ppc/Cluster opts))))

(defn stop!
  [instance]
  (p/do!
   (.idle ^js instance)
   (.close ^js instance)
   (log/info :msg "shutdown headless browser")
   nil))

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
  [page {:keys [viewport user-agent scale]
         :or {user-agent USER-AGENT
              scale 1}}]
  (let [[width height] viewport]
    (.emulate page #js {:viewport #js {:width width
                                       :height height
                                       :deviceScaleFactor scale}
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
  ([frame] (screenshot frame nil))
  ([frame {:keys [full-page? omit-background?]
           :or {full-page? false
                omit-background? false}}]
   (.screenshot ^js frame #js {:fullPage full-page?
                               :omitBackground omit-background?})))

(defn eval!
  [frame f]
  (.evaluate ^js frame f))

(defn select
  [frame selector]
  (.$ ^js frame selector))

(defn set-cookie!
  [page {:keys [key value domain]}]
  (.setCookie ^js page #js {:name key
                            :value value
                            :domain domain}))

(defn start!
  ([] (start! nil))
  ([{:keys [concurrency concurrency-strategy]
     :or {concurrency 10
          concurrency-strategy :incognito}}]
   (let [ccst (case concurrency-strategy
                :browser (.-CONCURRENCY_BROWSER ^js ppc/Cluster)
                :incognito (.-CONCURRENCY_CONTEXT ^js ppc/Cluster)
                :page (.-CONCURRENCY_PAGE ^js ppc/Cluster))
         opts #js {:concurrency ccst
                   :maxConcurrency concurrency
                   :puppeteerOptions #js {:args #js ["--no-sandbox"]}}]
     (.launch ^js ppc/Cluster opts))))

(defn stop!
  [instance]
  (p/do!
   (.idle ^js instance)
   (.close ^js instance)
   (log/info :msg "shutdown headless browser")
   nil))

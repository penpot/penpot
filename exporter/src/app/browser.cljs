;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.browser
  (:require
   [app.config :as cf]
   [lambdaisland.glogi :as log]
   [promesa.core :as p]
   ["puppeteer-cluster" :as ppc]))

;; --- BROWSER API

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
    (.emulate ^js page #js {:viewport #js {:width width
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
  (.waitForTimeout ^js page ms))


(defn wait-for
  ([page selector] (wait-for page selector nil))
  ([page selector {:keys [visible] :or {visible false}}]
   (.waitForSelector ^js page selector #js {:visible visible})))

(defn screenshot
  ([frame] (screenshot frame nil))
  ([frame {:keys [full-page? omit-background? type]
           :or {full-page? false
                type "png"
                omit-background? false}}]
   (.screenshot ^js frame #js {:fullPage full-page?
                               :type (name type)
                               :omitBackground omit-background?})))

(defn eval!
  [frame f]
  (.evaluate ^js frame f))

(defn select
  [frame selector]
  (.$ ^js frame selector))

(defn select-all
  [frame selector]
  (.$$ ^js frame selector))

(defn set-cookie!
  [page {:keys [key value domain]}]
  (.setCookie ^js page #js {:name key
                            :value value
                            :domain domain}))

;; --- BROWSER STATE

(def instance (atom nil))

(defn- create-browser
  [concurrency strategy]
  (let [strategy (case strategy
                   :browser (.-CONCURRENCY_BROWSER ^js ppc/Cluster)
                   :incognito (.-CONCURRENCY_CONTEXT ^js ppc/Cluster)
                   :page (.-CONCURRENCY_PAGE ^js ppc/Cluster))
        opts #js {:concurrency strategy
                  :maxConcurrency concurrency
                  :puppeteerOptions #js {:args #js ["--no-sandbox"]}}]
    (.launch ^js ppc/Cluster opts)))


(defn init
  []
  (let [concurrency (cf/get :browser-concurrency)
        strategy    (cf/get :browser-strategy)]
    (-> (create-browser concurrency strategy)
        (p/then #(reset! instance %))
        (p/catch (fn [error]
                   (log/error :msg "failed to initialize browser")
                   (js/console.error error))))))


(defn stop
  []
  (if-let [instance @instance]
    (p/do!
     (.idle ^js instance)
     (.close ^js instance)
     (log/info :msg "shutdown headless browser"))
    (p/resolved nil)))

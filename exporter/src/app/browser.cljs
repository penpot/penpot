;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.browser
  (:require
   ["puppeteer-core" :as pp]
   ["generic-pool" :as gp]
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [lambdaisland.glogi :as log]
   [promesa.core :as p]))

;; --- BROWSER API

(def default-timeout 30000)
(def default-viewport {:width 1920 :height 1080 :scale 1})
(def default-user-agent
  (str "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36"))

(defn set-cookie!
  [page {:keys [key value domain]}]
  (.setCookie ^js page #js {:name key
                            :value value
                            :domain domain}))

(defn configure-page!
  [page {:keys [timeout cookie user-agent viewport]}]
  (let [timeout    (or timeout default-timeout)
        user-agent (or user-agent default-user-agent)
        viewport   (d/merge default-viewport viewport)]
    (p/do!
     (.setViewport ^js page #js {:width (:width viewport)
                                 :height (:height viewport)
                                 :deviceScaleFactor (:scale viewport)})
     (.setUserAgent ^js page user-agent)
     (.setDefaultTimeout ^js page timeout)
     (when cookie
       (set-cookie! page cookie)))))

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
  ([page selector {:keys [visible timeout] :or {visible false timeout 10000}}]
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

(defn pdf
  ([page] (pdf page nil))
  ([page {:keys [viewport omit-background? prefer-css-page-size? save-path]
          :or {viewport {}
               omit-background? true
               prefer-css-page-size? true
               save-path nil}}]
   (let [viewport (d/merge default-viewport viewport)]
     (.pdf ^js page #js {:path save-path
                         :width (:width viewport)
                         :height (:height viewport)
                         :scale (:scale viewport)
                         :omitBackground omit-background?
                         :printBackground (not omit-background?)
                         :preferCSSPageSize prefer-css-page-size?}))))

(defn eval!
  [frame f]
  (.evaluate ^js frame f))

(defn select
  [frame selector]
  (.$ ^js frame selector))

(defn select-all
  [frame selector]
  (.$$ ^js frame selector))


;; --- BROWSER STATE

(defonce pool (atom nil))
(defonce pool-browser-id (atom 1))

(def browser-pool-factory
  (letfn [(create []
            (let [path (cf/get :browser-executable-path "/usr/bin/google-chrome")]
              (-> (pp/launch #js {:executablePath path :args #js ["--no-sandbox"]})
                  (p/then (fn [browser]
                            (let [id (deref pool-browser-id)]
                              (log/info :origin "factory" :action "create" :browser-id id)
                              (unchecked-set browser "__id" id)
                              (swap! pool-browser-id inc)
                              browser))))))
          (destroy [obj]
            (let [id (unchecked-get obj "__id")]
              (log/info :origin "factory" :action "destroy" :browser-id id)
              (.close ^js obj)))

          (validate [obj]
            (let [id (unchecked-get obj "__id")]
              (log/info :origin "factory" :action "validate" :browser-id id :obj obj)
              (p/resolved (.isConnected ^js obj))))]

    #js {:create create
         :destroy destroy
         :validate validate}))

(defn init
  []
  (log/info :msg "initializing browser pool")
  (let [opts #js {:max (cf/get :browser-pool-max 3)
                  :min (cf/get :browser-pool-min 0)
                  :testOnBorrow true
                  :evictionRunIntervalMillis 5000
                  :numTestsPerEvictionRun 5
                  :acquireTimeoutMillis 120000 ; 2min
                  :idleTimeoutMillis 10000}]

    (reset! pool (gp/createPool browser-pool-factory opts))
    (p/resolved nil)))

(defn stop
  []
  (when-let [pool (deref pool)]
    (log/info :msg "finalizing browser pool")
    (-> (.drain ^js pool)
        (p/then (fn [] (.clear ^js pool))))))

(defn exec!
  [callback]
  (letfn [(on-release [pool browser ctx result error]
            (-> (p/do! (.close ^js ctx))
                (p/handle
                 (fn [_ _]
                   (.release ^js pool browser)))
                (p/handle
                 (fn [_ _]
                   (let [id (unchecked-get browser "__id")]
                     (log/info :origin "exec" :action "release" :browser-id id))
                   (if result
                     (p/resolved result)
                     (p/rejected error))))))

          (on-context [pool browser ctx]
            (-> (p/do! (.newPage ^js ctx))
                (p/then callback)
                (p/handle #(on-release pool browser ctx %1 %2))))

          (on-acquire [pool browser]
            (-> (.createIncognitoBrowserContext ^js browser)
                (p/then #(on-context pool browser %))))]

    (when-let [pool (deref pool)]
      (-> (p/do! (.acquire ^js pool))
          (p/then (partial on-acquire pool))))))

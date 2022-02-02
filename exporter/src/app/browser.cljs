;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.browser
  (:require
   ["generic-pool" :as gp]
   ["puppeteer-core" :as pp]
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.util.object :as obj]
   [promesa.core :as p]))

(l/set-level! :trace)

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
        viewport   (merge default-viewport viewport)]

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
           :or {type "png"
                full-page? false
                omit-background? false}}]
   (let [options (-> (obj/new)
                     (obj/set! "type" (name type))
                     (obj/set! "omitBackground" omit-background?)
                     (cond-> full-page? (-> (obj/set! "fullPage" true)
                                            (obj/set! "clip" nil))))]
     (.screenshot ^js frame options))))

(defn pdf
  ([page] (pdf page nil))
  ([page {:keys [viewport save-path]}]
   (p/let [viewport (d/merge default-viewport viewport)]
     (.emulateMediaType ^js page "screen")
     (.pdf ^js page #js {:path save-path
                         :width (:width viewport)
                         :height (:height viewport)
                         :scale (:scale viewport)
                         :printBackground true
                         :preferCSSPageSize true}))))
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

(def default-chrome-args
  #js ["--no-sandbox"
       "--font-render-hinting=none"
       "--disable-setuid-sandbox"
       "--disable-accelerated-2d-canvas"
       "--disable-gpu"])

(def browser-pool-factory
  (letfn [(create []
            (let [path (cf/get :browser-executable-path "/usr/bin/google-chrome")]
              (-> (pp/launch #js {:executablePath path :args default-chrome-args})
                  (p/then (fn [browser]
                            (let [id (deref pool-browser-id)]
                              (l/info :origin "factory" :action "create" :browser-id id)
                              (unchecked-set browser "__id" id)
                              (swap! pool-browser-id inc)
                              browser))))))
          (destroy [obj]
            (let [id (unchecked-get obj "__id")]
              (l/info :origin "factory" :action "destroy" :browser-id id)
              (.close ^js obj)))

          (validate [obj]
            (let [id (unchecked-get obj "__id")]
              (l/info :origin "factory" :action "validate" :browser-id id :obj obj)
              (p/resolved (.isConnected ^js obj))))]

    #js {:create create
         :destroy destroy
         :validate validate}))

(defn init
  []
  (l/info :msg "initializing browser pool")
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
    (l/info :msg "finalizing browser pool")
    (-> (.drain ^js pool)
        (p/then (fn [] (.clear ^js pool))))))

(defn exec!
  [callback]
  (letfn [(release-browser [pool browser]
            (let [id (unchecked-get browser "__id")]
              (-> (p/do! (.release ^js pool browser))
                  (p/handle (fn [res err]
                              (l/trace :action "exec:release-browser" :browser-id id)
                              (when err (js/console.log err))
                              (if err
                                (p/rejected err)
                                (p/resolved res)))))))

          (destroy-browser [pool browser]
            (let [id (unchecked-get browser "__id")]
              (-> (p/do! (.destroy ^js pool browser))
                  (p/handle (fn [res err]
                              (l/trace :action "exec:destroy-browser" :browser-id id)
                              (when err (js/console.log err))
                              (if err
                                (p/rejected err)
                                (p/resolved res)))))))

          (handle-error [pool browser obj err]
            (let [id (unchecked-get browser "__id")]
              (if err
                (do
                  (l/trace :action "exec:handle-error" :browser-id id)
                  (-> (p/do! (destroy-browser pool browser))
                      (p/handle #(p/rejected err))))
                (p/resolved obj))))

          (on-result [pool browser context result]
            (let [id (unchecked-get browser "__id")]
              (l/trace :action "exec:on-result" :browser-id id)
              (-> (p/do! (.close ^js context))
                  (p/handle (fn [_ err]
                              (if err
                                (destroy-browser pool browser)
                                (release-browser pool browser))))
                  (p/handle #(p/resolved result)))))

          (on-page [pool browser context page]
            (let [id (unchecked-get browser "__id")]
              (l/trace :action "exec:on-page" :browser-id id)
              (-> (p/do! (callback page))
                  (p/handle (partial handle-error pool browser))
                  (p/then (partial on-result pool browser context)))))

          (on-context [pool browser ctx]
            (let [id (unchecked-get browser "__id")]
              (l/trace :action "exec:on-context" :browser-id id)
              (-> (p/do! (.newPage ^js ctx))
                  (p/handle (partial handle-error pool browser))
                  (p/then (partial on-page pool browser ctx)))))

          (on-acquire [pool browser err]
            (let [id (unchecked-get browser "__id")]
              (l/trace :action "exec:on-acquire" :browser-id id)
              (if err
                (js/console.log err)
                (-> (p/do! (.createIncognitoBrowserContext ^js browser))
                    (p/handle (partial handle-error pool browser))
                    (p/then (partial on-context pool browser))))))]

    (when-let [pool (deref pool)]
      (-> (p/do! (.acquire ^js pool))
          (p/handle (partial on-acquire pool))))))

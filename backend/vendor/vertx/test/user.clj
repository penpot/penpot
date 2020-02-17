(ns user
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.test :as test]
   [clojure.tools.namespace.repl :as r]
   [clojure.walk :refer [macroexpand-all]]
   [mount.core :as mount :refer [defstate]]
   [pohjavirta.server :as pohjavirta]
   [promesa.core :as p]
   [reitit.core :as rt]
   [jsonista.core :as j]
   [vertx.core :as vc]
   [vertx.eventbus :as ve]
   [vertx.http :as vh]
   [vertx.web :as vw])
  (:import
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse))

(declare thr-name)

;; --- System

(defstate system
  :start (vc/system)
  :stop (.close system))

;; --- Echo Verticle (using eventbus)

(def echo-verticle*
  (letfn [(on-message [ctx message]
            (println (pr-str "received:" message
                             "on" (thr-name)
                             "with ctx" ctx))
            (:body message))
          (on-start [ctx]
            (ve/consumer ctx "test.echo" on-message))]

    (vc/verticle {:on-start on-start})))

(defstate echo-verticle
  :start @(vc/deploy! system echo-verticle* {:instances 4}))

;; --- Echo Verticle Actor (using eventbus)

;; This is the same as the previous echo verticle, it just reduces the
;; boilerplate of creating the consumer.

;; (def echo-actor-verticle
;;   (letfn [(on-message [message]
;;             (println (pr-str "received:" (.body message)
;;                              "on" (thr-name)))
;;             (.body message))]
;;     (vc/actor "test.echo2" {:on-message on-message})))

;; (defstate echo-actor-verticle
;;   :start @(vc/deploy! system echo-actor-verticle options))

;; --- Http Server Verticle

(def http-verticle
  (letfn [(simple-handler [req]
            ;; (prn req)
            {:status 200
             :body (j/write-value-as-string
                    {:method (:method req)
                     :headers (:headers req)
                     :path (:path req)})})

          (on-start [ctx]
            (let [handler (vh/handler ctx simple-handler)]
              (vh/server ctx {:handler handler :port 2020})))]
    (vc/verticle {:on-start on-start})))

(defstate http-server-verticle
  :start @(vc/deploy! system http-verticle {:instances 2}))

;; --- Web Router Verticle

(def web-router-verticle
  (letfn [(simple-handler [req]
            {:status 200
             :body (j/write-value-as-string
                    {:method (:method req)
                     :path (:path req)})})

          (on-start [ctx]
            (let [routes [["/" {:all simple-handler}]]
                  handler (vw/handler ctx (vw/router routes))]
              (vh/server ctx {:handler handler :port 2021})))]
    (vc/verticle {:on-start on-start})))

(defstate web-server-with-router-verticle
  :start @(vc/deploy! system web-router-verticle {:instances 2}))

;; --- pohjavirta

(defn handler
  [req]
  {:status 200
   :body (j/write-value-as-string
          {:method (:request-method req)
           :headers (:headers req)
           :path (:uri req)})})

(defstate pohjavirta-server
  :start (let [instance (pohjavirta/create #'handler {:port 2022 :io-threads 2})]
           (pohjavirta/start instance)
           instance)
  :stop (pohjavirta/stop pohjavirta-server))

;; --- Repl

(defn start
  []
  (mount/start))

(defn stop
  []
  (mount/stop))

(defn restart
  []
  (stop)
  (r/refresh :after 'user/start))

(defn- run-test
  ([] (run-test #"^vertx-tests.*"))
  ([o]
   (r/refresh)
   (cond
     (instance? java.util.regex.Pattern o)
     (test/run-all-tests o)

     (symbol? o)
     (if-let [sns (namespace o)]
       (do (require (symbol sns))
           (test/test-vars [(resolve o)]))
       (test/test-ns o)))))

;; --- Helpers

(defn thr-name
  []
  (.getName (Thread/currentThread)))


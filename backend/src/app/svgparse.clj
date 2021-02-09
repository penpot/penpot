;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.svgparse
  (:require
   [app.common.exceptions :as ex]
   [app.metrics :as mtx]
   [app.util.graal :as graal]
   [app.util.pool :as pool]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.xml :as xml]
   [integrant.core :as ig])
  (:import
   java.util.function.Consumer
   org.apache.commons.io.IOUtils))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SVG Clean
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare clean-svg)
(declare prepare-context-pool)

(defmethod ig/pre-init-spec ::svgc [_]
  (s/keys :req-un [::mtx/metrics]))

(defmethod ig/init-key ::svgc
  [_ {:keys [metrics] :as cfg}]
  (let [pool    (prepare-context-pool cfg)
        cfg     (assoc cfg :pool pool)
        handler #(clean-svg cfg %)
        handler (->> {:registry (:registry metrics)
                      :type :summary
                      :name "svgc_timing"
                      :help "svg optimization function timing"}
                     (mtx/instrument handler))]
    (with-meta handler {::pool pool})))

(defmethod ig/halt-key! ::svgc
  [_ f]
  (let [{:keys [::pool]} (meta f)]
    (pool/clear! pool)
    (pool/close! pool)))

(defn- prepare-context-pool
  [cfg]
  (pool/create
   {:min-idle  (:min-idle cfg 0)
    :max-idle  (:max-idle cfg 3)
    :max-total (:max-total cfg 3)
    :create
    (fn []
      (let [ctx (graal/context "js")]
        (->> (graal/source "js" (io/resource "svgclean.js"))
             (graal/eval! ctx))
        ctx))
    :destroy
    (fn [ctx]
      (graal/close! ctx))}))

(defn- clean-svg
  [{:keys [pool]} data]
  (with-open [ctx (pool/acquire pool)]
    (let [res      (promise)
          optimize (-> (graal/get-bindings @ctx "js")
                       (graal/get-member "svgc")
                       (graal/get-member "optimize"))
          resultp (graal/invoke optimize data)]

      (graal/invoke-member resultp "then"
                           (reify Consumer
                             (accept [_ val]
                               (deliver res val))))

      (graal/invoke-member resultp "catch"
                           (reify Consumer
                             (accept [_ err]
                               (deliver res err))))

      (let [result (deref res)]
        (if (instance? Throwable result)
          (throw result)
          result)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handler)
(declare process-request)

(s/def ::svgc fn?)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::mtx/metrics ::svgc]))

(defmethod ig/init-key ::handler
  [_ {:keys [metrics] :as cfg}]
  (let [handler #(handler cfg %)]
    (->> {:registry (:registry metrics)
          :type :summary
          :name "http_handler_svgparse_timing"
          :help "svg parse timings"}
         (mtx/instrument handler))))

(defn- handler
  [cfg {:keys [headers body] :as request}]
  (when (not= "image/svg+xml" (get headers "content-type"))
    (ex/raise :type :validation
              :code :unsupported-mime-type
              :mime (get headers "content-type")))
  {:status 200
   :body (process-request cfg body)})

(defn parse
  [data]
  (try
    (with-open [istream (IOUtils/toInputStream data "UTF-8")]
      (xml/parse istream))
    (catch org.xml.sax.SAXParseException _e
      (ex/raise :type :validation
                :code :invalid-svg-file))))

(defn process-request
  [{:keys [svgc] :as cfg} body]
  (let [data (slurp body)
        data (svgc data)]
    (parse data)))


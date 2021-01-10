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
   [app.common.spec :as us]
   [cuerdas.core :as str]
   [app.metrics :as mtx]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.spec.alpha :as s]
   [clojure.xml :as xml]
   [app.util.graal :as graal]
   [app.util.pool :as pool]
   [integrant.core :as ig])
  (:import
   java.io.InputStream
   java.util.function.Consumer
   org.apache.commons.io.IOUtils))

;; (defn- clean-svg
;;   [^InputStream input]
;;   (let [result (shell/sh
;;                 ;; "svgcleaner" "--allow-bigger-file" "-c" "-"
;;                 "svgo"
;;                 "--enable=prefixIds,removeDimensions,removeXMLNS,removeScriptElement"
;;                 "--disable=removeViewBox,moveElemsAttrsToGroup"
;;                 "-i" "-" "-o" "-"

;;                 :in input :out-enc :bytes)
;;         err-str (:err result)]
;;     (when (or (not= 0 (:exit result))
;;               ;; svgcleaner returns 0 with some errors, we need to check
;;               (and (not= err-str "") (not (nil? err-str)) (str/starts-with? err-str "Error")))
;;       (ex/raise :type :validation
;;                 :code :unable-to-optimize
;;                 :hint (:err result)))
;;     (io/input-stream (:out result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SVG Clean
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare do-svg-clean)
(declare prepare-context-pool)

(defmethod ig/pre-init-spec ::svgc [_]
  (s/keys :req-un [::mtx/metrics]))

(defmethod ig/init-key ::svgc
  [_ {:keys [metrics] :as cfg}]
  (let [ctx-pool (prepare-context-pool)]
    (with-meta
      (fn [data]
        (with-open [ctx (pool/acquire ctx-pool)]
          (do-svg-clean @ctx data)))
      {::ctx-pool ctx-pool})))

(defmethod ig/halt-key! ::svgc
  [_ f]
  (let [{:keys [::ctx-pool]} (meta f)]
    (pool/clear! ctx-pool)
    (pool/close! ctx-pool)))

(defn- prepare-context-pool
  []
  (pool/create
   {:min-idle 0
    :max-idle 3
    :max-total 3
    :create
    (fn []
      (let [ctx (graal/context "js")]
        (->> (graal/source "js" (io/resource "svgclean.js"))
             (graal/eval! ctx))
        ctx))
    :destroy
    (fn [ctx]
      (graal/close! ctx))}))

(defn- do-svg-clean
  [ctx data]
  (let [res     (promise)
        cleaner (->> (graal/source "js" "require('svgclean')")
                     (graal/eval! ctx))
        resultp (graal/invoke-member cleaner "optimize" data)]
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
        result))))

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

(defn process-request
  [{:keys [svgc] :as cfg} body]
  (let [data (slurp body)
        data (svgc data)]
    (with-open [istream (IOUtils/toInputStream data "UTF-8")]
      (xml/parse istream))))

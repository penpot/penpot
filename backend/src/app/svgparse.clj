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
   [app.metrics :as mtx]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.spec.alpha :as s]
   [clojure.xml :as xml]
   [integrant.core :as ig])
  (:import
   java.io.InputStream
   org.apache.commons.io.IOUtils))

(defn- string->input-stream
  [^String data]
  (IOUtils/toInputStream data "UTF-8"))

(defn- clean-svg
  [^InputStream input]
  (let [result (shell/sh "svgcleaner" "-c" "-" :in input :out-enc :bytes)]
    (when (not= 0 (:exit result))
      (ex/raise :type :validation
                :code :unable-to-optimize
                :hint (:err result)))
    (io/input-stream (:out result))))

(defn parse
  [^InputStream input]
  (with-open [istream (io/input-stream input)]
    (-> (clean-svg istream)
        (xml/parse))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handler)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::mtx/metrics]))

(defmethod ig/init-key ::handler
  [_ {:keys [metrics] :as cfg}]
  (->> {:registry (:registry metrics)
        :type :summary
        :name "http_handler_svgparse_timing"
        :help "svg parse timings"}
       (mtx/instrument handler)))

(defn- handler
  [{:keys [headers body] :as request}]
  (when (not= "image/svg+xml" (get headers "content-type"))
    (ex/raise :type :validation
              :code :unsupported-mime-type
              :mime (get headers "content-type")))
  {:status 200
   :body (parse body)})

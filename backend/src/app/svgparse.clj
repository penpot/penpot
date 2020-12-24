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
   [clojure.xml :as xml]
   [clojure.java.shell :as shell]
   [clojure.java.io :as io])
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

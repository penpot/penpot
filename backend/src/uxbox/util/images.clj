;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.images
  "Images transformation utils."
  (:require [clojure.java.io :as io])
  (:import org.im4java.core.IMOperation
           org.im4java.core.ConvertCmd
           org.im4java.process.Pipe
           java.io.ByteArrayInputStream
           java.io.ByteArrayOutputStream))

;; Related info on how thumbnails generation
;;  http://www.imagemagick.org/Usage/thumbnails/

(defn thumbnail
  ([input] (thumbnail input nil))
  ([input {:keys [size quality format]
           :or {format "jpg"
                quality 92
                size [200 200]}
           :as opts}]
   {:pre [(vector? size)]}
   (with-open [out (ByteArrayOutputStream.)
               in (io/input-stream input)]
     (let [[width height] size
           pipe (Pipe. in out)
           op (doto (IMOperation.)
                (.addRawArgs ^java.util.List ["-"])
                (.autoOrient)
                ;; (.thumbnail (int width) (int height) "^")
                ;; (.gravity "center")
                ;; (.extent (int width) (int height))
                (.resize (int width) (int height) "^")
                (.quality (double quality))
                (.addRawArgs ^java.util.List [(str format ":-")]))
           cmd (doto (ConvertCmd.)
                 (.setInputProvider pipe)
                 (.setOutputConsumer pipe))]
       (.run cmd op (make-array Object 0))
       (ByteArrayInputStream. (.toByteArray out))))))

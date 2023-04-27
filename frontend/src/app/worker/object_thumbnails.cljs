;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.object-thumbnails
  (:require
   [app.worker.impl :as impl]
   [promesa.core :as p]))

(defmethod impl/handler :object-thumbnails/generate
  [{:keys [] :as message} ibpm]
  (p/let [canvas (js/OffscreenCanvas. (. ibpm -width) (. ibpm -height))
          ctx    (.getContext canvas "bitmaprenderer")
          _      (.transferFromImageBitmap ctx ibpm)
          blob (.convertToBlob canvas #js {:type "image/png"})] 
    (.close ibpm) ;; free imagebitmap data
    {:result (.createObjectURL js/URL blob)}))

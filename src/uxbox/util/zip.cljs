;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.zip
  "Helpers for make zip file (using jszip)."
  (:require [vendor.jszip]
            [promesa.core :as p]))

(defn build*
  [files resolve reject]
  (let [zipobj (js/JSZip.)]
    (run! (fn [[name content]]
            (.file zipobj name content))
          files)
    (-> (.generateAsync zipobj #js {:type "blob"})
        (.then resolve reject))))

(defn build
  [files]
  (p/promise (fn [resolve reject]
               (build* files resolve reject))))

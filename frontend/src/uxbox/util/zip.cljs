;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.zip
  "Helpers for make zip file (using jszip)."
  (:require [vendor.jszip]
            [beicon.core :as rx]))

(defn build
  [files]
  (letfn [(attach-file [zobj [name content]]
            (.file zobj name content))]
    (let [zobj (js/JSZip.)]
      (run! (partial attach-file zobj) files)
      (->> (.generateAsync zobj #js {:type "blob"})
           (rx/from-promise)))))

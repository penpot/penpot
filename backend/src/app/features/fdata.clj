;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.features.fdata
  "A `fdata/*` related feature migration helpers"
  (:require
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]))

(defn enable-objects-map
  [file]
  (-> file
      (update :data (fn [data]
                      (-> data
                          (update :pages-index update-vals #(update % :objects omap/wrap))
                          (update :components update-vals #(update % :objects omap/wrap)))))
      (update :features conj "fdata/objects-map")))

(defn enable-pointer-map
  [file]
  (-> file
      (update :data (fn [data]
                      (-> data
                          (update :pages-index update-vals pmap/wrap)
                          (update :components pmap/wrap))))

      (update :features conj "fdata/pointer-map")))

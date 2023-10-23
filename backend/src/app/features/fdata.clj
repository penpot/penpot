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

;; (defn enable-shape-data-type
;;   [file]
;;   (letfn [(update-object [object]
;;             (-> object
;;                 (d/update-when :selrect grc/make-rect)
;;                 (d/update-when :svg-viewbox grc/make-rect)
;;                 (cts/map->Shape)))

;;           (update-container [container]
;;             (d/update-when container :objects update-vals update-object))]

;;     (-> file
;;         (update :data (fn [data]
;;                         (-> data
;;                             (update :pages-index update-vals update-container)
;;                             (update :components update-vals update-container))))
;;         (update :features conj "fdata/shape-data-type"))))

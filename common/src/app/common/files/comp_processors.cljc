;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.comp-processors
  (:require
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]))

(defn fix-missing-swap-slots
  [file libraries]
  (ctf/update-all-shapes
   file
   (fn [shape]
     (if (and (ctk/instance-head? shape) (ctk/in-component-copy? shape))
       (let [{:keys [container]}
             (meta shape)

             ref-shape
             (ctf/find-ref-shape file container libraries shape :include-deleted? true :with-context? true)]
         (println "comparing" (:name shape) "with ref" (some-> ref-shape :name))
         (if ref-shape
           (if (and (not= (:shape-ref shape) (:id ref-shape))
                    (nil? (ctk/get-swap-slot shape)))
             (let [updated-shape (ctk/set-swap-slot shape (:id ref-shape))]
               {:result :update :updated-shape updated-shape})
             {:result :keep})
           {:result :keep}))
       {:result :keep}))))

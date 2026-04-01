;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.comp-processors
  (:require
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]))

"Repair, migration or transformation utilities for components."

(defn sync-component-id-with-ref-shape
  "Ensure that all copies heads have the same component id and file as the referenced shape.
   There may be bugs that cause them to get out of sync."
  [file libraries]
  (ctf/update-file-data
   file
   (fn [file-data]
     (loop [current-data file-data
            iteration    0]
       (let [next-data (ctf/update-all-shapes
                        file-data
                        (fn [shape]
                          (if (and (ctk/subcopy-head? shape) (nil? (ctk/get-swap-slot shape)))
                            (let [container (:container (meta shape))
                                  ref-shape (ctf/find-ref-shape file container libraries shape {:include-deleted? true :with-context? true})]
                              (if (and (some? ref-shape)
                                       (or (not= (:component-id shape) (:component-id ref-shape))
                                           (not= (:component-file shape) (:component-file ref-shape))))
                                (let [shape' (cond-> shape
                                               (some? (:component-id ref-shape))
                                               (assoc :component-id (:component-id ref-shape))

                                               (nil? (:component-id ref-shape))
                                               (dissoc :component-id)

                                               (some? (:component-file ref-shape))
                                               (assoc :component-file (:component-file ref-shape))

                                               (nil? (:component-file ref-shape))
                                               (dissoc :component-file))]
                                  {:result :update :updated-shape shape'})
                                {:result :keep}))
                            {:result :keep})))]
         (if (or (= current-data next-data)
                 (> iteration 20))     ;; safety bound
           next-data
           (recur next-data (inc iteration))))))))

(defn remove-unneeded-objects-in-components
  "Some components have an :objects attribute, despite not being deleted. This removes it.
   It also adds an empty :objects if it's deleted and does not have it."
  [file]
  (ctf/update-file-data
   file
   (fn [file-data]
     (ctf/update-components
      file-data
      (fn [component]
        (if (:deleted component)
          (if (nil? (:objects component))
            (assoc component :objects {})
            component)
          (if (contains? component :objects)
            (dissoc component :objects)
            component)))))))

(defn fix-missing-swap-slots
  "Locate shapes that have been swapped (i.e. their shape-ref does not point to the near match) but
   they don't have a swap slot. In this case, add one pointing to the near match."
  [file libraries]
  (ctf/update-file-data
   file
   (fn [file-data]
     (ctf/update-all-shapes
      file-data
      (fn [shape]
        (if (ctk/subcopy-head? shape)
          (let [container (:container (meta shape))
                near-match (ctf/find-near-match file container libraries shape :include-deleted? true :with-context? false)]
            (if (and (some? near-match)
                     (not= (:shape-ref shape) (:id near-match))
                     (nil? (ctk/get-swap-slot shape)))
              (let [updated-shape (ctk/set-swap-slot shape (:id near-match))]
                {:result :update :updated-shape updated-shape})
              {:result :keep}))
          {:result :keep}))))))


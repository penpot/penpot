;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.comp-processors
  "Repair, migration or transformation utilities for components."
  (:require
   [app.common.logging :as log]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]))

(log/set-level! :warn)

(defn remove-unneeded-objects-in-components
  "Some components have an :objects attribute, despite not being deleted. This removes it.
   It also adds an empty :objects if it's deleted and does not have it."
  [file-data]
  (ctf/update-components
   file-data
   (fn [component]
     (if (:deleted component)
       (if (nil? (:objects component))
         (do
           (log/warn :msg "Adding empty :objects to deleted component"
                     :component-id (:id component)
                     :component-name (:name component)
                     :file-id (:id file-data))
           (assoc component :objects {}))
         component)
       (if (contains? component :objects)
         (do
           (log/warn :msg "Removing :objects from non-deleted component"
                     :component-id (:id component)
                     :component-name (:name component)
                     :file-id (:id file-data))
           (dissoc component :objects))
         component)))))

(defn normalize-component-root
  "Some old files have shapes with an explicit :component-root false. This is semantically
   equivalent to the attribute being absent (instance-root? only treats true as root), but
   breaks the subcopy-head? predicate, which expects nil. Remove the explicit false so the
   downstream fixers can recognize these shapes as nested copy heads."
  [file-data]
  (try
    (ctf/update-all-shapes
     file-data
     (fn [shape]
       (if (false? (:component-root shape))
         (do
           (log/warn :msg "Normalizing :component-root false on shape"
                     :shape-id (:id shape)
                     :shape-name (:name shape)
                     :file-id (:id file-data))
           {:result :update :updated-shape (dissoc shape :component-root)})
         {:result :keep})))
    (catch #?(:clj Throwable :cljs :default) e
      (log/error :msg "Failed to normalize :component-root on shapes"
                 :file-id (:id file-data)
                 :cause e)
      file-data)))

(defn fix-missing-swap-slots
  "Locate shapes that have been swapped (i.e. their shape-ref does not point to the near match) but
   they don't have a swap slot. In this case, add one pointing to the near match."
  [file-data libraries]
  (try
    (ctf/update-all-shapes
     file-data
     (fn [shape]
       (if (ctk/subcopy-head? shape)
         (let [container (:container (meta shape))
               file {:id (:id file-data) :data file-data}
               near-match (ctf/find-near-match file container libraries shape :include-deleted? true :with-context? false)]
           (if (and (some? near-match)
                    (not= (:shape-ref shape) (:id near-match))
                    (nil? (ctk/get-swap-slot shape)))
             (let [updated-shape (ctk/set-swap-slot shape (:id near-match))]
               (log/warn :msg "Adding missing swap slot to shape"
                         :shape-id (:id shape)
                         :shape-name (:name shape)
                         :swap-slot (:id near-match)
                         :file-id (:id file)
                         :container-id (:id container)
                         :container-type (:type container))
               {:result :update :updated-shape updated-shape})
             {:result :keep}))
         {:result :keep})))
    (catch #?(:clj Throwable :cljs :default) e
      (log/error :msg "Failed to fix missing swap slots on shapes"
                 :file-id (:id file-data)
                 :cause e)
      file-data)))

(defn sync-component-id-with-ref-shape
  "Ensure that all copies heads have the same component id and file as the referenced shape.
   There may be bugs that cause them to get out of sync."
  [file-data libraries]
  (letfn [(sync-one-iteration
            [file-data libraries]
            (try
              (ctf/update-all-shapes
               file-data
               (fn [shape]
                 (if (and (ctk/subcopy-head? shape) (nil? (ctk/get-swap-slot shape)))
                   (let [container (:container (meta shape))
                         file {:id (:id file-data) :data file-data}
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
                         (log/warn :msg "Syncing component id and file with ref shape"
                                   :shape-id (:id shape)
                                   :shape-name (:name shape)
                                   :component-id (:component-id shape')
                                   :component-file (:component-file shape')
                                   :ref-shape-id (:id ref-shape)
                                   :file-id (:id file)
                                   :container-id (:id container)
                                   :container-type (:type container))
                         {:result :update :updated-shape shape'})
                       {:result :keep}))
                   {:result :keep})))
              (catch #?(:clj Throwable :cljs :default) e
                (log/error :msg "Failed to sync component id and file with ref shape"
                           :file-id (:id file-data)
                           :cause e)
                file-data)))]
    ;; If a copy inside a main is updated, we need to repeat the process for the change to be
    ;; propagated to all copies.
    (loop [current-data file-data
           iteration    0]
      (let [next-data (sync-one-iteration current-data libraries)]
        (if (or (= current-data next-data)
                (> iteration 20))     ;; safety bound
          next-data
          (recur next-data (inc iteration)))))))

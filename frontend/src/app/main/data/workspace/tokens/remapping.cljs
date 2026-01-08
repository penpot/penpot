;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.tokens.remapping
  "Core logic for token remapping functionality"
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.tokens :as cft]
   [app.common.logging :as log]
   [app.common.types.container :refer [shapes-seq]]
   [app.common.types.file :refer [object-containers-seq]]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dh]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module, or :warn to reset to default
(log/set-level! :warn)

;; Token Reference Scanning
;; ========================

(defn scan-shape-applied-tokens
  "Scan a shape for applied token references to a specific token name"
  [shape token-name container]
  (when-let [applied-tokens (:applied-tokens shape)]
    (for [[attribute applied-token-name] applied-tokens
          :when (= applied-token-name token-name)]
      {:type :applied-token
       :shape-id (:id shape)
       :attribute attribute
       :token-name applied-token-name
       :container container})))

(defn scan-token-value-references
  "Scan a token value for references to a specific token name (alias), supporting complex token values."
  [token token-name]
  (letfn [(find-all-token-value-references [token-value]
            (cond
              (string? token-value)
              (filter #(= % token-name) (cto/find-token-value-references token-value))

              (map? token-value)
              (mapcat find-all-token-value-references (vals token-value))

              (sequential? token-value)
              (mapcat find-all-token-value-references token-value)

              :else
              []))]
    (when-let [value (:value token)]
      (for [referenced-token-name (find-all-token-value-references value)]
        {:type :token-alias
         :source-token-name (:name token)
         :referenced-token-name referenced-token-name}))))

(defn scan-workspace-token-references
  "Scan entire workspace for all token references to a specific token"
  [file-data old-token-name]
  (let [tokens-lib (:tokens-lib file-data)
        containers (object-containers-seq file-data)

        ;; Scan all shapes for applied token references to the specific token
        matching-applied (mapcat (fn [container]
                                   (let [shapes (shapes-seq container)]
                                     (mapcat #(scan-shape-applied-tokens % old-token-name container) shapes)))
                                 containers)

        ;; Scan tokens library for alias references to the specific token
        matching-aliases (if tokens-lib
                           (let [all-tokens (ctob/get-all-tokens tokens-lib)]
                             (mapcat #(scan-token-value-references % old-token-name) all-tokens))
                           [])]
    (log/info :hint "token-scan-details"
              :token-name old-token-name
              :containers-count (count containers)
              :total-applied-refs (count matching-applied)
              :matching-applied (count matching-applied)
              :total-alias-refs (count matching-aliases)
              :matching-aliases (count matching-aliases))

    {:applied-tokens matching-applied
     :token-aliases matching-aliases
     :total-references (+ (count matching-applied) (count matching-aliases))}))

;; Token Remapping Core Logic
;; ==========================

(defn remap-tokens
  "Main function to remap all token references when a token name changes"
  [old-token-name new-token-name]
  (ptk/reify ::remap-tokens
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-data (dh/lookup-file-data state)
            scan-results (scan-workspace-token-references file-data old-token-name)
            tokens-lib (:tokens-lib file-data)
            sets (ctob/get-sets tokens-lib)
            tokens-with-sets (mapcat (fn [set]
                                       (map (fn [token]
                                              {:token token :set set})
                                            (vals (ctob/get-tokens tokens-lib (ctob/get-id set)))))
                                     sets)

            ;; Group applied token references by container
            refs-by-container (group-by :container (:applied-tokens scan-results))

            ;; Use apply-token logic to update shapes for both direct and alias references
            shape-changes (reduce-kv
                           (fn [changes container refs]
                             (let [shape-ids (map :shape-id refs)
                                   ;; Find the correct token to apply (new or alias)
                                   token (or (some #(when (= (:name (:token %)) new-token-name) %) tokens-with-sets)
                                             (some #(when (= (:name (:token %)) old-token-name) %) tokens-with-sets))
                                   attributes (set (map :attribute refs))]
                               (if token
                                 (-> (pcb/with-container changes container)
                                     (pcb/update-shapes shape-ids
                                                        (fn [shape]
                                                          (update shape :applied-tokens
                                                                  #(merge % (cft/attributes-map attributes (:token token)))))))
                                 changes)))
                           (-> (pcb/empty-changes)
                               (pcb/with-file-data file-data)
                               (pcb/with-library-data file-data))
                           refs-by-container)

            ;; Create changes for updating token alias references
            token-changes (reduce
                           (fn [changes ref]
                             (let [source-token-name (:source-token-name ref)]
                               (when-let [{:keys [token set]} (some #(when (= (:name (:token %)) source-token-name) %) tokens-with-sets)]
                                 (let [old-value (:value token)
                                       new-value (cto/update-token-value-references old-value old-token-name new-token-name)]
                                   (pcb/set-token changes (ctob/get-id set) (:id token)
                                                  (assoc token :value new-value))))))
                           shape-changes
                           (:token-aliases scan-results))]

        (log/info :hint "token-remapping"
                  :old-name old-token-name
                  :new-name new-token-name
                  :references-count (:total-references scan-results))

        (rx/of (dch/commit-changes token-changes))))))

(defn validate-token-remapping
  "Validate that a token remapping operation is safe to perform"
  [old-name new-name]
  (cond
    (str/blank? new-name)
    {:valid? false
     :error :invalid-name
     :message "Token name cannot be empty"}
    (= old-name new-name)
    {:valid? false
     :error :no-change
     :message "New name is the same as current name"}
    :else
    {:valid? true}))

(defn count-token-references
  "Count the number of references to a token in the workspace"
  [file-data token-name]
  (let [scan-results (scan-workspace-token-references file-data token-name)]
    (log/info :hint "token-reference-scan"
              :token-name token-name
              :applied-refs (count (:applied-tokens scan-results))
              :alias-refs (count (:token-aliases scan-results))
              :total (:total-references scan-results))
    (:total-references scan-results)))

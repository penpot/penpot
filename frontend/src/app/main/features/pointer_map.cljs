;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.features.pointer-map
  "A frontend specific helpers for work with pointer-map feature"
  (:require
   [app.common.logging :as log]
   [app.common.transit :as t]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]))

(defn- unresolved-pointers
  [m]
  (into [] (comp (filter (fn [[_ v]] (t/pointer? v))) (map key)) m))

(defn resolve-pointer
  "Resolves a single [key pointer] entry into a [key data] pair against
  `get-file-fragment`. A failing fragment rejects with the original
  cause (logged with file-id and fragment-id) instead of resolving to
  a silently pruned entry. Pass a share-id for share-link scoped
  resolution, as the viewer does."
  ([file-id entry]
   (resolve-pointer file-id nil entry))
  ([file-id share-id [key pointer :as kv]]
   (if (t/pointer? pointer)
     (let [params (cond-> {:file-id file-id :fragment-id @pointer}
                    (uuid? share-id)
                    (assoc :share-id share-id))]
       (->> (rp/cmd! :get-file-fragment params)
            (rx/map :data)
            (rx/map #(vector key %))
            (rx/catch (fn [cause]
                        (log/error :hint "unable to resolve file fragment"
                                   :file-id (str file-id)
                                   :fragment-id (str @pointer)
                                   :cause cause)
                        (rx/throw cause)))))
     (rx/of kv))))

(defn resolve-file
  [{:keys [id data] :as file}]
  (letfn [(resolve-pointers [coll]
            (->> (rx/from (seq coll))
                 (rx/merge-map (partial resolve-pointer id))
                 (rx/reduce conj {})))]

    (->> (rx/zip (resolve-pointers data)
                 (resolve-pointers (:pages-index data)))
         (rx/take 1)
         (rx/map (fn [[data pages-index]]
                   (let [data (assoc data :pages-index pages-index)
                         pending (concat (unresolved-pointers data)
                                         (unresolved-pointers pages-index))]
                     (when (seq pending)
                       (throw (ex-info "unresolved file fragments after resolve"
                                       {:file-id id :keys pending})))
                     (assoc file :data data)))))))

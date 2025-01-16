;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.utils
  "RPC for plugins runtime."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.main.data.helpers :as dsh]
   [app.main.store :as st]
   [app.util.object :as obj]))

(defn locate-file
  [id]
  (assert (uuid? id) "File not valid uuid")
  (dsh/lookup-file @st/state id))

(defn locate-page
  [file-id id]
  (assert (uuid? id) "Page not valid uuid")
  (-> (dsh/lookup-file-data @st/state file-id)
      (dsh/get-page id)))

(defn locate-objects
  ([]
   (locate-objects (:current-file-id @st/state) (:current-page-id @st/state)))
  ([file-id page-id]
   (:objects (locate-page file-id page-id))))

(defn locate-shape
  [file-id page-id id]
  (assert (uuid? id) "Shape not valid uuid")
  (dm/get-in (locate-page file-id page-id) [:objects id]))

(defn locate-library-color
  [file-id id]
  (assert (uuid? id) "Color not valid uuid")
  (dm/get-in (locate-file file-id) [:data :colors id]))

(defn locate-library-typography
  [file-id id]
  (assert (uuid? id) "Typography not valid uuid")
  (dm/get-in (locate-file file-id) [:data :typographies id]))

(defn locate-library-component
  [file-id id]
  (assert (uuid? id) "Component not valid uuid")
  (dm/get-in (locate-file file-id) [:data :components id]))

(defn locate-presence
  [session-id]
  (dm/get-in @st/state [:workspace-presence session-id]))

(defn locate-profile
  [session-id]
  (let [{:keys [profile-id]} (locate-presence session-id)]
    (dm/get-in @st/state [:users profile-id])))

;; FIXME: the impl looks strange: objects is passed by parameters but
;; then the rest of the file is looked up directly from state.... (?)
(defn locate-component
  [objects shape]
  (let [state           (deref st/state)
        file            (dsh/lookup-file state)
        libraries       (dsh/lookup-libraries state)
        root            (ctn/get-instance-root objects shape)]
    [root (ctf/resolve-component root file libraries {:include-deleted? true})]))

(defn proxy->file
  [proxy]
  (let [id (obj/get proxy "$id")]
    (when (some? id)
      (locate-file id))))

(defn proxy->page
  [proxy]
  (let [file-id (obj/get proxy "$file")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? id))
      (locate-page file-id id))))

(defn proxy->shape
  [proxy]
  (let [file-id (obj/get proxy "$file")
        page-id (obj/get proxy "$page")
        id      (obj/get proxy "$id")]
    (when (and (some? file-id) (some? page-id) (some? id))
      (locate-shape file-id page-id id))))

(defn proxy->library-color
  [proxy]
  (let [file-id (obj/get proxy "$file")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? id))
      (locate-library-color file-id id))))

(defn proxy->library-typography
  [proxy]
  (let [file-id (obj/get proxy "$file")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? id))
      (locate-library-typography file-id id))))

(defn proxy->library-component
  [proxy]
  (let [file-id (obj/get proxy "$file")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? id))
      (locate-library-component file-id id))))

(defn proxy->flow
  [proxy]
  (let [file-id (obj/get proxy "$file")
        page-id (obj/get proxy "$page")
        flow-id (obj/get proxy "$id")
        page (locate-page file-id page-id)]
    (when (some? page)
      (get (:flows page) flow-id))))

(defn locate-ruler-guide
  [file-id page-id ruler-id]
  (let [page (locate-page file-id page-id)]
    (when (some? page)
      (d/seek #(= (:id %) ruler-id) (-> page :guides vals)))))

(defn proxy->ruler-guide
  [proxy]
  (let [file-id (obj/get proxy "$file")
        page-id (obj/get proxy "$page")
        ruler-id (obj/get proxy "$id")]
    (locate-ruler-guide file-id page-id ruler-id)))

(defn locate-interaction
  [file-id page-id shape-id index]
  (when-let [shape (locate-shape file-id page-id shape-id)]
    (get-in shape [:interactions index])))

(defn proxy->interaction
  [proxy]
  (let [file-id (obj/get proxy "$file")
        page-id (obj/get proxy "$page")
        shape-id (obj/get proxy "$shape")
        index (obj/get proxy "$index")]
    (locate-interaction file-id page-id shape-id index)))

(defn get-data
  ([self attr]
   (-> (obj/get self "_data")
       (get attr)))

  ([self attr transform-fn]
   (-> (get-data self attr)
       (transform-fn))))

(defn get-data-fn
  ([attr]
   (fn [self]
     (get-data self attr)))

  ([attr transform-fn]
   (fn [self]
     (get-data self attr transform-fn))))

(defn get-state
  ([self attr]
   (let [id      (get-data self :id)
         page-id (or (get-data self :page-id)
                     (:current-page-id @st/state))]
     (-> (dsh/lookup-page-objects @st/state page-id)
         (dm/get-in [:objects id attr]))))

  ([self attr mapfn]
   (-> (get-state self attr)
       (mapfn))))

(defn result-p
  "Creates a pair of atom+promise. The promise will be resolved when the atom gets a value.
  We use this to return the promise to the library clients and resolve its value when a value is passed
  to the atom"
  []
  (let [ret-v (atom nil)
        ret-p
        (js/Promise.
         (fn [resolve _]
           (add-watch
            ret-v
            ::watcher
            (fn [_ _ _ value]
              (remove-watch ret-v ::watcher)
              (resolve value)))))]
    [ret-v ret-p]))

(defn display-not-valid
  [code value]
  (.error js/console (dm/str "[PENPOT PLUGIN] Value not valid: " value ". Code: " code)))

(defn reject-not-valid
  [reject code value]
  (let [msg (dm/str "[PENPOT PLUGIN] Value not valid: " value ". Code: " code)]
    (.error js/console msg)
    (reject msg)))

(defn mixed-value
  [values]
  (let [s (set values)]
    (if (= (count s) 1) (first s) "mixed")))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.media
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [uxbox.common.spec :as us]
   [uxbox.common.data :as d]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.store :as st]
   [uxbox.main.repo :as rp]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.router :as rt]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.time :as ts]
   [uxbox.util.router :as r]
   [uxbox.util.files :as files]))

;; --- Specs

(s/def ::name string?)
(s/def ::width number?)
(s/def ::height number?)
(s/def ::modified-at inst?)
(s/def ::created-at inst?)
(s/def ::mtype string?)
;; (s/def ::thumbnail string?)
(s/def ::id uuid?)
(s/def ::uri string?)
;; (s/def ::collection-id uuid?)
(s/def ::user-id uuid?)

;; (s/def ::collection
;;   (s/keys :req-un [::id
;;                    ::name
;;                    ::created-at
;;                    ::modified-at
;;                    ::user-id]))

(s/def ::media-object
  (s/keys :req-un [::id
                   ::name
                   ::width
                   ::height
                   ::mtype
                   ::created-at
                   ::modified-at
                   ::uri
                   ;; ::thumb-uri
                   ::user-id]))

;; ;; --- Initialize Collection Page
;; 
;; (declare fetch-media-objects)
;; 
;; (defn initialize
;;   [collection-id]
;;   (us/verify ::us/uuid collection-id)
;;   (ptk/reify ::initialize
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (assoc-in state [:dashboard-media-objects :selected] #{}))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (rx/of (fetch-media-objects collection-id)))))
;; 
;; ;; --- Fetch Collections
;; 
;; (declare collections-fetched)
;; 
;; (def fetch-collections
;;   (ptk/reify ::fetch-collections
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (->> (rp/query! :media-object-collections)
;;            (rx/map collections-fetched)))))
;; 
;; 
;; ;; --- Collections Fetched
;; 
;; (defn collections-fetched
;;   [items]
;;   (us/verify (s/every ::collection) items)
;;   (ptk/reify ::collections-fetched
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (reduce (fn [state {:keys [id user] :as item}]
;;                 (let [type (if (uuid/zero? (:user-id item)) :builtin :own)
;;                       item (assoc item :type type)]
;;                   (assoc-in state [:media-objects-collections id] item)))
;;               state
;;               items))))
;; 
;; 
;; ;; --- Create Collection
;; 
;; (declare collection-created)
;; 
;; (def create-collection
;;   (ptk/reify ::create-collection
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (let [data {:name (tr "ds.default-library-title" (gensym "c"))}]
;;         (->> (rp/mutation! :create-media-object-collection data)
;;              (rx/map collection-created))))))
;; 
;; ;; --- Collection Created
;; 
;; (defn collection-created
;;   [item]
;;   (us/verify ::collection item)
;;   (ptk/reify ::collection-created
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (let [{:keys [id] :as item} (assoc item :type :own)]
;;         (update state :media-objects-collections assoc id item)))))
;; 
;; ;; --- Rename Collection
;; 
;; (defn rename-collection
;;   [id name]
;;   (ptk/reify ::rename-collection
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (assoc-in state [:media-objects-collections id :name] name))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (let [params {:id id :name name}]
;;         (->> (rp/mutation! :rename-media-object-collection params)
;;              (rx/ignore))))))
;; 
;; ;; --- Delete Collection
;; 
;; (defn delete-collection
;;   [id on-success]
;;   (ptk/reify ::delete-collection
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update state :media-objects-collections dissoc id))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (->> (rp/mutation! :delete-media-object-collection {:id id})
;;            (rx/tap on-success)
;;            (rx/ignore)))))
;; 
;; ;; --- Update Media object
;; 
;; (defn persist-media-object
;;   [id]
;;   (us/verify ::us/uuid id)
;;   (ptk/reify ::persist-media-object
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (let [data (get-in state [:media-objects id])]
;;         (->> (rp/mutation! :update-media-object data)
;;              (rx/ignore))))))
;; 
;; ;; --- Fetch Media objects
;; 
;; (declare media-objects-fetched)
;; 
;; (defn fetch-media-objects
;;   "Fetch a list of media-objects of the selected collection"
;;   [id]
;;   (us/verify ::us/uuid id)
;;   (ptk/reify ::fetch-media-objects
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (let [params {:collection-id id}]
;;         (->> (rp/query! :media-objects-by-collection params)
;;              (rx/map (partial media-objects-fetched id)))))))
;; 
;; ;; --- Media objects Fetched
;; 
;; (s/def ::media-objects (s/every ::media-object))
;; 
;; (defn media-objects-fetched
;;   [collection-id items]
;;   (us/verify ::us/uuid collection-id)
;;   (us/verify ::media-objects items)
;;   (ptk/reify ::media-objects-fetched
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (let [media-objects (d/index-by :id items)]
;;         (assoc state :media-objects media-objects)))))
;; 
;; ;; --- Fetch Media object
;; 
;; (declare media-object-fetched)
;; 
;; (defrecord FetchMediaObject [id]
;;   ptk/WatchEvent
;;   (watch [_ state stream]
;;     (let [existing (get-in state [:media-objects id])]
;;       (if existing
;;         (rx/empty)
;;         (->> (rp/query! :media-object-by-id {:id id})
;;              (rx/map media-object-fetched)
;;              (rx/catch rp/client-error? #(rx/empty)))))))
;; 
;; (defn fetch-media-object
;;   "Conditionally fetch media-object by its id. If media-object
;;   is already loaded, this event is noop."
;;   [id]
;;   {:pre [(uuid? id)]}
;;   (FetchMediaObject. id))
;; 
;; ;; --- MediaObject Fetched
;; 
;; (defrecord MediaObjectFetched [media-object]
;;   ptk/UpdateEvent
;;   (update [_ state]
;;     (let [id (:id media-object)]
;;       (update state :media-objects assoc id media-object))))
;; 
;; (defn media-object-fetched
;;   [media-object]
;;   {:pre [(map? media-object)]}
;;   (MediaObjectFetched. media-object))
;; 
;; ;; --- Rename MediaObject
;; 
;; (defn rename-media-object
;;   [id name]
;;   (us/verify ::us/uuid id)
;;   (us/verify ::us/string name)
;;   (ptk/reify ::rename-media-object
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (assoc-in state [:media-objects id :name] name))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (rx/of (persist-media-object id)))))
;; 
;; ;; --- MediaObject Selection
;; 
;; (defn select-media-object
;;   [id]
;;   (ptk/reify ::select-media-object
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update-in state [:dashboard-media-objects :selected] (fnil conj #{}) id))))
;; 
;; (defn deselect-media-object
;;   [id]
;;   (ptk/reify ::deselect-media-object
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update-in state [:dashboard-media-objects :selected] (fnil disj #{}) id))))
;; 
;; (def deselect-all-media-objects
;;   (ptk/reify ::deselect-all-media-objects
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (assoc-in state [:dashboard-media-objects :selected] #{}))))
;; 
;; ;; --- Delete MediaObjects
;; 
;; (defn delete-media-object
;;   [id]
;;   (us/verify ::us/uuid id)
;;   (ptk/reify ::delete-media-object
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update state :media-objects dissoc id))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (rx/merge
;;        (rx/of deselect-all-media-objects)
;;        (->> (rp/mutation! :delete-media-object {:id id})
;;             (rx/ignore))))))
;; 
;; ;; --- Delete Selected
;; 
;; (def delete-selected
;;   (ptk/reify ::delete-selected
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (let [selected (get-in state [:dashboard-media-objects :selected])]
;;         (->> (rx/from selected)
;;              (rx/map delete-media-object))))))
;; 
;; ;; --- Update Opts (Filtering & Ordering)
;; 
;; (defn update-opts
;;   [& {:keys [order filter edition]
;;       :or {edition false}}]
;;   (ptk/reify ::update-opts
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update state :dashboard-media-objects merge
;;               {:edition edition}
;;               (when order {:order order})
;;               (when filter {:filter filter})))))

;; --- Copy Selected MediaObject

;; (defrecord CopySelected [id]
;;   ptk/WatchEvent
;;   (watch [_ state stream]
;;     (let [selected (get-in state [:dashboard-media-objects :selected])]
;;       (rx/merge
;;        (->> (rx/from selected)
;;             (rx/flat-map #(rp/mutation! :copy-media-object {:id % :collection-id id}))
;;             (rx/map media-object-created))
;;        (->> (rx/from selected)
;;             (rx/map deselect-media-object))))))

;; (defn copy-selected
;;   [id]
;;   {:pre [(or (uuid? id) (nil? id))]}
;;   (CopySelected. id))

;; --- Move Selected MediaObject

;; (defrecord MoveSelected [id]
;;   ptk/UpdateEvent
;;   (update [_ state]
;;     (let [selected (get-in state [:dashboard-media-objects :selected])]
;;       (reduce (fn [state media-object]
;;                 (assoc-in state [:media-objects media-object :collection] id))
;;               state
;;               selected)))

;;   ptk/WatchEvent
;;   (watch [_ state stream]
;;     (let [selected (get-in state [:dashboard-media-objects :selected])]
;;       (rx/merge
;;        (->> (rx/from selected)
;;             (rx/map persist-media-object))
;;        (->> (rx/from selected)
;;             (rx/map deselect-media-object))))))

;; (defn move-selected
;;   [id]
;;   {:pre [(or (uuid? id) (nil? id))]}
;;   (MoveSelected. id))


;;;;;;; NEW

;; --- Create library Media Objects

(declare create-media-objects-result)
(def allowed-file-types #{"image/jpeg" "image/png" "image/webp" "image/svg+xml"})
(def max-file-size (* 5 1024 1024))

;; TODO: unify with upload-media-object at main/data/workspace/persistence.cljs
;;       and update-photo at main/data/users.cljs
;; https://tree.taiga.io/project/uxboxproject/us/440

(defn create-media-objects
  ([file-id files] (create-media-objects file-id files identity))
  ([file-id files on-uploaded]
   (us/verify (s/nilable ::us/uuid) file-id)
   (us/verify fn? on-uploaded)
   (ptk/reify ::create-media-objects
     ptk/WatchEvent
     (watch [_ state stream]
       (let [check-file
             (fn [file]
               (when (> (.-size file) max-file-size)
                 (throw (ex-info (tr "errors.media-too-large") {})))
               (when-not (contains? allowed-file-types (.-type file))
                 (throw (ex-info (tr "errors.media-format-unsupported") {})))
               file)

             on-success #(do (st/emit! dm/hide)
                             (on-uploaded %))

             on-error #(do (st/emit! dm/hide)
                           (let [msg (cond
                                       (.-message %)
                                       (.-message %)

                                       (= (:code %) :media-type-not-allowed)
                                       (tr "errors.media-type-not-allowed")

                                       (= (:code %) :media-type-mismatch)
                                       (tr "errors.media-type-mismatch")

                                       :else
                                       (tr "errors.unexpected-error"))]
                             (rx/of (dm/error msg))))

             prepare
             (fn [file]
               {:name (.-name file)
                :file-id file-id
                :content file
                :is-local false})]

         (st/emit! (dm/show {:content (tr "media.loading")
                             :type :info
                             :timeout nil}))

         (->> (rx/from files)
              (rx/map check-file)
              (rx/map prepare)
              (rx/mapcat #(rp/mutation! :upload-media-object %))
              (rx/reduce conj [])
              (rx/do on-success)
              (rx/mapcat identity)
              (rx/map (partial create-media-objects-result file-id))
              (rx/catch on-error)))))))

;; --- Media object Created

(defn create-media-objects-result
  [file-id media-object]
  #_(us/verify ::media-object media-object)
  (ptk/reify ::create-media-objects-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-media (:id media-object)] media-object)))))


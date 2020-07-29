;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.images
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
(s/def ::thumbnail string?)
(s/def ::id uuid?)
(s/def ::url string?)
(s/def ::collection-id uuid?)
(s/def ::user-id uuid?)

(s/def ::collection
  (s/keys :req-un [::id
                   ::name
                   ::created-at
                   ::modified-at
                   ::user-id]))

(s/def ::image
  (s/keys :req-un [::id
                   ::name
                   ::width
                   ::height
                   ::mtype
                   ::collection-id
                   ::created-at
                   ::modified-at
                   ::uri
                   ::thumb-uri
                   ::user-id]))

;; ;; --- Initialize Collection Page
;; 
;; (declare fetch-images)
;; 
;; (defn initialize
;;   [collection-id]
;;   (us/verify ::us/uuid collection-id)
;;   (ptk/reify ::initialize
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (assoc-in state [:dashboard-images :selected] #{}))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (rx/of (fetch-images collection-id)))))
;; 
;; ;; --- Fetch Collections
;; 
;; (declare collections-fetched)
;; 
;; (def fetch-collections
;;   (ptk/reify ::fetch-collections
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (->> (rp/query! :image-collections)
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
;;                   (assoc-in state [:images-collections id] item)))
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
;;         (->> (rp/mutation! :create-image-collection data)
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
;;         (update state :images-collections assoc id item)))))
;; 
;; ;; --- Rename Collection
;; 
;; (defn rename-collection
;;   [id name]
;;   (ptk/reify ::rename-collection
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (assoc-in state [:images-collections id :name] name))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (let [params {:id id :name name}]
;;         (->> (rp/mutation! :rename-image-collection params)
;;              (rx/ignore))))))
;; 
;; ;; --- Delete Collection
;; 
;; (defn delete-collection
;;   [id on-success]
;;   (ptk/reify ::delete-collection
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update state :images-collections dissoc id))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (->> (rp/mutation! :delete-image-collection {:id id})
;;            (rx/tap on-success)
;;            (rx/ignore)))))
;; 
;; ;; --- Update Image
;; 
;; (defn persist-image
;;   [id]
;;   (us/verify ::us/uuid id)
;;   (ptk/reify ::persist-image
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (let [data (get-in state [:images id])]
;;         (->> (rp/mutation! :update-image data)
;;              (rx/ignore))))))
;; 
;; ;; --- Fetch Images
;; 
;; (declare images-fetched)
;; 
;; (defn fetch-images
;;   "Fetch a list of images of the selected collection"
;;   [id]
;;   (us/verify ::us/uuid id)
;;   (ptk/reify ::fetch-images
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (let [params {:collection-id id}]
;;         (->> (rp/query! :images-by-collection params)
;;              (rx/map (partial images-fetched id)))))))
;; 
;; ;; --- Images Fetched
;; 
;; (s/def ::images (s/every ::image))
;; 
;; (defn images-fetched
;;   [collection-id items]
;;   (us/verify ::us/uuid collection-id)
;;   (us/verify ::images items)
;;   (ptk/reify ::images-fetched
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (let [images (d/index-by :id items)]
;;         (assoc state :images images)))))
;; 
;; ;; --- Fetch Image
;; 
;; (declare image-fetched)
;; 
;; (defrecord FetchImage [id]
;;   ptk/WatchEvent
;;   (watch [_ state stream]
;;     (let [existing (get-in state [:images id])]
;;       (if existing
;;         (rx/empty)
;;         (->> (rp/query! :image-by-id {:id id})
;;              (rx/map image-fetched)
;;              (rx/catch rp/client-error? #(rx/empty)))))))
;; 
;; (defn fetch-image
;;   "Conditionally fetch image by its id. If image
;;   is already loaded, this event is noop."
;;   [id]
;;   {:pre [(uuid? id)]}
;;   (FetchImage. id))
;; 
;; ;; --- Image Fetched
;; 
;; (defrecord ImageFetched [image]
;;   ptk/UpdateEvent
;;   (update [_ state]
;;     (let [id (:id image)]
;;       (update state :images assoc id image))))
;; 
;; (defn image-fetched
;;   [image]
;;   {:pre [(map? image)]}
;;   (ImageFetched. image))
;; 
;; ;; --- Rename Image
;; 
;; (defn rename-image
;;   [id name]
;;   (us/verify ::us/uuid id)
;;   (us/verify ::us/string name)
;;   (ptk/reify ::rename-image
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (assoc-in state [:images id :name] name))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (rx/of (persist-image id)))))
;; 
;; ;; --- Image Selection
;; 
;; (defn select-image
;;   [id]
;;   (ptk/reify ::select-image
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update-in state [:dashboard-images :selected] (fnil conj #{}) id))))
;; 
;; (defn deselect-image
;;   [id]
;;   (ptk/reify ::deselect-image
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update-in state [:dashboard-images :selected] (fnil disj #{}) id))))
;; 
;; (def deselect-all-images
;;   (ptk/reify ::deselect-all-images
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (assoc-in state [:dashboard-images :selected] #{}))))
;; 
;; ;; --- Delete Images
;; 
;; (defn delete-image
;;   [id]
;;   (us/verify ::us/uuid id)
;;   (ptk/reify ::delete-image
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update state :images dissoc id))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (rx/merge
;;        (rx/of deselect-all-images)
;;        (->> (rp/mutation! :delete-image {:id id})
;;             (rx/ignore))))))
;; 
;; ;; --- Delete Selected
;; 
;; (def delete-selected
;;   (ptk/reify ::delete-selected
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (let [selected (get-in state [:dashboard-images :selected])]
;;         (->> (rx/from selected)
;;              (rx/map delete-image))))))
;; 
;; ;; --- Update Opts (Filtering & Ordering)
;; 
;; (defn update-opts
;;   [& {:keys [order filter edition]
;;       :or {edition false}}]
;;   (ptk/reify ::update-opts
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update state :dashboard-images merge
;;               {:edition edition}
;;               (when order {:order order})
;;               (when filter {:filter filter})))))

;; --- Copy Selected Image

;; (defrecord CopySelected [id]
;;   ptk/WatchEvent
;;   (watch [_ state stream]
;;     (let [selected (get-in state [:dashboard-images :selected])]
;;       (rx/merge
;;        (->> (rx/from selected)
;;             (rx/flat-map #(rp/mutation! :copy-image {:id % :collection-id id}))
;;             (rx/map image-created))
;;        (->> (rx/from selected)
;;             (rx/map deselect-image))))))

;; (defn copy-selected
;;   [id]
;;   {:pre [(or (uuid? id) (nil? id))]}
;;   (CopySelected. id))

;; --- Move Selected Image

;; (defrecord MoveSelected [id]
;;   ptk/UpdateEvent
;;   (update [_ state]
;;     (let [selected (get-in state [:dashboard-images :selected])]
;;       (reduce (fn [state image]
;;                 (assoc-in state [:images image :collection] id))
;;               state
;;               selected)))

;;   ptk/WatchEvent
;;   (watch [_ state stream]
;;     (let [selected (get-in state [:dashboard-images :selected])]
;;       (rx/merge
;;        (->> (rx/from selected)
;;             (rx/map persist-image))
;;        (->> (rx/from selected)
;;             (rx/map deselect-image))))))

;; (defn move-selected
;;   [id]
;;   {:pre [(or (uuid? id) (nil? id))]}
;;   (MoveSelected. id))


;;;;;;; NEW

;; --- Create Image
(declare create-images-result)
(def allowed-file-types #{"image/jpeg" "image/png" "image/webp" "image/svg+xml"})
(def max-file-size (* 5 1024 1024))

;; TODO: unify with upload-image at main/data/workspace/persistence.cljs
;;       and update-photo at main/data/users.cljs
;; https://tree.taiga.io/project/uxboxproject/us/440

(defn create-images
  ([file-id files] (create-images file-id files identity))
  ([file-id files on-uploaded]
   (us/verify (s/nilable ::us/uuid) file-id)
   (us/verify fn? on-uploaded)
   (ptk/reify ::create-images
     ptk/WatchEvent
     (watch [_ state stream]
       (let [check-file
             (fn [file]
               (when (> (.-size file) max-file-size)
                 (throw (ex-info (tr "errors.image-too-large") {})))
               (when-not (contains? allowed-file-types (.-type file))
                 (throw (ex-info (tr "errors.image-format-unsupported") {})))
               file)

             on-success #(do (st/emit! dm/hide)
                             (on-uploaded %))

             on-error #(do (st/emit! dm/hide)
                           (let [msg (cond
                                       (.-message %)
                                       (.-message %)

                                       (= (:code %) :image-type-not-allowed)
                                       (tr "errors.image-type-not-allowed")

                                       (= (:code %) :image-type-mismatch)
                                       (tr "errors.image-type-mismatch")

                                       :else
                                       (tr "errors.unexpected-error"))]
                             (rx/of (dm/error msg))))

             prepare
             (fn [file]
               {:name (.-name file)
                :file-id file-id
                :content file})]

         (st/emit! (dm/show {:content (tr "image.loading")
                             :type :info
                             :timeout nil}))

         (->> (rx/from files)
              (rx/map check-file)
              (rx/map prepare)
              (rx/mapcat #(rp/mutation! :upload-image %))
              (rx/reduce conj [])
              (rx/do on-success)
              (rx/mapcat identity)
              (rx/map (partial create-images-result file-id))
              (rx/catch on-error)))))))

;; --- Image Created

(defn create-images-result
  [file-id image]
  #_(us/verify ::image image)
  (ptk/reify ::create-images-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-images (:id image)] image)))))


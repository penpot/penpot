;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.icons
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [uxbox.common.spec :as us]
   [uxbox.common.data :as d]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.util.dom :as dom]
   [uxbox.util.webapi :as wapi]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.router :as r]
   [uxbox.util.uuid :as uuid]))

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::created-at inst?)
(s/def ::modified-at inst?)
(s/def ::user-id uuid?)
(s/def ::collection-id ::us/uuid)

(s/def ::collection
  (s/keys :req-un [::id
                   ::name
                   ::created-at
                   ::modified-at
                   ::user-id]))


(declare fetch-icon-libraries-result)

(defn fetch-icon-libraries
  [team-id]
  (s/assert ::us/uuid team-id)
  (ptk/reify ::fetch-icon-libraries
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query! :icon-libraries {:team-id team-id})
           (rx/map fetch-icon-libraries-result)))))

(defn fetch-icon-libraries-result [result]
  (ptk/reify ::fetch-icon-libraries-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:library :icon-libraries] result)))))

(declare fetch-icon-library-result)

(defn fetch-icon-library
  [library-id]
  (ptk/reify ::fetch-icon-library
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:library :selected-items] nil)))
    
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query! :icons {:library-id library-id})
           (rx/map fetch-icon-library-result)))))

(defn fetch-icon-library-result
  [data]
  (ptk/reify ::fetch-icon-library
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:library :selected-items] data)))))

(declare create-icon-library-result)

(defn create-icon-library
  [team-id name]
  (ptk/reify ::create-icon-library
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/mutation! :create-icon-library {:team-id team-id
                                               :name name})
           (rx/map create-icon-library-result)))))

(defn create-icon-library-result [result]
  (ptk/reify ::create-icon-library-result
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:library :icon-libraries] #(into [result] %))))))

;; rename-icon-library
;; delete-icon-library

;; (declare fetch-icons)
;; 
;; (defn initialize
;;   [collection-id]
;;   (s/assert ::us/uuid collection-id)
;;   (ptk/reify ::initialize
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (assoc-in state [:dashboard-icons :selected] #{}))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (rx/of (fetch-icons collection-id)))))
;; 
;; --- Fetch Collections

;; (declare collections-fetched)
;; 
;; (def fetch-collections
;;   (ptk/reify ::fetch-collections
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (->> (rp/query! :icons-collections)
;;            (rx/map collections-fetched)))))
;; 
;; ;; --- Collections Fetched
;; 
;; (defn collections-fetched
;;   [items]
;;   (s/assert (s/every ::collection) items)
;;   (ptk/reify ::collections-fetched
;;     cljs.core/IDeref
;;     (-deref [_] items)
;; 
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (reduce (fn [state {:keys [id user] :as item}]
;;                 (let [type (if (uuid/zero? (:user-id item)) :builtin :own)
;;                       item (assoc item :type type)]
;;                   (assoc-in state [:icons-collections id] item)))
;;               state
;;               items))))


;; ;; --- Create Collection
;; 
;; (declare collection-created)
;; 
;; (def create-collection
;;   (ptk/reify ::create-collection
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (let [name (tr "ds.default-library-title" (gensym "c"))
;;             data {:name name}]
;;         (->> (rp/mutation! :create-icons-collection data)
;;              (rx/map collection-created))))))
;; 
;; 
;; ;; --- Collection Created
;; 
;; (defn collection-created
;;   [item]
;;   (s/assert ::collection item)
;;   (ptk/reify ::collection-created
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (let [{:keys [id] :as item} (assoc item :type :own)]
;;         (update state :icons-collections assoc id item)))))
;; 
;; ;; --- Rename Collection
;; 
;; (defn rename-collection
;;   [id name]
;;   (ptk/reify ::rename-collection
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (assoc-in state [:icons-collections id :name] name))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (let [params {:id id :name name}]
;;         (->> (rp/mutation! :rename-icons-collection params)
;;              (rx/ignore))))))
;; 
;; ;; --- Delete Collection
;; 
;; (defn delete-collection
;;   [id on-success]
;;   (ptk/reify ::delete-collection
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update state :icons-collections dissoc id))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (->> (rp/mutation! :delete-icons-collection {:id id})
;;            (rx/tap on-success)
;;            (rx/ignore)))))
;; 
;; --- Icon Created

;; --- Create Icon
(defn- parse-svg
  [data]
  (s/assert ::us/string data)
  (let [valid-tags #{"defs" "path" "circle" "rect" "metadata" "g"
                     "radialGradient" "stop"}
        div (dom/create-element "div")
        gc (dom/create-element "div")
        g (dom/create-element "http://www.w3.org/2000/svg" "g")
        _ (dom/set-html! div data)
        svg (dom/query div "svg")]
    (loop [child (dom/get-first-child svg)]
      (if child
        (let [tagname (dom/get-tag-name child)]
          (if  (contains? valid-tags tagname)
            (dom/append-child! g child)
            (dom/append-child! gc child))
          (recur (dom/get-first-child svg)))
        (let [width (.. svg -width -baseVal -value)
              height (.. svg -height -baseVal -value)
              view-box [(.. svg -viewBox -baseVal -x)
                        (.. svg -viewBox -baseVal -y)
                        (.. svg -viewBox -baseVal -width)
                        (.. svg -viewBox -baseVal -height)]
              props {:width width
                     :mimetype "image/svg+xml"
                     :height height
                     :view-box view-box}]
          [(dom/get-outer-html g) props])))))


(declare create-icon-result)

(defn create-icons
  [library-id files]
  (s/assert (s/nilable uuid?) library-id)
  (ptk/reify ::create-icons
    ptk/WatchEvent
    (watch [_ state s]
      (letfn [(parse [file]
                (->> (wapi/read-file-as-text file)
                     (rx/map parse-svg)))
              (allowed? [file]
                (= (.-type file) "image/svg+xml"))
              (prepare [[content metadata]]
                {:library-id library-id
                 :content content
                 :id (uuid/next)
                 ;; TODO Keep the name of the original icon
                 :name (str "Icon " (gensym "i"))
                 :metadata metadata})]
        (->> (rx/from files)
             (rx/filter allowed?)
             (rx/merge-map parse)
             (rx/map prepare)
             (rx/flat-map #(rp/mutation! :create-icon %))
             (rx/map (partial create-icon-result library-id)))))))

(defn create-icon-result
  [library-id item]
  (ptk/reify ::create-icon-result
    ptk/UpdateEvent
    (update [_ state]
      (let [{:keys [id] :as item} (assoc item :type :icon)]
        (-> state
            (update-in [:library :selected-items library-id] #(into [item] %)))))))

;; ;; --- Icon Persisted
;; 
;; (defrecord IconPersisted [id data]
;;   ptk/UpdateEvent
;;   (update [_ state]
;;     (assoc-in state [:icons id] data)))
;; 
;; (defn icon-persisted
;;   [{:keys [id] :as data}]
;;   {:pre [(map? data)]}
;;   (IconPersisted. id data))
;; 
;; ;; --- Persist Icon
;; 
;; (defn persist-icon
;;   [id]
;;   (s/assert ::us/uuid id)
;;   (ptk/reify ::persist-icon
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (let [data (get-in state [:icons id])]
;;         (->> (rp/mutation! :update-icon data)
;;              (rx/ignore))))))
;; 
;; --- Load Icons

;; (declare icons-fetched)
;; 
;; (defn fetch-icons
;;   [id]
;;   (ptk/reify ::fetch-icons
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (let [params (cond-> {} id (assoc :collection-id id))]
;;         (->> (rp/query! :icons-by-collection params)
;;              (rx/map icons-fetched))))))
;; 
;; ;; --- Icons Fetched
;; 
;; (defn icons-fetched
;;   [items]
;;   ;; TODO: specs
;;   (ptk/reify ::icons-fetched
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (let [icons (d/index-by :id items)]
;;         (assoc state :icons icons)))))

;; ;; --- Rename Icon
;; 
;; (defn rename-icon
;;   [id name]
;;   (s/assert ::us/uuid id)
;;   (s/assert ::us/string name)
;;   (ptk/reify ::rename-icon
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (assoc-in state [:icons id :name] name))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (rx/of (persist-icon id)))))
;; 
;; ;; --- Icon Selection
;; 
;; (defn select-icon
;;   [id]
;;   (ptk/reify ::select-icon
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update-in state [:dashboard-icons :selected] (fnil conj #{}) id))))
;; 
;; (defn deselect-icon
;;   [id]
;;   (ptk/reify ::deselect-icon
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update-in state [:dashboard-icons :selected] (fnil disj #{}) id))))
;; 
;; (def deselect-all-icons
;;   (ptk/reify ::deselect-all-icons
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (assoc-in state [:dashboard-icons :selected] #{}))))
;; 
;; ;; --- Delete Icons
;; 
;; (defn delete-icon
;;   [id]
;;   (ptk/reify ::delete-icon
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update state :icons dissoc id))
;; 
;;     ptk/WatchEvent
;;     (watch [_ state s]
;;       (rx/merge
;;        (rx/of deselect-all-icons)
;;        (->> (rp/mutation! :delete-icon {:id id})
;;             (rx/ignore))))))
;; 
;; ;; --- Delete Selected
;; 
;; (def delete-selected
;;   (ptk/reify ::delete-selected
;;     ptk/WatchEvent
;;     (watch [_ state stream]
;;       (let [selected (get-in state [:dashboard-icons :selected])]
;;         (->> (rx/from selected)
;;              (rx/map delete-icon))))))
;; ;; --- Update Opts (Filtering & Ordering)
;; 
;; (defn update-opts
;;   [& {:keys [order filter edition]
;;       :or {edition false}}]
;;   (ptk/reify ::update-opts
;;     ptk/UpdateEvent
;;     (update [_ state]
;;       (update state :dashboard-icons merge
;;               {:edition edition}
;;               (when order {:order order})
;;               (when filter {:filter filter})))))

;; --- Copy Selected Icon

;; (defrecord CopySelected [id]
;;   ptk/WatchEvent
;;   (watch [_ state stream]
;;     (let [selected (get-in state [:dashboard :icons :selected])]
;;       (rx/merge
;;        (->> (rx/from selected)
;;             (rx/map #(get-in state [:icons %]))
;;             (rx/map #(dissoc % :id))
;;             (rx/map #(assoc % :collection-id id))
;;             (rx/flat-map #(rp/mutation :create-icon %))
;;             (rx/map :payload)
;;             (rx/map icon-created))
;;        (->> (rx/from selected)
;;             (rx/map deselect-icon))))))

;; (defn copy-selected
;;   [id]
;;   {:pre [(or (uuid? id) (nil? id))]}
;;   (CopySelected. id))

;; --- Move Selected Icon

;; (defrecord MoveSelected [id]
;;   ptk/UpdateEvent
;;   (update [_ state]
;;     (let [selected (get-in state [:dashboard :icons :selected])]
;;       (reduce (fn [state icon]
;;                 (assoc-in state [:icons icon :collection] id))
;;               state
;;               selected)))

;;   ptk/WatchEvent
;;   (watch [_ state stream]
;;     (let [selected (get-in state [:dashboard :icons :selected])]
;;       (rx/merge
;;        (->> (rx/from selected)
;;             (rx/map persist-icon))
;;        (->> (rx/from selected)
;;             (rx/map deselect-icon))))))

;; (defn move-selected
;;   [id]
;;   {:pre [(or (uuid? id) (nil? id))]}
;;   (MoveSelected. id))

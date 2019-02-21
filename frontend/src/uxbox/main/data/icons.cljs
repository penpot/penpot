;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.icons
  (:require [cuerdas.core :as str]
            [beicon.core :as rx]
            [uxbox.util.data :refer (jscoll->vec)]
            [uxbox.util.uuid :as uuid]
            [potok.core :as ptk]
            [uxbox.util.router :as r]
            [uxbox.util.dom :as dom]
            [uxbox.util.files :as files]
            [uxbox.main.store :as st]
            [uxbox.main.repo :as rp]))

;; --- Initialize

(declare fetch-icons)
(declare fetch-collections)
(declare collections-fetched?)

(defrecord Initialize [type id]
  ptk/UpdateEvent
  (update [_ state]
    (let [type (or type :own)
          data {:type type :id id :selected #{}}]
      (-> state
          (assoc-in [:dashboard :icons] data)
          (assoc-in [:dashboard :section] :dashboard/icons))))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/merge (rx/of (fetch-collections))
              (rx/of (fetch-icons id)))))

(defn initialize
  [type id]
  (Initialize. type id))

;; --- Select a Collection

(defrecord SelectCollection [type id]
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (r/navigate :dashboard/icons
                       {:type type :id id}))))

(defn select-collection
  ([type]
   (select-collection type nil))
  ([type id]
   {:pre [(keyword? type)]}
   (SelectCollection. type id)))

;; --- Collections Fetched

(deftype CollectionsFetched [items]
  cljs.core/IDeref
  (-deref [_] items)

  ptk/UpdateEvent
  (update [_ state]
    (reduce (fn [state {:keys [id user] :as item}]
              (let [type (if (uuid/zero? (:user item)) :builtin :own)
                    item (assoc item :type type)]
                (assoc-in state [:icons-collections id] item)))
            state
            items)))

(defn collections-fetched
  [items]
  (CollectionsFetched. items))

;; --- Fetch Collections

(defrecord FetchCollections []
  ptk/WatchEvent
  (watch [_ state s]
    (->> (rp/req :fetch/icon-collections)
         (rx/map :payload)
         (rx/map collections-fetched))))

(defn fetch-collections
  []
  (FetchCollections.))

;; --- Collection Created

(defrecord CollectionCreated [item]
  ptk/UpdateEvent
  (update [_ state]
    (let [{:keys [id] :as item} (assoc item :type :own)]
      (update state :icons-collections assoc id item)))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (select-collection :own (:id item)))))

(defn collection-created
  [item]
  (CollectionCreated. item))

;; --- Create Collection

(defrecord CreateCollection []
  ptk/WatchEvent
  (watch [_ state s]
    (let [name (tr "ds.default-library-title" (gensym "c"))
          coll {:name name}]
      (->> (rp/req :create/icon-collection coll)
           (rx/map :payload)
           (rx/map collection-created)))))

(defn create-collection
  []
  (CreateCollection.))

(defn collections-fetched?
  [v]
  (instance? CollectionsFetched v))

;; --- Collection Updated

(defrecord CollectionUpdated [item]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:icons-collections (:id item)]  merge item)))

(defn collection-updated
  [item]
  (CollectionUpdated. item))

;; --- Update Collection

(defrecord UpdateCollection [id]
  ptk/WatchEvent
  (watch [_ state s]
    (let [item (get-in state [:icons-collections id])]
      (->> (rp/req :update/icon-collection item)
           (rx/map :payload)
           (rx/map collection-updated)))))

(defn update-collection
  [id]
  (UpdateCollection. id))

;; --- Rename Collection

(defrecord RenameCollection [id name]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:icons-collections id :name] name))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (update-collection id))))

(defn rename-collection
  [id name]
  (RenameCollection. id name))

;; --- Delete Collection

(defrecord DeleteCollection [id]
  ptk/UpdateEvent
  (update [_ state]
    (update state :icons-collections dissoc id))

  ptk/WatchEvent
  (watch [_ state s]
    (let [type (get-in state [:dashboard :icons :type])]
      (->> (rp/req :delete/icon-collection id)
           (rx/map #(select-collection type))))))

(defn delete-collection
  [id]
  (DeleteCollection. id))

;; --- Icon Created

(defrecord IconCreated [item]
  ptk/UpdateEvent
  (update [_ state]
    (let [{:keys [id] :as item} (assoc item :type :icon)]
      (update state :icons assoc id item))))

(defn icon-created
  [item]
  (IconCreated. item))

;; --- Create Icon

(defn- parse-svg
  [data]
  {:pre [(string? data)]}
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
              header (.. svg -height -baseVal -value)
              view-box [(.. svg -viewBox -baseVal -x)
                        (.. svg -viewBox -baseVal -y)
                        (.. svg -viewBox -baseVal -width)
                        (.. svg -viewBox -baseVal -height)]
              props {:width width
                     :mimetype "image/svg+xml"
                     :height header
                     :view-box view-box}]
          [(dom/get-outer-html g) props])))))

(defrecord CreateIcons [id files]
  ptk/WatchEvent
  (watch [_ state s]
    (letfn [(parse [file]
              (->> (files/read-as-text file)
                   (rx/map parse-svg)))
            (allowed? [file]
              (= (.-type file) "image/svg+xml"))
            (prepare [[content metadata]]
              {:collection id
               :content content
               :name (str "Icon " (gensym "i"))
               :metadata metadata})]
      (->> (rx/from-coll (jscoll->vec files))
           (rx/filter allowed?)
           (rx/flat-map parse)
           (rx/map prepare)
           (rx/flat-map #(rp/req :create/icon %))
           (rx/map :payload)
           (rx/map icon-created)))))

(defn create-icons
  [id files]
  {:pre [(or (uuid? id) (nil? id))]}
  (CreateIcons. id files))

;; --- Icon Persisted

(defrecord IconPersisted [id data]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:icons id] data)))

(defn icon-persisted
  [{:keys [id] :as data}]
  {:pre [(map? data)]}
  (IconPersisted. id data))

;; --- Persist Icon

(defrecord PersistIcon [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [icon (get-in state [:icons id])]
      (->> (rp/req :update/icon icon)
           (rx/map :payload)
           (rx/map icon-persisted)))))

(defn persist-icon
  [id]
  {:pre [(uuid? id)]}
  (PersistIcon. id))

;; --- Icons Fetched

(defrecord IconsFetched [items]
  ptk/UpdateEvent
  (update [_ state]
    (reduce (fn [state {:keys [id] :as icon}]
              (let [icon (assoc icon :type :icon)]
                (assoc-in state [:icons id] icon)))
            state
            items)))

(defn icons-fetched
  [items]
  (IconsFetched. items))

;; --- Load Icons

(defrecord FetchIcons [id]
  ptk/WatchEvent
  (watch [_ state s]
    (let [params {:coll id}]
      (->> (rp/req :fetch/icons params)
           (rx/map :payload)
           (rx/map icons-fetched)))))

(defn fetch-icons
  [id]
  {:pre [(or (uuid? id) (nil? id))]}
  (FetchIcons. id))

;; --- Delete Icons

(defrecord DeleteIcon [id]
  ptk/UpdateEvent
  (update [_ state]
    (-> state
        (update :icons dissoc id)
        (update-in [:dashboard :icons :selected] disj id)))

  ptk/WatchEvent
  (watch [_ state s]
    (->> (rp/req :delete/icon id)
         (rx/ignore))))

(defn delete-icon
  [id]
  {:pre [(uuid? id)]}
  (DeleteIcon. id))

;; --- Rename Icon

(defrecord RenameIcon [id name]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:icons id :name] name))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (persist-icon id))))

(defn rename-icon
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (RenameIcon. id name))

;; --- Select icon

(defrecord SelectIcon [id]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:dashboard :icons :selected] conj id)))

(defrecord DeselectIcon [id]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:dashboard :icons :selected] disj id)))

(defrecord ToggleIconSelection [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :icons :selected])]
      (rx/of
       (if (selected id)
         (DeselectIcon. id)
         (SelectIcon. id))))))

(defn deselect-icon
  [id]
  {:pre [(uuid? id)]}
  (DeselectIcon. id))

(defn toggle-icon-selection
  [id]
  (ToggleIconSelection. id))

;; --- Copy Selected Icon

(defrecord CopySelected [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :icons :selected])]
      (rx/merge
       (->> (rx/from-coll selected)
            (rx/map #(get-in state [:icons %]))
            (rx/map #(dissoc % :id))
            (rx/map #(assoc % :collection id))
            (rx/flat-map #(rp/req :create/icon %))
            (rx/map :payload)
            (rx/map icon-created))
       (->> (rx/from-coll selected)
            (rx/map deselect-icon))))))

(defn copy-selected
  [id]
  {:pre [(or (uuid? id) (nil? id))]}
  (CopySelected. id))

;; --- Move Selected Icon

(defrecord MoveSelected [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [selected (get-in state [:dashboard :icons :selected])]
      (reduce (fn [state icon]
                (assoc-in state [:icons icon :collection] id))
              state
              selected)))

  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :icons :selected])]
      (rx/merge
       (->> (rx/from-coll selected)
            (rx/map persist-icon))
       (->> (rx/from-coll selected)
            (rx/map deselect-icon))))))

(defn move-selected
  [id]
  {:pre [(or (uuid? id) (nil? id))]}
  (MoveSelected. id))

;; --- Delete Selected

(defrecord DeleteSelected []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :icons :selected])]
      (->> (rx/from-coll selected)
           (rx/map delete-icon)))))

(defn delete-selected
  []
  (DeleteSelected.))

;; --- Update Opts (Filtering & Ordering)

(defrecord UpdateOpts [order filter edition]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:dashboard :icons] merge
               {:edition edition}
               (when order {:order order})
               (when filter {:filter filter}))))

(defn update-opts
  [& {:keys [order filter edition]
      :or {edition false}
      :as opts}]
  (UpdateOpts. order filter edition))

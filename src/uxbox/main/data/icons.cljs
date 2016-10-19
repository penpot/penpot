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
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as r]
            [uxbox.util.dom.files :as files]
            [uxbox.main.state :as st]
            [uxbox.main.repo :as rp]))

;; --- Initialize

(declare fetch-icons)
(declare fetch-collections)
(declare collections-fetched?)

(defrecord Initialize [type id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [type (or type :own)
          data {:type type :id id :selected #{}}]
      (-> state
          (assoc-in [:dashboard :icons] data)
          (assoc-in [:dashboard :section] :dashboard/icons))))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/merge (rx/of (fetch-collections))
              (rx/of (fetch-icons id)))))

(defn initialize
  [type id]
  (Initialize. type id))

;; --- Select a Collection

(defrecord SelectCollection [type id]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (rx/of (r/navigate :dashboard/icons
                       {:type type :id id}))))

(defn select-collection
  ([type]
   (select-collection type nil))
  ([type id]
   {:pre [(keyword? type)]}
   (SelectCollection. type id)))

;; --- Collections Fetched

(defrecord CollectionsFetched [items]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce (fn [state item]
              (let [id (:id item)
                    item (assoc item :type :own)]
                (assoc-in state [:icon-colls-by-id id] item)))
            state
            items)))

(defn collections-fetched
  [items]
  (CollectionsFetched. items))

;; --- Fetch Collections

(defrecord FetchCollections []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (->> (rp/req :fetch/icon-collections)
         (rx/map :payload)
         (rx/map collections-fetched))))

(defn fetch-collections
  []
  (FetchCollections.))

;; --- Collection Created

(defrecord CollectionCreated [item]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [{:keys [id] :as item} (assoc item :type :own)]
      (update state :image-colls-by-id assoc id item)))

  rs/WatchEvent
  (-apply-watch [_ state stream]
    (rx/of (select-collection :own (:id item)))))

(defn collection-created
  [item]
  (CollectionCreated. item))

;; --- Create Collection

(defrecord CreateCollection []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [coll {:name "Unnamed collection"
                :id (uuid/random)}]
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
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:icon-colls-by-id (:id item)]  merge item)))

(defn collection-updated
  [item]
  (CollectionUpdated. item))

;; --- Update Collection

(defrecord UpdateCollection [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [item (get-in state [:icon-colls-by-id id])]
      (->> (rp/req :update/icon-collection item)
           (rx/map :payload)
           (rx/map collection-updated)))))

(defn update-collection
  [id]
  (UpdateCollection. id))

;; --- Rename Collection

(defrecord RenameCollection [id name]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:icon-colls-by-id id :name] name))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (update-collection id))))

(defn rename-collection
  [id name]
  (RenameCollection. id name))

;; --- Delete Collection

(defrecord DeleteCollection [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update state :icon-colls-by-id dissoc id))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [type (get-in state [:dashboard :images :type])]
      (->> (rp/req :delete/icon-collection id)
           (rx/map #(select-collection type))))))

(defn delete-collection
  [id]
  (DeleteCollection. id))

;; --- Icon Created

(defrecord IconCreated [item]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [{:keys [id] :as item} (assoc item :type :icon-raw)]
      (update state :icons-by-id assoc id item))))

(defn icon-created
  [item]
  (IconCreated. item))

;; --- Create Icon

(defn- parse-svg
  [data]
  {:pre [(string? data)]}
  (let [valid-tags #{"defs" "path" "circle" "rect" "metadata" "g"}
        div (js/document.createElement "div")
        gc  (js/document.createElement "div")
        g (js/document.createElementNS "http://www.w3.org/2000/svg" "g")]
    (set! (.-innerHTML div) data)
    (loop [child (.. div -firstChild -firstChild)]
      (if child
        (let [tagname (.-tagName child)]
          (if  (contains? valid-tags tagname)
            (.appendChild g child)
            (.appendChild gc child))
          (recur (.. div -firstChild -firstChild)))
        (let [svg (.-firstChild div)
              width (.. svg -width -baseVal -value)
              header (.. svg -height -baseVal -value)
              view-box [(.. svg -viewBox -baseVal -x)
                        (.. svg -viewBox -baseVal -y)
                        (.. svg -viewBox -baseVal -width)
                        (.. svg -viewBox -baseVal -height)]
              props {:width width
                     :mimetype "image/svg+xml"
                     :height header
                     :view-box view-box}]
          [(.-outerHTML g) props])))))

(defrecord CreateIcons [id files]
  rs/WatchEvent
  (-apply-watch [_ state s]
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

;; --- Icons Fetched

(defrecord IconsFetched [items]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce (fn [state {:keys [id] :as icon}]
              (let [icon (assoc icon :type :icon-raw)]
                (assoc-in state [:icons-by-id id] icon)))
            state
            items)))

(defn icons-fetched
  [items]
  (IconsFetched. items))

;; --- Load Icons

(defrecord FetchIcons [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
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
  rs/UpdateEvent
  (-apply-update [_ state]
    (-> state
        (update :icons-by-id dissoc id)
        (update-in [:dashboard :icons :selected] disj id)))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (->> (rp/req :delete/icon id)
         (rx/ignore))))

(defn delete-icon
  [id]
  {:pre [(uuid? id)]}
  (DeleteIcon. id))

;; --- Select icon

(defrecord SelectIcon [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :icons :selected] conj id)))

(defrecord DeselectIcon [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :icons :selected] disj id)))

(defrecord ToggleIconSelection [id]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (let [selected (get-in state [:dashboard :icons :selected])]
      (rx/of
       (if (selected id)
         (DeselectIcon. id)
         (SelectIcon. id))))))

(defn toggle-icon-selection
  [id]
  (ToggleIconSelection. id))

;; --- Delete Selected

(defrecord DeleteSelected []
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (let [selected (get-in state [:dashboard :icons :selected])]
      (->> (rx/from-coll selected)
           (rx/map delete-icon)))))

(defn delete-selected
  []
  (DeleteSelected.))

;; --- Update Opts (Filtering & Ordering)

(defrecord UpdateOpts [order filter]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :icons] merge
               (when order {:order order})
               (when filter {:filter filter}))))

(defn update-opts
  [& {:keys [order filter] :as opts}]
  (UpdateOpts. order filter))

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
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.util.data :refer (jscoll->vec)]
   [uxbox.util.dom :as dom]
   [uxbox.util.files :as files]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.router :as r]
   [uxbox.util.uuid :as uuid]))

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::created-at inst?)
(s/def ::modified-at inst?)
(s/def ::user-id uuid?)

;; (s/def ::collection-id (s/nilable ::us/uuid))

;; (s/def ::mimetype string?)
;; (s/def ::thumbnail us/url-str?)
;; (s/def ::width number?)
;; (s/def ::height number?)
;; (s/def ::url us/url-str?)

(s/def ::collection
  (s/keys :req-un [::id
                   ::name
                   ::created-at
                   ::modified-at
                   ::user-id]))

;; --- Collections Fetched

(defn collections-fetched
  [items]
  (s/assert (s/every ::collection) items)
  (ptk/reify ::collections-fetched
    cljs.core/IDeref
    (-deref [_] items)

    ptk/UpdateEvent
    (update [_ state]
      (reduce (fn [state {:keys [id user] :as item}]
                (let [type (if (uuid/zero? (:user-id item)) :builtin :own)
                      item (assoc item :type type)]
                  (assoc-in state [:icons-collections id] item)))
              state
              items))))

;; --- Fetch Collections

(def fetch-collections
  (ptk/reify ::fetch-collections
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query! :icons-collections)
           (rx/map collections-fetched)))))

;; --- Collection Created

(defn collection-created
  [item]
  (s/assert ::collection item)
  (ptk/reify ::collection-created
    ptk/UpdateEvent
    (update [_ state]
      (let [{:keys [id] :as item} (assoc item :type :own)]
        (update state :icons-collections assoc id item)))))

;; --- Create Collection

(def create-collection
  (ptk/reify ::create-collection
    ptk/WatchEvent
    (watch [_ state s]
      (let [name (tr "ds.default-library-title" (gensym "c"))
            data {:name name}]
        (->> (rp/mutation! :create-icons-collection data)
             (rx/map collection-created))))))

;; --- Collection Updated

(defn collection-updated
  [item]
  (ptk/reify ::collection-updated
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:icons-collections (:id item)] merge item))))

;; --- Update Collection

(defrecord UpdateCollection [id]
  ptk/WatchEvent
  (watch [_ state s]
    (let [data (get-in state [:icons-collections id])]
      (->> (rp/mutation! :update-icons-collection data)
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
      (->> (rp/mutation! :delete-icons-collection {:id id})
           (rx/map #(r/nav :dashboard-icons {:type type}))))))

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


(defn create-icons
  [id files]
  (s/assert (s/nilable uuid?) id)
  (ptk/reify ::create-icons
    ptk/WatchEvent
    (watch [_ state s]
      (letfn [(parse [file]
                (->> (files/read-as-text file)
                     (rx/map parse-svg)))
              (allowed? [file]
                (= (.-type file) "image/svg+xml"))
              (prepare [[content metadata]]
                {:collection-id id
                 :content content
                 :id (uuid/next)
                 ;; TODO Keep the name of the original icon
                 :name (str "Icon " (gensym "i"))
                 :metadata metadata})]
        (->> (rx/from files)
             (rx/filter allowed?)
             (rx/flat-map parse)
             (rx/map prepare)
             (rx/flat-map #(rp/mutation! :create-icon %))
             (rx/map icon-created))))))

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
    (let [data (get-in state [:icons id])]
      (->> (rp/mutation! :update-icon data)
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
    (let [params (cond-> {} id (assoc :collection-id id))]
      (->> (rp/query! :icons-by-collection params)
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
    (->> (rp/mutation! :delete-icon {:id id})
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
       (->> (rx/from selected)
            (rx/map #(get-in state [:icons %]))
            (rx/map #(dissoc % :id))
            (rx/map #(assoc % :collection-id id))
            (rx/flat-map #(rp/mutation :create-icon %))
            (rx/map :payload)
            (rx/map icon-created))
       (->> (rx/from selected)
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
       (->> (rx/from selected)
            (rx/map persist-icon))
       (->> (rx/from selected)
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
      (->> (rx/from selected)
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

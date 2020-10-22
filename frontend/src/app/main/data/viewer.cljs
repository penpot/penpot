;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.viewer
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.main.constants :as c]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.common.spec :as us]
   [app.common.pages :as cp]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.util.router :as rt]
   [app.common.uuid :as uuid]))

;; --- Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)

(s/def ::project (s/keys ::req-un [::id ::name]))
(s/def ::file (s/keys :req-un [::id ::name]))
(s/def ::page ::cp/page)

(s/def ::interactions-mode #{:hide :show :show-on-click})

(s/def ::bundle
  (s/keys :req-un [::project ::file ::page]))


;; --- Initialization

(declare fetch-bundle)
(declare bundle-fetched)

(defn initialize
  [{:keys [page-id file-id] :as params}]
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :viewer-local {:zoom 1
                                  :page-id page-id
                                  :file-id file-id
                                  :interactions-mode :hide
                                  :show-interactions? false

                                  :selected #{}
                                  :collapsed #{}
                                  :hover #{}}))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (fetch-bundle params)))))

;; --- Data Fetching

(defn fetch-bundle
  [{:keys [page-id file-id token]}]
  (ptk/reify ::fetch-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [params (cond-> {:page-id page-id
                            :file-id file-id}
                     (string? token) (assoc :share-token token))]
        (->> (rp/query :viewer-bundle params)
             (rx/map bundle-fetched)
             #_(rx/catch (fn [error-data]
                         (rx/of (rt/nav :not-found)))))))))

(defn- extract-frames
  [objects]
  (let [root (get objects uuid/zero)]
    (->> (:shapes root)
         (map #(get objects %))
         (filter #(= :frame (:type %)))
         (reverse)
         (vec))))

(defn bundle-fetched
  [{:keys [project file page share-token] :as bundle}]
  (us/verify ::bundle bundle)
  (ptk/reify ::file-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (:objects page)
            frames  (extract-frames objects)]
        (-> state
            (assoc :viewer-data {:project project
                                 :objects objects
                                 :file file
                                 :page page
                                 :frames frames
                                 :share-token share-token}))))))

(def create-share-link
  (ptk/reify ::create-share-link
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file-id (get-in state [:viewer-local :file-id])
            page-id (get-in state [:viewer-local :page-id])]
        (->> (rp/mutation! :create-file-share-token {:file-id file-id
                                                     :page-id page-id})
             (rx/map (fn [{:keys [token]}]
                       #(assoc-in % [:viewer-data :share-token] token))))))))

(def delete-share-link
  (ptk/reify ::delete-share-link
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file-id (get-in state [:viewer-local :file-id])
            page-id (get-in state [:viewer-local :page-id])
            token   (get-in state [:viewer-data :share-token])]
        (->> (rp/mutation :delete-file-share-token {:file-id file-id
                                                    :page-id page-id
                                                    :token token})
             (rx/map (fn [_] #(update % :viewer-data dissoc :share-token))))))))

;; --- Zoom Management

(def increase-zoom
  (ptk/reify ::increase-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [increase #(nth c/zoom-levels
                           (+ (d/index-of c/zoom-levels %) 1)
                           (last c/zoom-levels))]
        (update-in state [:viewer-local :zoom] (fnil increase 1))))))

(def decrease-zoom
  (ptk/reify ::decrease-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [decrease #(nth c/zoom-levels
                           (- (d/index-of c/zoom-levels %) 1)
                           (first c/zoom-levels))]
        (update-in state [:viewer-local :zoom] (fnil decrease 1))))))

(def reset-zoom
  (ptk/reify ::reset-zoom
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :zoom] 1))))

(def zoom-to-50
  (ptk/reify ::zoom-to-50
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :zoom] 0.5))))

(def zoom-to-200
  (ptk/reify ::zoom-to-200
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :zoom] 2))))

;; --- Local State Management

(def toggle-thumbnails-panel
  (ptk/reify ::toggle-thumbnails-panel
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:viewer-local :show-thumbnails] not))))

(def select-prev-frame
  (ptk/reify ::select-prev-frame
    ptk/WatchEvent
    (watch [_ state stream]
      (let [route (:route state)
            screen (-> route :data :name keyword)
            qparams (get-in route [:params :query])
            pparams (get-in route [:params :path])
            index   (d/parse-integer (:index qparams))]
        (when (pos? index)
          (rx/of (rt/nav screen pparams (assoc qparams :index (dec index)))))))))

(def select-next-frame
  (ptk/reify ::select-prev-frame
    ptk/WatchEvent
    (watch [_ state stream]
      (let [route (:route state)
            screen (-> route :data :name keyword)
            qparams (get-in route [:params :query])
            pparams (get-in route [:params :path])
            index   (d/parse-integer (:index qparams))
            total   (count (get-in state [:viewer-data :frames]))]
        (when (< index (dec total))
          (rx/of (rt/nav screen pparams (assoc qparams :index (inc index)))))))))

(defn set-interactions-mode
  [mode]
  (us/verify ::interactions-mode mode)
  (ptk/reify ::set-interactions-mode
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:viewer-local :interactions-mode] mode)
          (assoc-in [:viewer-local :show-interactions?] (case mode
                                                          :hide false
                                                          :show true
                                                          :show-on-click false))))))

(declare flash-done)

(def flash-interactions
  (ptk/reify ::flash-interactions
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :show-interactions?] true))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (rx/filter (ptk/type? ::flash-interactions) stream)]
        (->> (rx/of flash-done)
             (rx/delay 500)
             (rx/take-until stopper))))))

(def flash-done
  (ptk/reify ::flash-done
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :show-interactions?] false))))

;; --- Navigation

(defn go-to-frame
  [frame-id]
  (us/verify ::us/uuid frame-id)
  (ptk/reify ::go-to-frame
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (get-in state [:viewer-local :page-id])
            file-id (get-in state [:viewer-local :file-id])
            frames  (get-in state [:viewer-data :frames])
            share-token  (get-in state [:viewer-data :share-token])
            index   (d/index-of-pred frames #(= (:id %) frame-id))]
        (rx/of (rt/nav :viewer {:page-id page-id :file-id file-id} {:token share-token
                                                                    :index index}))))))

;; --- Shortcuts

(def shortcuts
  {"+" #(st/emit! increase-zoom)
   "-" #(st/emit! decrease-zoom)
   "shift+0" #(st/emit! zoom-to-50)
   "shift+1" #(st/emit! reset-zoom)
   "shift+2" #(st/emit! zoom-to-200)
   "left" #(st/emit! select-prev-frame)
   "right" #(st/emit! select-next-frame)})


(defn deselect-all []
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :selected] #{}))))

(defn select-shape
  ([id] (select-shape id false))
  ([id toggle?]
   (ptk/reify ::select-shape
     ptk/UpdateEvent
     (update [_ state]
       (-> state
           (assoc-in [:viewer-local :selected] #{id}))))))

;; TODO
(defn collapse-all []
  (ptk/reify ::collapse-all))

(defn toggle-collapse [id]
  (ptk/reify ::toggle-collapse
    ptk/UpdateEvent
    (update [_ state]
      (let [toggled? (contains? (get-in state [:viewer-local :collapsed]) id)]
        (update-in state [:viewer-local :collapsed] (if toggled? disj conj) id)))))

(defn hover-shape [id hover?]
  (ptk/reify ::hover-shape
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:viewer-local :hover] (if hover? conj disj) id))))

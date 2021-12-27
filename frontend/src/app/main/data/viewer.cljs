;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.viewer
  (:require
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.types.interactions :as cti]
   [app.common.uuid :as uuid]
   [app.main.constants :as c]
   [app.main.data.comments :as dcm]
   [app.main.data.fonts :as df]
   [app.main.repo :as rp]
   [app.util.globals :as ug]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

;; --- Local State Initialization

(def ^:private
  default-local-state
  {:zoom 1
   :interactions-mode :hide
   :interactions-show? false
   :comments-mode :all
   :comments-show :unresolved
   :selected #{}
   :collapsed #{}
   :overlays []
   :hover nil})

(declare fetch-comment-threads)
(declare fetch-bundle)
(declare bundle-fetched)

(s/def ::file-id ::us/uuid)
(s/def ::index ::us/integer)
(s/def ::page-id (s/nilable ::us/uuid))
(s/def ::share-id (s/nilable ::us/uuid))
(s/def ::section ::us/string)

(s/def ::initialize-params
  (s/keys :req-un [::file-id]
          :opt-un [::share-id ::page-id]))

(defn initialize
  [{:keys [file-id] :as params}]
  (us/assert ::initialize-params params)
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :current-file-id file-id)
          (update :viewer-local
                  (fn [lstate]
                    (if (nil? lstate)
                      default-local-state
                      lstate)))))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (fetch-bundle params)
             (fetch-comment-threads params)))

    ptk/EffectEvent
    (effect [_ _ _]
      ;; Set the window name, the window name is used on inter-tab
      ;; navigation; in other words: when a user opens a tab with a
      ;; name, if there are already opened tab with that name, the
      ;; browser just focus the opened tab instead of creating new
      ;; tab.
      (let [name (str "viewer-" file-id)]
        (unchecked-set ug/global "name" name)))))

(defn finalize
  [_]
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :viewer))))

(defn select-frames
  [{:keys [objects] :as page}]
  (let [root (get objects uuid/zero)]
    (into [] (comp (map #(get objects %))
                   (filter #(= :frame (:type %))))
          (reverse (:shapes root)))))

;; --- Data Fetching

(s/def ::fetch-bundle-params
  (s/keys :req-un [::page-id ::file-id]
          :opt-un [::share-id]))

(defn fetch-bundle
  [{:keys [file-id share-id] :as params}]
  (us/assert ::fetch-bundle-params params)
  (ptk/reify ::fetch-file
    ptk/WatchEvent
    (watch [_ _ _]
      (let [params' (cond-> {:file-id file-id}
                      (uuid? share-id) (assoc :share-id share-id))]
        (->> (rp/query :view-only-bundle params')
             (rx/mapcat
              (fn [{:keys [fonts] :as bundle}]
                (rx/of (df/fonts-fetched fonts)
                       (bundle-fetched (merge bundle params))))))))))

(declare go-to-frame-auto)

(defn bundle-fetched
  [{:keys [project file share-links libraries users permissions] :as bundle}]
  (let [pages (->> (get-in file [:data :pages])
                   (map (fn [page-id]
                          (let [data (get-in file [:data :pages-index page-id])]
                            [page-id (assoc data :frames (select-frames data))])))
                   (into {}))]

    (ptk/reify ::bundle-fetched
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            (assoc :share-links share-links)
            (assoc :viewer {:libraries (d/index-by :id libraries)
                            :users (d/index-by :id users)
                            :permissions permissions
                            :project project
                            :pages pages
                            :file file})))

      ptk/WatchEvent
      (watch [_ state _]
        (let [route   (:route state)
              qparams (:query-params route)
              index   (:index qparams)]
          (when (nil? index)
            (rx/of (go-to-frame-auto))))))))

(defn fetch-comment-threads
  [{:keys [file-id page-id] :as params}]
  (letfn [(fetched [data state]
            (->> data
                 (filter #(= page-id (:page-id %)))
                 (d/index-by :id)
                 (assoc state :comment-threads)))
          (on-error [{:keys [type] :as err}]
            (if (or (= :authentication type)
                    (= :not-found type))
              (rx/empty)
              (rx/throw err)))]

    (ptk/reify ::fetch-comment-threads
      ptk/WatchEvent
      (watch [_ _ _]
        (->> (rp/query :comment-threads {:file-id file-id})
             (rx/map #(partial fetched %))
             (rx/catch on-error))))))

(defn refresh-comment-thread
  [{:keys [id file-id] :as thread}]
  (letfn [(fetched [thread state]
            (assoc-in state [:comment-threads id] thread))]
    (ptk/reify ::refresh-comment-thread
      ptk/WatchEvent
      (watch [_ _ _]
        (->> (rp/query :comment-thread {:file-id file-id :id id})
             (rx/map #(partial fetched %)))))))

(defn fetch-comments
  [{:keys [thread-id]}]
  (us/assert ::us/uuid thread-id)
  (letfn [(fetched [comments state]
            (update state :comments assoc thread-id (d/index-by :id comments)))]
    (ptk/reify ::retrieve-comments
      ptk/WatchEvent
      (watch [_ _ _]
        (->> (rp/query :comments {:thread-id thread-id})
             (rx/map #(partial fetched %)))))))

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

(def close-thumbnails-panel
  (ptk/reify ::close-thumbnails-panel
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :show-thumbnails] false))))

(def select-prev-frame
  (ptk/reify ::select-prev-frame
    ptk/WatchEvent
    (watch [_ state _]
      (let [route   (:route state)
            qparams (:query-params route)
            pparams (:path-params route)
            index   (:index qparams)]
        (when (pos? index)
          (rx/of
           (dcm/close-thread)
           (rt/nav :viewer pparams (assoc qparams :index (dec index)))))))))

(def select-next-frame
  (ptk/reify ::select-next-frame
    ptk/WatchEvent
    (watch [_ state _]
      (let [route   (:route state)
            pparams (:path-params route)
            qparams (:query-params route)

            page-id (:page-id qparams)
            index   (:index qparams)

            total   (count (get-in state [:viewer :pages page-id :frames]))]

        (when (< index (dec total))
          (rx/of
           (dcm/close-thread)
           (rt/nav :viewer pparams (assoc qparams :index (inc index)))))))))

(s/def ::interactions-mode #{:hide :show :show-on-click})

(defn set-interactions-mode
  [mode]
  (us/verify ::interactions-mode mode)
  (ptk/reify ::set-interactions-mode
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:viewer-local :interactions-mode] mode)
          (assoc-in [:viewer-local :interactions-show?] (case mode
                                                          :hide false
                                                          :show true
                                                          :show-on-click false))))))

(declare flash-done)

(def flash-interactions
  (ptk/reify ::flash-interactions
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :interactions-show?] true))

    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (rx/filter (ptk/type? ::flash-interactions) stream)]
        (->> (rx/of flash-done)
             (rx/delay 500)
             (rx/take-until stopper))))))

(def flash-done
  (ptk/reify ::flash-done
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :interactions-show?] false))))

(defn set-nav-scroll
  [scroll]
  (ptk/reify ::set-nav-scroll
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :nav-scroll] scroll))))

(defn reset-nav-scroll
  []
  (ptk/reify ::reset-nav-scroll
    ptk/UpdateEvent
    (update [_ state]
      (d/dissoc-in state [:viewer-local :nav-scroll]))))

(defn complete-animation
  []
  (ptk/reify ::complete-animation
    ptk/UpdateEvent
    (update [_ state]
      (d/dissoc-in state [:viewer-local :current-animation]))))

;; --- Navigation inside page

(defn go-to-frame-by-index
  [index]
  (ptk/reify ::go-to-frame-by-index
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :overlays] []))

    ptk/WatchEvent
    (watch [_ state _]
      (let [route   (:route state)
            screen  (-> route :data :name keyword)
            qparams (:query-params route)
            pparams (:path-params route)]
        (rx/of (rt/nav screen pparams (assoc qparams :index index)))))))

(defn go-to-frame
  ([frame-id] (go-to-frame frame-id nil))
  ([frame-id animation]
   (us/verify ::us/uuid frame-id)
   (us/verify (s/nilable ::cti/animation) animation)
   (ptk/reify ::go-to-frame
     ptk/UpdateEvent
     (update [_ state]
       (let [route   (:route state)
             qparams (:query-params route)
             page-id (:page-id qparams)
             index   (:index qparams)
             frames  (get-in state [:viewer :pages page-id :frames])
             frame   (get frames index)]
         (cond-> state
           :always
           (assoc-in [:viewer-local :overlays] [])

           (some? animation)
           (assoc-in [:viewer-local :current-animation]
                     {:kind :go-to-frame
                      :orig-frame-id (:id frame)
                      :animation animation}))))

     ptk/WatchEvent
     (watch [_ state _]
       (let [route   (:route state)
             qparams (:query-params route)
             page-id (:page-id qparams)
             frames  (get-in state [:viewer :pages page-id :frames])
             index   (d/index-of-pred frames #(= (:id %) frame-id))]
         (when index
           (rx/of (go-to-frame-by-index index))))))))

(defn go-to-frame-auto
  []
  (ptk/reify ::go-to-frame-auto
    ptk/WatchEvent
    (watch [_ state _]
      (let [route   (:route state)
            qparams (:query-params route)
            page-id (:page-id qparams)
            flows   (get-in state [:viewer :pages page-id :options :flows])]
        (if (seq flows)
          (let [frame-id (:starting-frame (first flows))]
            (rx/of (go-to-frame frame-id)))
          (rx/of (go-to-frame-by-index 0)))))))

(defn go-to-section
  [section]
  (ptk/reify ::go-to-section
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :overlays] []))

    ptk/WatchEvent
    (watch [_ state _]
      (let [route   (:route state)
            pparams (:path-params route)
            qparams (:query-params route)]
        (rx/of (rt/nav :viewer pparams (assoc qparams :section section)))))))

;; --- Overlays

(defn- do-open-overlay
  [state frame position close-click-outside background-overlay animation]
  (cond-> state
    :always
    (update-in [:viewer-local :overlays] conj 
               {:frame frame
                :position position
                :close-click-outside close-click-outside
                :background-overlay background-overlay})
    (some? animation)
    (assoc-in [:viewer-local :current-animation]
              {:kind :open-overlay
               :overlay-id (:id frame)
               :animation animation})))

(defn- do-close-overlay
  [state frame-id animation]
  (if (nil? animation)
    (update-in state [:viewer-local :overlays]
               (fn [overlays]
                 (d/removev #(= (:id (:frame %)) frame-id) overlays)))
    (assoc-in state [:viewer-local :current-animation]
              {:kind :close-overlay
               :overlay-id frame-id
               :animation animation})))

(defn open-overlay
  [frame-id position close-click-outside background-overlay animation]
  (us/verify ::us/uuid frame-id)
  (us/verify ::us/point position)
  (us/verify (s/nilable ::us/boolean) close-click-outside)
  (us/verify (s/nilable ::us/boolean) background-overlay)
  (us/verify (s/nilable ::cti/animation) animation)
  (ptk/reify ::open-overlay
    ptk/UpdateEvent
    (update [_ state]
      (let [route    (:route state)
            qparams  (:query-params route)
            page-id  (:page-id qparams)
            frames   (get-in state [:viewer :pages page-id :frames])
            frame    (d/seek #(= (:id %) frame-id) frames)
            overlays (get-in state [:viewer-local :overlays])]
        (if-not (some #(= (:frame %) frame) overlays)
          (do-open-overlay state
                           frame
                           position
                           close-click-outside
                           background-overlay
                           animation)
          state)))))

(defn toggle-overlay
  [frame-id position close-click-outside background-overlay animation]
  (us/verify ::us/uuid frame-id)
  (us/verify ::us/point position)
  (us/verify (s/nilable ::us/boolean) close-click-outside)
  (us/verify (s/nilable ::us/boolean) background-overlay)
  (us/verify (s/nilable ::cti/animation) animation)
  (ptk/reify ::toggle-overlay
    ptk/UpdateEvent
    (update [_ state]
      (let [route    (:route state)
            qparams  (:query-params route)
            page-id  (:page-id qparams)
            frames   (get-in state [:viewer :pages page-id :frames])
            frame    (d/seek #(= (:id %) frame-id) frames)
            overlays (get-in state [:viewer-local :overlays])]
        (if-not (some #(= (:frame %) frame) overlays)
          (do-open-overlay state
                           frame
                           position
                           close-click-outside
                           background-overlay
                           animation)
          (do-close-overlay state
                            (:id frame)
                            (cti/invert-direction animation)))))))

(defn close-overlay
  ([frame-id] (close-overlay frame-id nil))
  ([frame-id animation]
   (us/verify ::us/uuid frame-id)
   (us/verify (s/nilable ::cti/animation) animation)
   (ptk/reify ::close-overlay
     ptk/UpdateEvent
     (update [_ state]
       (do-close-overlay state
                         frame-id
                         animation)))))

;; --- Objects selection

(defn deselect-all []
  (ptk/reify ::deselect-all
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :selected] #{}))))

(defn select-shape
  ([id]
   (ptk/reify ::select-shape
     ptk/UpdateEvent
     (update [_ state]
       (-> state
           (assoc-in [:viewer-local :selected] #{id}))))))

(defn toggle-selection
  [id]
  (ptk/reify ::toggle-selection
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (get-in state [:viewer-local :selected])]
        (cond-> state
          (not (selected id)) (update-in [:viewer-local :selected] conj id)
          (selected id)       (update-in [:viewer-local :selected] disj id))))))

(defn shift-select-to
  [id]
  (ptk/reify ::shift-select-to
    ptk/UpdateEvent
    (update [_ state]
      (let [route     (:route state)
            qparams   (:query-params route)
            page-id   (:page-id qparams)
            objects   (get-in state [:viewer :pages page-id :objects])
            selection (-> state
                          (get-in [:viewer-local :selected] #{})
                          (conj id))]
        (-> state
            (assoc-in [:viewer-local :selected]
                      (cp/expand-region-selection objects selection)))))))

(defn select-all
  []
  (ptk/reify ::select-all
    ptk/UpdateEvent
    (update [_ state]
      (let [route     (:route state)
            qparams   (:query-params route)
            page-id   (:page-id qparams)
            index     (:index qparams)
            objects   (get-in state [:viewer :pages page-id :objects])
            frame-id  (get-in state [:viewer :pages page-id :frames index :id])

            selection (->> objects
                           (filter #(= (:frame-id (second %)) frame-id))
                           (map first)
                           (into #{frame-id}))]
        (-> state
            (assoc-in [:viewer-local :selected] selection))))))

(defn toggle-collapse [id]
  (ptk/reify ::toggle-collapse
    ptk/UpdateEvent
    (update [_ state]
      (let [toggled? (contains? (get-in state [:viewer-local :collapsed]) id)]
        (update-in state [:viewer-local :collapsed] (if toggled? disj conj) id)))))

(defn hover-shape
  [id hover?]
  (ptk/reify ::hover-shape
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :hover] (when hover? id)))))

;; --- Navigation outside page

(defn go-to-dashboard
  []
  (ptk/reify ::go-to-dashboard
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (get-in state [:viewer :project :team-id])
            params  {:team-id team-id}]
        (rx/of (rt/nav :dashboard-projects params))))))

(defn go-to-page
  [page-id]
  (ptk/reify ::go-to-page
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :overlays] []))

    ptk/WatchEvent
     (watch [_ state _]
       (let [route   (:route state)
             pparams (:path-params route)
             qparams (-> (:query-params route)
                         (assoc :index 0)
                         (assoc :page-id page-id))
             rname   (get-in route [:data :name])]
         (rx/of (rt/nav rname pparams qparams))))))

(defn go-to-workspace
  ([] (go-to-workspace nil))
  ([page-id]
   (ptk/reify ::go-to-workspace
     ptk/WatchEvent
     (watch [_ state _]
       (let [route   (:route state)
             project-id (get-in state [:viewer :project :id])
             file-id    (get-in state [:viewer :file :id])
             saved-page-id   (get-in route [:query-params :page-id])
             pparams    {:project-id project-id :file-id file-id}
             qparams    {:page-id (or page-id saved-page-id)}]
         (rx/of (rt/nav-new-window*
                 {:rname :workspace
                  :path-params pparams
                  :query-params qparams
                  :name (str "workspace-" file-id)})))))))

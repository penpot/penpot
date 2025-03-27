;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.viewer
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.features :as cfeat]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.transit :as t]
   [app.common.types.shape-tree :as ctt]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.main.data.comments :as dcmt]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.fonts :as df]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.util.globals :as ug]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; --- Local State Initialization

(def ^:private
  default-local-state
  {:zoom 1
   :fullscreen? false
   :interactions-mode :show-on-click
   :show-interactions false
   :comments-mode :all
   :comments-show :unresolved
   :selected #{}
   :collapsed #{}
   :hover nil
   :share-id ""
   :file-comments-users []})

(declare fetch-comment-threads)
(declare fetch-bundle)
(declare bundle-fetched)
(declare zoom-to-fill)
(declare zoom-to-fit)

(def ^:private
  schema:initialize
  [:map {:title "initialize"}
   [:file-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]
   [:page-id {:optional true} ::sm/uuid]])

(defn initialize
  [{:keys [file-id share-id] :as params}]
  (dm/assert!
   "expected valid params"
   (sm/check schema:initialize params))

  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :current-file-id file-id)
          (assoc :current-share-id share-id)
          (update :viewer-local
                  (fn [lstate]
                    (if (nil? lstate)
                      default-local-state
                      lstate)))
          (assoc-in [:viewer-local :share-id] share-id)))

    ptk/WatchEvent
    (watch [_ state _]
      (rx/of (fetch-bundle (d/without-nils params))
             ;; Only fetch threads for logged-in users
             (when (some? (:profile state))
               (fetch-comment-threads params))))

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

;; --- Data Fetching

(def ^:private
  schema:fetch-bundle
  [:map {:title "fetch-bundle"}
   [:page-id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:share-id {:optional true} ::sm/uuid]])

(defn- fetch-bundle
  [{:keys [file-id share-id] :as params}]

  (dm/assert!
   "expected valid params"
   (sm/check schema:fetch-bundle params))

  (ptk/reify ::fetch-bundle
    ptk/WatchEvent
    (watch [_ _ _]
      (let [;; NOTE: in viewer we don't have access to the team when
            ;; user is not logged-in, so we can't know which features
            ;; are active from team, so in this case it is necesary
            ;; report the whole set of supported features instead of
            ;; the enabled ones.
            features cfeat/supported-features
            params'  (cond-> {:file-id file-id :features features}
                       (uuid? share-id)
                       (assoc :share-id share-id))

            resolve  (fn [[key pointer]]
                       (let [params {:file-id file-id :fragment-id @pointer}
                             params (cond-> params
                                      (uuid? share-id)
                                      (assoc :share-id share-id))]
                         (->> (rp/cmd! :get-file-fragment params)
                              (rx/map :data)
                              (rx/map #(vector key %)))))]

        (->> (rp/cmd! :get-view-only-bundle params')
             (rx/mapcat
              (fn [bundle]
                (->> (rx/from (-> bundle :file :data :pages-index seq))
                     (rx/merge-map
                      (fn [[_ page :as kp]]
                        (if (t/pointer? page)
                          (resolve kp)
                          (rx/of kp))))
                     (rx/reduce conj {})
                     (rx/map (fn [pages-index]
                               (update-in bundle [:file :data] assoc :pages-index pages-index))))))
             (rx/mapcat
              (fn [bundle]
                (->> (rx/from (-> bundle :file :data seq))
                     (rx/merge-map
                      (fn [[_ object :as kp]]
                        (if (t/pointer? object)
                          (resolve kp)
                          (rx/of kp))))
                     (rx/reduce conj {})
                     (rx/map (fn [data]
                               (update bundle :file assoc :data data))))))
             (rx/mapcat
              (fn [{:keys [fonts team] :as bundle}]
                (rx/of (df/fonts-fetched fonts)
                       (features/initialize (:features team))
                       (bundle-fetched (merge bundle params))))))))))

(declare go-to-frame)
(declare go-to-frame-by-index)
(declare go-to-frame-auto)

(defn bundle-fetched
  [{:keys [project file team share-links libraries users permissions thumbnails] :as bundle}]
  (let [pages (->> (dm/get-in file [:data :pages])
                   (map (fn [page-id]
                          (let [data (get-in file [:data :pages-index page-id])]
                            [page-id (assoc data
                                            :frames (ctt/get-viewer-frames (:objects data))
                                            :all-frames (ctt/get-viewer-frames (:objects data) {:all-frames? true}))])))
                   (into {}))]

    (ptk/reify ::bundle-fetched
      ptk/UpdateEvent
      (update [_ state]
        (let [team-id (:id team)
              team    {:members users}]
          (-> state
              (assoc :share-links share-links)
              (assoc :current-team-id team-id)
              (assoc :teams {team-id team})
              (assoc :viewer {:libraries (d/index-by :id libraries)
                              :users (d/index-by :id users)
                              :permissions permissions
                              :project project
                              :pages pages
                              :thumbnails thumbnails
                              :file file}))))

      ptk/WatchEvent
      (watch [_ state _]
        (let [route    (:route state)
              qparams  (:query-params route)
              index    (some-> (:index qparams) parse-long)
              frame-id (some-> (:frame-id qparams) uuid/parse)]
          (rx/merge
           (rx/of (case (:zoom qparams)
                    "fit" zoom-to-fit
                    "fill" zoom-to-fill
                    nil))
           (rx/of
            (cond
              (some? frame-id) (go-to-frame frame-id)
              (some? index) (go-to-frame-by-index index)
              :else (go-to-frame-auto)))))))))

(defn fetch-comment-threads
  [{:keys [file-id page-id share-id] :as params}]
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
        (->> (rp/cmd! :get-comment-threads {:file-id file-id :share-id share-id})
             (rx/map #(partial fetched %))
             (rx/catch on-error))))))

(defn refresh-comment-thread
  [{:keys [id file-id] :as thread}]
  (letfn [(fetched [thread state]
            (assoc-in state [:comment-threads id] thread))]
    (ptk/reify ::refresh-comment-thread
      ptk/WatchEvent
      (watch [_ _ _]
        (->> (rp/cmd! :get-comment-thread {:file-id file-id :id id})
             (rx/map #(partial fetched %)))))))

(defn fetch-comments
  [{:keys [thread-id]}]
  (dm/assert! (uuid thread-id))
  (letfn [(fetched [comments state]
            (update state :comments assoc thread-id (d/index-by :id comments)))]
    (ptk/reify ::retrieve-comments
      ptk/WatchEvent
      (watch [_ _ _]
        (->> (rp/cmd! :get-comments {:thread-id thread-id})
             (rx/map #(partial fetched %)))))))

;; --- Zoom Management

(def update-zoom-querystring
  (ptk/reify ::update-zoom-querystring
    ptk/WatchEvent
    (watch [_ state _]
      (let [zoom-type (get-in state [:viewer-local :zoom-type])
            params    (rt/get-params state)]

        (rx/of (rt/nav :viewer (assoc params :zoom zoom-type)))))))

(def increase-zoom
  (ptk/reify ::increase-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [increase #(min (* % 1.3) 200)]
        (-> state
            (update-in  [:viewer-local :zoom] (fnil increase 1))
            (d/dissoc-in  [:viewer-local :zoom-type]))))))

(def decrease-zoom
  (ptk/reify ::decrease-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [decrease #(max (/ % 1.3) 0.01)]
        (-> state
            (update-in [:viewer-local :zoom] (fnil decrease 1))
            (d/dissoc-in  [:viewer-local :zoom-type]))))))

(def reset-zoom
  (ptk/reify ::reset-zoom
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in  [:viewer-local :zoom] 1)
          (d/dissoc-in  [:viewer-local :zoom-type])))))

(def zoom-to-fit
  (ptk/reify ::zoom-to-fit
    ptk/UpdateEvent
    (update [_ state]
      (let [params (rt/get-params state)
            page-id (some-> (:page-id params) uuid/parse)
            index   (some-> (:index params) parse-long)

            frames  (dm/get-in state [:viewer :pages page-id :frames])
            srect   (-> (nth frames index)
                        (get :selrect))
            osize   (dm/get-in state [:viewer-local :viewport-size])
            wdiff   (/ (:width osize) (:width srect))
            hdiff   (/ (:height osize) (:height srect))
            minzoom (min wdiff hdiff)]
        (-> state
            (assoc-in  [:viewer-local :zoom] minzoom)
            (assoc-in  [:viewer-local :zoom-type] :fit))))

    ptk/WatchEvent
    (watch [_ _ _] (rx/of update-zoom-querystring))))

(def zoom-to-fill
  (ptk/reify ::zoom-to-fill
    ptk/UpdateEvent
    (update [_ state]

      (let [params (rt/get-params state)
            page-id (some-> (:page-id params) uuid/parse)
            index   (some-> (:index params) parse-long)

            frames  (dm/get-in state [:viewer :pages page-id :frames])
            srect   (-> (nth frames index)
                        (get :selrect))

            osize   (dm/get-in state [:viewer-local :viewport-size])

            wdiff   (/ (:width osize) (:width srect))
            hdiff   (/ (:height osize) (:height srect))

            maxzoom (max wdiff hdiff)]
        (-> state
            (assoc-in  [:viewer-local :zoom] maxzoom)
            (assoc-in  [:viewer-local :zoom-type] :fill))))

    ptk/WatchEvent
    (watch [_ _ _] (rx/of update-zoom-querystring))))

(def toggle-zoom-style
  (ptk/reify ::toggle-zoom-style
    ptk/WatchEvent
    (watch [_ state _]
      (let [zoom-type (get-in state [:viewer-local :zoom-type])]
        (if (= zoom-type :fit)
          (rx/of zoom-to-fill)
          (rx/of zoom-to-fit))))))

(def toggle-fullscreen
  (ptk/reify ::toggle-fullscreen
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:viewer-local :fullscreen?] not))))

(defn exit-fullscreen
  []
  (ptk/reify ::exit-fullscreen
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :fullscreen?] false))))

(defn set-viewport-size
  [{:keys [size]}]
  (ptk/reify ::set-viewport-size
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :viewport-size] size))))

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
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (dissoc :viewer-animations)
          (assoc  :viewer-overlays [])))

    ptk/WatchEvent
    (watch [_ state _]
      (let [params  (rt/get-params state)
            index   (some-> params :index parse-long)]
        (when (pos? index)
          (rx/of
           (dcmt/close-thread)
           (rt/nav :viewer (assoc params :index (dec index)))))))))

(def select-next-frame
  (ptk/reify ::select-next-frame
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (dissoc :viewer-animations)
          (assoc  :viewer-overlays [])))
    ptk/WatchEvent
    (watch [_ state _]
      (let [params  (rt/get-params state)
            index   (some-> params :index parse-long)
            page-id (some-> params :page-id parse-uuid)

            total   (count (get-in state [:viewer :pages page-id :frames]))]

        (when (< index (dec total))
          (rx/of
           (dcmt/close-thread)
           (rt/nav :viewer (assoc params :index (inc index)))))))))

(def select-first-frame
  (ptk/reify ::select-first-frame
    ptk/WatchEvent
    (watch [_ state _]
      (let [params (rt/get-params state)]
        (rx/of
         (dcmt/close-thread)
         (rt/nav :viewer (assoc params :index 0)))))))

(def valid-interaction-modes
  #{:hide :show :show-on-click})

(defn set-interactions-mode
  [mode]
  (dm/assert!
   "expected valid interaction mode"
   (contains? valid-interaction-modes mode))
  (ptk/reify ::set-interactions-mode
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:viewer-local :interactions-mode] mode)
          (assoc-in [:viewer-local :show-interactions] (case mode
                                                         :hide false
                                                         :show true
                                                         :show-on-click false))))
    ptk/WatchEvent
    (watch [_ state _]
      (let [params  (rt/get-params state)]
        (rx/of (rt/nav :viewer (assoc params :interactions-mode mode)))))))

(declare flash-done)

(defn flash-interactions
  []
  (ptk/reify ::flash-interactions
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :show-interactions] true))

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
      (assoc-in state [:viewer-local :show-interactions] false))))

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
      (dissoc state :viewer-animations))))

;; --- Navigation inside page

(defn go-to-frame-by-index
  [index]
  (ptk/reify ::go-to-frame-by-index
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :viewer-overlays []))

    ptk/WatchEvent
    (watch [_ state _]
      (let [params  (rt/get-params state)]
        (rx/of (rt/nav :viewer (assoc params :index index)))))))

(defn go-to-frame
  ([frame-id]
   (go-to-frame frame-id nil))

  ([frame-id animation]
   (dm/assert!
    "expected valid parameters"
    (and (uuid? frame-id)
         (or (nil? animation)
             (ctsi/check-animation! animation))))

   (ptk/reify ::go-to-frame
     ptk/UpdateEvent
     (update [_ state]
       (let [route   (:route state)
             qparams (:query-params route)
             page-id (some-> (:page-id qparams) uuid/parse)
             index   (some-> (:index qparams) parse-long)
             frames  (get-in state [:viewer :pages page-id :frames])
             frame   (get frames index)]
         (cond-> state
           :always
           (assoc :viewer-overlays [])

           (some? animation)
           (assoc-in [:viewer-animations (:id frame)]
                     {:kind :go-to-frame
                      :orig-frame-id (:id frame)
                      :animation animation}))))

     ptk/WatchEvent
     (watch [_ state _]
       (let [route   (:route state)
             qparams (:query-params route)
             page-id (some-> (:page-id qparams) uuid/parse)
             frames  (get-in state [:viewer :pages page-id :frames])
             index   (d/index-of-pred frames #(= (:id %) frame-id))]
         (rx/of (go-to-frame-by-index (or index 0))))))))

(defn go-to-frame-auto
  []
  (ptk/reify ::go-to-frame-auto
    ptk/WatchEvent
    (watch [_ state _]
      (let [route   (:route state)
            qparams (:query-params route)
            page-id (some-> (:page-id qparams) uuid/parse)
            flows   (get-in state [:viewer :pages page-id :options :flows])]
        (if (seq flows)
          (let [frame-id (:starting-frame (first flows))]
            (rx/of (go-to-frame frame-id)))
          (rx/of (go-to-frame-by-index 0)))))))

(defn go-to-section
  [section]
  (ptk/reify ::go-to-section
    ev/Event
    (-data [_]
      {::ev/origin "viewer"
       :section (name section)})

    ptk/UpdateEvent
    (update [_ state]
      (assoc state :viewer-overlays []))

    ptk/WatchEvent
    (watch [_ state _]
      (let [params (rt/get-params state)]
        (rx/of (rt/nav :viewer (assoc params :section section)))))))

;; --- Overlays

(defn- open-overlay*
  [state frame position snap-to close-click-outside background-overlay animation fixed-source?]
  (cond-> state
    :always
    (update :viewer-overlays conj
            {:frame frame
             :id (:id frame)
             :position position
             :snap-to snap-to
             :close-click-outside close-click-outside
             :background-overlay background-overlay
             :animation animation
             :fixed-source? fixed-source?})

    (some? animation)
    (assoc-in [:viewer-animations (:id frame)]
              {:kind :open-overlay
               :overlay-id (:id frame)
               :animation animation})))

(defn- close-overlay*
  [state frame-id animation]
  (if (nil? animation)
    (update state :viewer-overlays
            (fn [overlays]
              (d/removev #(= (:id (:frame %)) frame-id) overlays)))
    (assoc-in state [:viewer-animations frame-id]
              {:kind :close-overlay
               :overlay-id frame-id
               :animation animation})))

(defn open-overlay
  [frame-id position snap-to close-click-outside background-overlay animation fixed-source?]
  (dm/assert! (uuid? frame-id))
  (dm/assert! (gpt/point? position))
  (dm/assert! (or (nil? close-click-outside)
                  (boolean? close-click-outside)))
  (dm/assert! (or (nil? background-overlay)
                  (boolean? background-overlay)))
  (dm/assert! (or (nil? animation)
                  (ctsi/check-animation! animation)))
  (ptk/reify ::open-overlay
    ptk/UpdateEvent
    (update [_ state]
      (let [route    (:route state)
            qparams  (:query-params route)
            page-id  (some-> (:page-id qparams) uuid/parse)
            frames   (dm/get-in state [:viewer :pages page-id :all-frames])
            frame    (d/seek #(= (:id %) frame-id) frames)
            overlays (:viewer-overlays state)]
        (if-not (some #(= (:frame %) frame) overlays)
          (open-overlay* state
                         frame
                         position
                         snap-to
                         close-click-outside
                         background-overlay
                         animation
                         fixed-source?)
          state)))))


(defn toggle-overlay
  [frame-id position snap-to close-click-outside background-overlay animation fixed-source?]
  (dm/assert! (uuid? frame-id))
  (dm/assert! (gpt/point? position))
  (dm/assert! (or (nil? close-click-outside)
                  (boolean? close-click-outside)))
  (dm/assert! (or (nil? background-overlay)
                  (boolean? background-overlay)))
  (dm/assert! (or (nil? animation)
                  (ctsi/check-animation! animation)))

  (ptk/reify ::toggle-overlay
    ptk/UpdateEvent
    (update [_ state]
      (let [route    (:route state)
            qparams  (:query-params route)
            page-id  (some-> (:page-id qparams) uuid/parse)
            frames   (get-in state [:viewer :pages page-id :all-frames])
            frame    (d/seek #(= (:id %) frame-id) frames)
            overlays (:viewer-overlays state)]
        (if-not (some #(= (:frame %) frame) overlays)
          (open-overlay* state
                         frame
                         position
                         snap-to
                         close-click-outside
                         background-overlay
                         animation
                         fixed-source?)
          (close-overlay* state
                          (:id frame)
                          (ctsi/invert-direction animation)))))))

(defn close-overlay
  ([frame-id] (close-overlay frame-id nil))
  ([frame-id animation]
   (dm/assert! (uuid? frame-id))
   (dm/assert! (or (nil? animation)
                   (ctsi/check-animation! animation)))

   (ptk/reify ::close-overlay
     ptk/UpdateEvent
     (update [_ state]
       (close-overlay* state
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
            page-id   (some-> (:page-id qparams) uuid/parse)
            objects   (get-in state [:viewer :pages page-id :objects])
            selection (-> state
                          (get-in [:viewer-local :selected] #{})
                          (conj id))]
        (-> state
            (assoc-in [:viewer-local :selected]
                      (cfh/expand-region-selection objects selection)))))))

(defn select-all
  []
  (ptk/reify ::select-all
    ptk/UpdateEvent
    (update [_ state]
      (let [route     (:route state)
            qparams   (:query-params route)
            page-id   (some-> (:page-id qparams) uuid/parse)
            index     (some-> (:index qparams) parse-long)
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
      (let [team-id (get-in state [:viewer :project :team-id])]
        (rx/of (dcm/go-to-dashboard-recent :team-id team-id))))))

(defn go-to-page
  [page-id]
  (ptk/reify ::go-to-page
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :overlays] []))

    ptk/WatchEvent
    (watch [_ state _]
      (let [params (-> (rt/get-params state)
                       (assoc :index 0)
                       (assoc :page-id page-id))]
        (rx/of (rt/nav :viewer params))))))

(defn go-to-workspace
  ([] (go-to-workspace nil))
  ([page-id]
   (ptk/reify ::go-to-workspace
     ptk/WatchEvent
     (watch [_ state _]
       (let [params  (rt/get-params state)
             file-id (get-in state [:viewer :file :id])
             team-id (get-in state [:viewer :project :team-id])
             page-id (or page-id
                         (some-> (:page-id params) uuid/parse))
             params {:page-id page-id
                     :file-id file-id
                     :team-id team-id}
             name   (dm/str "workspace-" file-id)]

         (rx/of (rt/nav :workspace params
                        ::rt/new-window true
                        ::rt/window-name name)))))))

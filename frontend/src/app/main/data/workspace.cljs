;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace
  (:require
   [cuerdas.core :as str]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.pages-helpers :as cph]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.constants :as c]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.notifications :as dwn]
   [app.main.data.workspace.persistence :as dwp]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.texts :as dwtxt]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.colors :as mdc]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.worker :as uw]
   [app.util.router :as rt]
   [app.util.timers :as ts]
   [app.util.transit :as t]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [potok.core :as ptk]))

;; --- Specs

(s/def ::shape-attrs ::cp/shape-attrs)
(s/def ::set-of-string
  (s/every string? :kind set?))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare file-initialized)

;; --- Initialize Workspace

(s/def ::layout-flag
  #{:sitemap
    :sitemap-pages
    :layers
    :comments
    :assets
    :document-history
    :colorpalette
    :element-options
    :rules
    :display-grid
    :snap-grid
    :dynamic-alignment})

(s/def ::layout-flags (s/coll-of ::layout-flag))

(def default-layout
  #{:sitemap
    :sitemap-pages
    :layers
    :element-options
    :rules
    :display-grid
    :snap-grid
    :dynamic-alignment})

(s/def ::options-mode #{:design :prototype})

(def workspace-local-default
  {:zoom 1
   :flags #{}
   :selected (d/ordered-set)
   :expanded {}
   :tooltip nil
   :options-mode :design
   :draw-interaction-to nil
   :left-sidebar? true
   :right-sidebar? true
   :color-for-rename nil
   :selected-palette :recent
   :selected-palette-size :big
   :picking-color? false
   :picked-color nil
   :picked-color-select false})

(def initialize-layout
  (ptk/reify ::initialize-layout
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-layout default-layout))))

(defn initialize-file
  [project-id file-id]
  (us/verify ::us/uuid project-id)
  (us/verify ::us/uuid file-id)

  (ptk/reify ::initialize-file
    ptk/UpdateEvent
    (update [_ state]
      (assoc state
             :workspace-presence {}))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/merge
       (rx/of (dwp/fetch-bundle project-id file-id))

       ;; Initialize notifications (websocket connection) and the file persistence
       (->> stream
            (rx/filter (ptk/type? ::dwp/bundle-fetched))
            (rx/first)
            (rx/mapcat #(rx/of (dwn/initialize file-id)
                               (dwp/initialize-file-persistence file-id))))

       ;; Initialize Indexes (webworker)
       (->> stream
            (rx/filter (ptk/type? ::dwp/bundle-fetched))
            (rx/map deref)
            (rx/map dwc/initialize-indices)
            (rx/first))

       ;; Mark file initialized when indexes are ready
       (->> stream
            (rx/filter #(= ::dwc/index-initialized %))
            (rx/map (constantly
                     (file-initialized project-id file-id))))
       ))))

(defn- file-initialized
  [project-id file-id]
  (ptk/reify ::file-initialized
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-file
              (fn [file]
                (if (= (:id file) file-id)
                  (assoc file :initialized true)
                  file))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [ignore-until (get-in state [:workspace-file :ignore-sync-until])
            needs-update? (some #(and (> (:modified-at %) (:synced-at %))
                                      (or (not ignore-until)
                                          (> (:modified-at %) ignore-until)))
                                (vals (get state :workspace-libraries)))]
        (when needs-update?
          (rx/of (dwl/notify-sync-file file-id)))))))

(defn finalize-file
  [project-id file-id]
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state
              :workspace-file
              :workspace-project
              :workspace-media-objects
              :workspace-users
              :workspace-persistence))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dwn/finalize file-id)
             ::dwp/finalize))))


(defn initialize-page
  [page-id]
  (ptk/reify ::initialize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [local (get-in state [:workspace-cache page-id] workspace-local-default)]
        (assoc state
               :current-page-id page-id   ; mainly used by events
               :workspace-local local)))))

(defn finalize-page
  [page-id]
  (us/verify ::us/uuid page-id)
  (ptk/reify ::finalize-page
    ptk/UpdateEvent
    (update [_ state]
      (let [local (:workspace-local state)]
        (-> state
            (assoc-in [:workspace-cache page-id] local)
            (dissoc :current-page-id))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace Page CRUD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def create-empty-page
  (ptk/reify ::create-empty-page
    ptk/WatchEvent
    (watch [this state stream]
      (let [id      (uuid/next)
            pages   (get-in state [:workspace-data :pages-index])
            unames  (dwc/retrieve-used-names pages)
            name    (dwc/generate-unique-name unames "Page")

            rchange {:type :add-page
                     :id id
                     :name name}
            uchange {:type :del-page
                     :id id}]
        (rx/of (dwc/commit-changes [rchange] [uchange] {:commit-local? true}))))))

(s/def ::rename-page
  (s/keys :req-un [::id ::name]))

(defn rename-page
  [id name]
  (us/verify ::us/uuid id)
  (us/verify string? name)
  (ptk/reify ::rename-page
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page (get-in state [:workspace-data :pages-index id])
            rchg {:type :mod-page
                  :id id
                  :name name}
            uchg {:type :mod-page
                  :id id
                  :name (:name page)}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(declare purge-page)
(declare go-to-file)

;; TODO: properly handle positioning on undo.

(defn delete-page
  [id]
  (ptk/reify ::delete-page
    ptk/WatchEvent
    (watch [_ state s]
      (let [page (get-in state [:workspace-data :pages-index id])
            rchg {:type :del-page
                  :id id}
            uchg {:type :add-page
                  :page page}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true})
               (when (= id (:current-page-id state))
                 go-to-file))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WORKSPACE File Actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rename-file
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (ptk/reify ::rename-file
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-file :name] name))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id :name name}]
        (->> (rp/mutation :rename-file params)
             (rx/ignore))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace State Manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Viewport Sizing

(declare zoom-to-fit-all)

(defn initialize-viewport
  [{:keys [width height] :as size}]
  (letfn [(update* [{:keys [vbox vport] :as local}]
            (let [wprop (/ (:width vport) width)
                  hprop (/ (:height vport) height)]
              (-> local
                  (assoc :vport size)
                  (update :vbox (fn [vbox]
                                    (-> vbox
                                        (update :width #(/ % wprop))
                                        (update :height #(/ % hprop))))))))

          (initialize [state local]
            (let [page-id (:current-page-id state)
                  objects (dwc/lookup-page-objects state page-id)
                  shapes  (cph/select-toplevel-shapes objects {:include-frames? true})
                  srect   (geom/selection-rect shapes)
                  local   (assoc local :vport size)]
              (cond
                (or (not (mth/finite? (:width srect)))
                    (not (mth/finite? (:height srect))))
                (assoc local :vbox (assoc size :x 0 :y 0 :left-offset 0))

                (or (> (:width srect) width)
                    (> (:height srect) height))
                (let [srect (geom/adjust-to-viewport size srect {:padding 40})
                      zoom  (/ (:width size) (:width srect))]
                  (-> local
                      (assoc :zoom zoom)
                      (update :vbox merge srect)))

                :else
                (assoc local :vbox (assoc size
                                          :x (- (:x srect) 40)
                                          :y (- (:y srect) 40))))))

          (setup [state local]
            (if (:vbox local)
              (update* local)
              (initialize state local)))]

    (ptk/reify ::initialize-viewport
      ptk/UpdateEvent
      (update [_ state]
        (update state :workspace-local
                (fn [local]
                  (setup state local)))))))

(defn update-viewport-position
  [{:keys [x y] :or {x identity y identity}}]
  (us/assert fn? x)
  (us/assert fn? y)
  (ptk/reify ::update-viewport-position
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :vbox]
                 (fn [vbox]
                   (-> vbox
                       (update :x x)
                       (update :y y)))))))

(defn update-viewport-size
  [{:keys [width height] :as size}]
  (ptk/reify ::update-viewport-size
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              (fn [{:keys [vbox vport left-sidebar? zoom] :as local}]
                (let [wprop (/ (:width vport) width)
                      hprop (/ (:height vport) height)
                      left-offset (if left-sidebar? 0 (/ (* -1 15 16) zoom))]
                  (-> local                ;; This matches $width-settings-bar
                      (assoc :vport size)  ;; in frontend/resources/styles/main/partials/sidebar.scss
                      (update :vbox (fn [vbox]
                                      (-> vbox
                                          (update :width #(/ % wprop))
                                          (update :height #(/ % hprop))
                                          (assoc :left-offset left-offset)))))))))))


(defn start-pan [state]
  (-> state
      (assoc-in [:workspace-local :panning] true)))

(defn finish-pan [state]
  (-> state
      (update :workspace-local dissoc :panning)))


;; --- Toggle layout flag

(defn- toggle-layout-flag
  [state flag]
  (us/assert ::layout-flag flag)
  (update state :workspace-layout
          (fn [flags]
            (if (contains? flags flag)
              (disj flags flag)
              (conj flags flag)))))

(defn- check-sidebars
  [state]
  (let [layout (:workspace-layout state)
        left-sidebar? (not (empty? (keep layout [:layers
                                                 :sitemap
                                                 :document-history
                                                 :assets])))
        right-sidebar? (not (empty? (keep layout [:element-options :comments])))]
    (update state :workspace-local
            assoc :left-sidebar? left-sidebar?
                  :right-sidebar? right-sidebar?)))

(defn- check-auto-flags
  [state flags-to-toggle]
  (update state :workspace-layout
          (fn [flags]
            (cond-> flags
              (contains? flags-to-toggle :assets)
              (disj :sitemap :layers :document-history)

              (contains? flags-to-toggle :sitemap)
              (disj :assets :document-history)

              (contains? flags-to-toggle :document-history)
              (disj :assets :sitemap :layers)

              (contains? flags-to-toggle :document-history)
              (disj :assets :sitemap :layers)

              (and (contains? flags-to-toggle :comments)
                   (contains? flags :comments))
              (disj :element-options)

              (and (contains? flags-to-toggle :comments)
                   (not (contains? flags :comments)))
              (conj :element-options)))))

(defn toggle-layout-flags
  [& flags]
  (let [flags (into #{} flags)]
    (ptk/reify ::toggle-layout-flags
      ptk/UpdateEvent
      (update [_ state]
        (-> (reduce toggle-layout-flag state flags)
            (check-auto-flags flags)
            (check-sidebars))))))

;; --- Set element options mode

(defn set-options-mode
  [mode]
  (us/assert ::options-mode mode)
  (ptk/reify ::set-options-mode
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :options-mode] mode))))

;; --- Tooltip

(defn assign-cursor-tooltip
  [content]
  (ptk/reify ::assign-cursor-tooltip
    ptk/UpdateEvent
    (update [_ state]
      (if (string? content)
        (assoc-in state [:workspace-local :tooltip] content)
        (assoc-in state [:workspace-local :tooltip] nil)))))

;; --- Zoom Management

(defn- impl-update-zoom
  [{:keys [vbox] :as local} center zoom]
  (let [vbox (update vbox :x + (:left-offset vbox))
        new-zoom (if (fn? zoom) (zoom (:zoom local)) zoom)
        old-zoom (:zoom local)
        center (if center center (geom/center vbox))
        scale (/ old-zoom new-zoom)
        mtx  (gmt/scale-matrix (gpt/point scale) center)
        vbox' (geom/transform vbox mtx)
        vbox' (update vbox' :x - (:left-offset vbox))]
    (-> local
        (assoc :zoom new-zoom)
        (update :vbox merge (select-keys vbox' [:x :y :width :height])))))

(defn increase-zoom
  [center]
  (ptk/reify ::increase-zoom
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              #(impl-update-zoom % center (fn [z] (min (* z 1.1) 200)))))))

(defn decrease-zoom
  [center]
  (ptk/reify ::decrease-zoom
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              #(impl-update-zoom % center (fn [z] (max (* z 0.9) 0.01)))))))

(def reset-zoom
  (ptk/reify ::reset-zoom
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local
              #(impl-update-zoom % nil 1)))))

(def zoom-to-fit-all
  (ptk/reify ::zoom-to-fit-all
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)
            shapes  (cph/select-toplevel-shapes objects {:include-frames? true})
            srect   (geom/selection-rect shapes)]

        (if (or (mth/nan? (:width srect))
                (mth/nan? (:height srect)))
          state
          (update state :workspace-local
                  (fn [{:keys [vbox vport] :as local}]
                    (let [srect (geom/adjust-to-viewport vport srect {:padding 40})
                          zoom  (/ (:width vport) (:width srect))]
                      (-> local
                          (assoc :zoom zoom)
                          (update :vbox merge srect))))))))))

(def zoom-to-selected-shape
  (ptk/reify ::zoom-to-selected-shape
    ptk/UpdateEvent
    (update [_ state]
      (let [selected (get-in state [:workspace-local :selected])]
        (if (empty? selected)
          state
          (let [page-id (:current-page-id state)
                objects (dwc/lookup-page-objects state page-id)
                srect   (->> selected
                             (map #(get objects %))
                             (geom/selection-rect))]
            (update state :workspace-local
                    (fn [{:keys [vbox vport] :as local}]
                      (let [srect (geom/adjust-to-viewport vport srect {:padding 40})
                            zoom  (/ (:width vport) (:width srect))]
                        (-> local
                            (assoc :zoom zoom)
                            (update :vbox merge srect)))))))))))

;; --- Add shape to Workspace

(declare start-edition-mode)

(defn add-shape
  [attrs]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::add-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)

            id       (uuid/next)
            shape    (geom/setup-proportions attrs)

            unames   (dwc/retrieve-used-names objects)
            name     (dwc/generate-unique-name unames (:name shape))

            frames   (cph/select-frames objects)

            frame-id (if (= :frame (:type shape))
                       uuid/zero
                       (dwc/calculate-frame-overlap frames shape))

            shape    (merge
                      (if (= :frame (:type shape))
                        cp/default-frame-attrs
                        cp/default-shape-attrs)
                      (assoc shape
                             :id id
                             :name name
                             :frame-id frame-id))

            rchange  {:type :add-obj
                      :id id
                      :page-id page-id
                      :frame-id frame-id
                      :obj shape}
            uchange  {:type :del-obj
                      :page-id page-id
                      :id id}]

        (rx/concat
         (rx/of (dwc/commit-changes [rchange] [uchange] {:commit-local? true})
                (dws/select-shapes (d/ordered-set id)))
         (when (= :text (:type attrs))
           (->> (rx/of (start-edition-mode id))
                (rx/observe-on :async))))))))

(defn- viewport-center
  [state]
  (let [{:keys [x y width height]} (get-in state [:workspace-local :vbox])]
    [(+ x (/ width 2)) (+ y (/ height 2))]))

(defn create-and-add-shape
  [type data]
  (ptk/reify ::create-and-add-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [width height]} data
            [vbc-x vbc-y] (viewport-center state)

            x (:x data (- vbc-x (/ width 2)))
            y (:y data (- vbc-y (/ height 2)))

            shape (-> (cp/make-minimal-shape type)
                      (merge data)
                      (merge {:x x :y y})
                      (geom/setup-selrect))]
        (rx/of (add-shape shape))))))

;; --- Update Shape Attrs

(defn update-shape
  [id attrs]
  (us/verify ::us/uuid id)
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::update-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dwc/update-shapes [id] #(merge % attrs))))))

;; --- Update Selected Shapes attrs

(defn update-selected-shapes
  [attrs]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::update-selected-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])]
        (rx/from (map #(update-shape % attrs) selected))))))

(defn update-color-on-selected-shapes
  [{:keys [fill-color stroke-color] :as attrs}]
  (us/verify ::shape-attrs attrs)
  (ptk/reify ::update-color-on-selected-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])
            update-fn
            (fn [shape]
              (cond-> (merge shape attrs)
                (and (= :text (:type shape))
                     (string? (:fill-color attrs)))
                (dwtxt/impl-update-shape-attrs {:fill (:fill-color attrs)})))]
        (rx/of (dwc/update-shapes-recursive selected update-fn))))))


;; --- Shape Movement (using keyboard shorcuts)

(declare initial-selection-align)

(defn- get-displacement-with-grid
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction options]
  (let [grid-x (:grid-x options 10)
        grid-y (:grid-y options 10)
        x-mod (mod (:x shape) grid-x)
        y-mod (mod (:y shape) grid-y)]
    (case direction
      :up (gpt/point 0 (- (if (zero? y-mod) grid-y y-mod)))
      :down (gpt/point 0 (- grid-y y-mod))
      :left (gpt/point (- (if (zero? x-mod) grid-x x-mod)) 0)
      :right (gpt/point (- grid-x x-mod) 0))))

(defn- get-displacement
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction]
  (case direction
    :up (gpt/point 0 (- 1))
    :down (gpt/point 0 1)
    :left (gpt/point (- 1) 0)
    :right (gpt/point 1 0)))

;; --- Delete Selected

(defn- delete-shapes
  [ids]
  (us/assert (s/coll-of ::us/uuid) ids)
  (ptk/reify ::delete-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)

            get-empty-parents
            (fn [parents]
              (->> parents
                   (map (fn [id]
                          (let [obj (get objects id)]
                            (when (and (= :group (:type obj))
                                       (= 1 (count (:shapes obj))))
                              obj))))
                   (take-while (complement nil?))
                   (map :id)))

            groups-to-unmask
            (reduce (fn [group-ids id]
                      ;; When the shape to delete is the mask of a masked group,
                      ;; the mask condition must be removed, and it must be
                      ;; converted to a normal group.
                      (let [obj (get objects id)
                            parent (get objects (:parent-id obj))]
                        (if (and (:masked-group? parent)
                                 (= id (first (:shapes parent))))
                          (conj group-ids (:id parent))
                          group-ids)))
                    #{}
                    ids)

            rchanges
            (d/concat
              (reduce (fn [res id]
                        (let [children (cph/get-children id objects)
                              parents  (cph/get-parents id objects)
                              del-change #(array-map
                                            :type :del-obj
                                            :page-id page-id
                                            :id %)]
                              (d/concat res
                                        (map del-change (reverse children))
                                        [(del-change id)]
                                        (map del-change (get-empty-parents parents))
                                        [{:type :reg-objects
                                          :page-id page-id
                                          :shapes (vec parents)}])))
                      []
                      ids)
              (map #(array-map
                      :type :mod-obj
                      :page-id page-id
                      :id %
                      :operations [{:type :set
                                    :attr :masked-group?
                                    :val false}])
                   groups-to-unmask))

            uchanges
            (d/concat
              (reduce (fn [res id]
                        (let [children    (cph/get-children id objects)
                              parents     (cph/get-parents id objects)
                              add-change  (fn [id]
                                            (let [item (get objects id)]
                                              {:type :add-obj
                                               :id (:id item)
                                               :page-id page-id
                                               :index (cph/position-on-parent id objects)
                                               :frame-id (:frame-id item)
                                               :parent-id (:parent-id item)
                                               :obj item}))]
                          (d/concat res
                                    (map add-change (reverse (get-empty-parents parents)))
                                    [(add-change id)]
                                    (map add-change children)
                                    [{:type :reg-objects
                                      :page-id page-id
                                      :shapes (vec parents)}])))
                      []
                      ids)
              (map #(array-map
                      :type :mod-obj
                      :page-id page-id
                      :id %
                      :operations [{:type :set
                                    :attr :masked-group?
                                    :val true}])
                   groups-to-unmask))]

        ;; (println "================ rchanges")
        ;; (cljs.pprint/pprint rchanges)
        ;; (println "================ uchanges")
        ;; (cljs.pprint/pprint uchanges)
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(def delete-selected
  "Deselect all and remove all selected shapes."
  (ptk/reify ::delete-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])]
        (rx/of (delete-shapes selected)
               (dws/deselect-all))))))

;; --- Shape Vertical Ordering

(s/def ::loc  #{:up :down :bottom :top})

(defn vertical-order-selected
  [loc]
  (us/verify ::loc loc)
  (ptk/reify ::vertical-order-selected-shpes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected (get-in state [:workspace-local :selected])
            rchanges (mapv (fn [id]
                             (let [obj (get objects id)
                                   parent (get objects (:parent-id obj))
                                   shapes (:shapes parent)
                                   cindex (d/index-of shapes id)
                                   nindex (case loc
                                            :top (count shapes)
                                            :down (max 0 (- cindex 1))
                                            :up (min (count shapes) (+ (inc cindex) 1))
                                            :bottom 0)]
                               {:type :mov-objects
                                :parent-id (:parent-id obj)
                                :frame-id (:frame-id obj)
                                :page-id page-id
                                :index nindex
                                :shapes [id]}))
                           selected)

             uchanges (mapv (fn [id]
                             (let [obj (get objects id)]
                               {:type :mov-objects
                                :parent-id (:parent-id obj)
                                :frame-id (:frame-id obj)
                                :page-id page-id
                                :shapes [id]
                                :index (cph/position-on-parent id objects)}))
                            selected)]
        ;; TODO: maybe missing the :reg-objects event?
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))


;; --- Change Shape Order (D&D Ordering)

(defn relocate-shapes
  [ids parent-id to-index]
  (us/verify (s/coll-of ::us/uuid) ids)
  (us/verify ::us/uuid parent-id)
  (us/verify number? to-index)

  (ptk/reify ::relocate-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            parents  (loop [res #{parent-id}
                            ids (seq ids)]
                       (if (nil? ids)
                         (vec res)
                         (recur
                          (conj res (cph/get-parent (first ids) objects))
                          (next ids))))

            groups-to-unmask
            (reduce (fn [group-ids id]
                      ;; When a masked group loses its mask shape, because it's
                      ;; moved outside the group, the mask condition must be
                      ;; removed, and it must be converted to a normal group.
                      (let [obj (get objects id)
                            parent (get objects (:parent-id obj))]
                        (if (and (:masked-group? parent)
                                 (= id (first (:shapes parent)))
                                 (not= (:id parent) parent-id))
                          (conj group-ids (:id parent))
                          group-ids)))
                    #{}
                    ids)

            rchanges (d/concat
                       [{:type :mov-objects
                         :parent-id parent-id
                         :page-id page-id
                         :index to-index
                         :shapes (vec (reverse ids))}
                        {:type :reg-objects
                         :page-id page-id
                         :shapes parents}]
                       (map (fn [group-id]
                              {:type :mod-obj
                               :page-id page-id
                               :id group-id
                               :operations [{:type :set
                                             :attr :masked-group?
                                             :val false}]})
                            groups-to-unmask))

            uchanges (d/concat
                       (reduce (fn [res id]
                                 (let [obj (get objects id)]
                                   (conj res
                                         {:type :mov-objects
                                          :parent-id (:parent-id obj)
                                          :page-id page-id
                                          :index (cph/position-on-parent id objects)
                                          :shapes [id]})))
                               [] (reverse ids))
                       [{:type :reg-objects
                            :page-id page-id
                            :shapes parents}]
                       (map (fn [group-id]
                              {:type :mod-obj
                               :page-id page-id
                               :id group-id
                               :operations [{:type :set
                                             :attr :masked-group?
                                             :val true}]})
                            groups-to-unmask))]

        ;; (println "================ rchanges")
        ;; (cljs.pprint/pprint rchanges)
        ;; (println "================ uchanges")
        ;; (cljs.pprint/pprint uchanges)
        (rx/of (dwc/commit-changes rchanges uchanges
                                   {:commit-local? true}))))))

(defn relocate-selected-shapes
  [parent-id to-index]
  (ptk/reify ::relocate-selected-shapes
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected (get-in state [:workspace-local :selected])]
        (rx/of (relocate-shapes selected parent-id to-index))))))


;; --- Change Page Order (D&D Ordering)

(defn relocate-page
  [id index]
  (ptk/reify ::relocate-pages
    ptk/WatchEvent
    (watch [_ state stream]
      (let [cidx (-> (get-in state [:workspace-data :pages])
                     (d/index-of id))
            rchg {:type :mov-page
                  :id id
                  :index index}
            uchg {:type :mov-page
                  :id id
                  :index cidx}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

;; --- Shape / Selection Alignment and Distribution

(declare align-object-to-frame)
(declare align-objects-list)

(defn align-objects
  [axis]
  (us/verify ::geom/align-axis axis)
  (ptk/reify :align-objects
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected (get-in state [:workspace-local :selected])
            moved    (if (= 1 (count selected))
                       (align-object-to-frame objects (first selected) axis)
                       (align-objects-list objects selected axis))]
        (loop [moved    (seq moved)
               rchanges []
               uchanges []]
          (if (nil? moved)
            (do
              ;; (println "================ rchanges")
              ;; (cljs.pprint/pprint rchanges)
              ;; (println "================ uchanges")
              ;; (cljs.pprint/pprint uchanges)
              (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})))
            (let [curr (first moved)
                  prev (get objects (:id curr))
                  ops1 (dwc/generate-operations prev curr)
                  ops2 (dwc/generate-operations curr prev true)]
              (recur (next moved)
                     (conj rchanges {:type :mod-obj
                                     :page-id page-id
                                     :operations ops1
                                     :id (:id curr)})
                     (conj uchanges {:type :mod-obj
                                     :page-id page-id
                                     :operations ops2
                                     :id (:id curr)})))))))))

(defn align-object-to-frame
  [objects object-id axis]
  (let [object (get objects object-id)
        frame (get objects (:frame-id object))]
    (geom/align-to-rect object frame axis objects)))

(defn align-objects-list
  [objects selected axis]
  (let [selected-objs (map #(get objects %) selected)
        rect (geom/selection-rect selected-objs)]
    (mapcat #(geom/align-to-rect % rect axis objects) selected-objs)))

(defn distribute-objects
  [axis]
  (us/verify ::geom/dist-axis axis)
  (ptk/reify :align-objects
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected (get-in state [:workspace-local :selected])
            moved    (-> (map #(get objects %) selected)
                         (geom/distribute-space axis objects))]
        (loop [moved    (seq moved)
               rchanges []
               uchanges []]
          (if (nil? moved)
            (do
              ;; (println "================ rchanges")
              ;; (cljs.pprint/pprint rchanges)
              ;; (println "================ uchanges")
              ;; (cljs.pprint/pprint uchanges)
              (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})))
            (let [curr (first moved)
                  prev (get objects (:id curr))
                  ops1 (dwc/generate-operations prev curr)
                  ops2 (dwc/generate-operations curr prev true)]
              (recur (next moved)
                     (conj rchanges {:type :mod-obj
                                     :page-id page-id
                                     :operations ops1
                                     :id (:id curr)})
                     (conj uchanges {:type :mod-obj
                                     :page-id page-id
                                     :operations ops2
                                     :id (:id curr)})))))))))

;; --- Start shape "edition mode"

(declare clear-edition-mode)

(defn start-edition-mode
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::start-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :edition] id))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> stream
           (rx/filter dwc/interrupt?)
           (rx/take 1)
           (rx/map (constantly clear-edition-mode))))))

(def clear-edition-mode
  (ptk/reify ::clear-edition-mode
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :edition))))

;; --- Select for Drawing

(def clear-drawing
  (ptk/reify ::clear-drawing
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-drawing dissoc :tool :object))))

(defn select-for-drawing
  ([tool] (select-for-drawing tool nil))
  ([tool data]
   (ptk/reify ::select-for-drawing
     ptk/UpdateEvent
     (update [_ state]
       (update state :workspace-drawing assoc :tool tool :object data))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [cancel-event? (fn [event]
                             (dwc/interrupt? event))
             stoper (rx/filter (ptk/type? ::clear-drawing) stream)]
         (->> (rx/filter cancel-event? stream)
              (rx/take 1)
              (rx/map (constantly clear-drawing))
              (rx/take-until stoper)))))))

;; --- Update Dimensions

;; Event mainly used for handling user modification of the size of the
;; object from workspace sidebar options inputs.

(defn update-dimensions
  [ids attr value]
  (us/verify (s/coll-of ::us/uuid) ids)
  (us/verify #{:width :height} attr)
  (us/verify ::us/number value)
  (ptk/reify ::update-dimensions
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dwc/update-shapes ids #(geom/resize-rect % attr value))))))


;; --- Shape Proportions

(defn set-shape-proportion-lock
  [id lock]
  (ptk/reify ::set-shape-proportion-lock
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dwc/update-shapes [id] (fn [shape]
                                       (if-not lock
                                         (assoc shape :proportion-lock false)
                                         (-> (assoc shape :proportion-lock true)
                                             (geom/assign-proportions)))))))))
;; --- Update Shape Position

(s/def ::x number?)
(s/def ::y number?)
(s/def ::position
  (s/keys :opt-un [::x ::y]))

(defn update-position
  [id position]
  (us/verify ::us/uuid id)
  (us/verify ::position position)
  (ptk/reify ::update-position
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)
            shape   (get objects id)
            cpos    (gpt/point (:x shape) (:y shape))
            pos     (gpt/point (or (:x position) (:x shape))
                               (or (:y position) (:y shape)))
            displ   (gmt/translate-matrix (gpt/subtract pos cpos))]
        (rx/of (dwt/set-modifiers [id] {:displacement displ})
               (dwt/apply-modifiers [id]))))))

;; --- Path Modifications

(defn update-path
  "Update a concrete point in the path shape."
  [id index delta]
  (us/verify ::us/uuid id)
  (us/verify ::us/integer index)
  (us/verify gpt/point? delta)
  (js/alert "TODO: broken")
  #_(ptk/reify ::update-path
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)]
        (-> state
            (update-in [:workspace-data page-id :objects id :segments index] gpt/add delta)
            (update-in [:workspace-data page-id :objects id] geom/update-path-selrect))))))

;; --- Shape attrs (Layers Sidebar)

(defn toggle-collapse
  [id]
  (ptk/reify ::toggle-collapse
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :expanded id] not))))

(def collapse-all
  (ptk/reify ::collapse-all
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :expanded))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Navigation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn navigate-to-project
  [project-id]
  (ptk/reify ::navigate-to-project
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-ids (get-in state [:projects project-id :pages])
            params {:project project-id :page (first page-ids)}]
        (rx/of (rt/nav :workspace/page params))))))

(defn go-to-page
  [page-id]
  (us/verify ::us/uuid page-id)
  (ptk/reify ::go-to-page
    ptk/WatchEvent
    (watch [_ state stream]
      (let [project-id (get-in state [:workspace-project :id])
            file-id    (get-in state [:workspace-file :id])
            pparams    {:file-id file-id :project-id project-id}
            qparams    {:page-id page-id}]
        (rx/of (rt/nav :workspace pparams qparams))))))


(def go-to-file
  (ptk/reify ::go-to-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [id project-id data] :as file} (:workspace-file state)
            page-id (get-in data [:pages 0])
            pparams {:project-id project-id :file-id id}
            qparams {:page-id page-id}]
        (rx/of (rt/nav :workspace pparams qparams))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Context Menu
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::point gpt/point?)

(defn show-context-menu
  [{:keys [position] :as params}]
  (us/verify ::point position)
  (ptk/reify ::show-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :context-menu] {:position position}))))

(defn show-shape-context-menu
  [{:keys [position shape] :as params}]
  (us/verify ::point position)
  (us/verify ::cp/minimal-shape shape)
  (ptk/reify ::show-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id    (:current-page-id state)
            objects    (dwc/lookup-page-objects state page-id)

            mdata {:position position
                   :shape shape
                   :selected (get-in state [:workspace-local :selected])}]
        (-> state
            (assoc-in [:workspace-local :context-menu] mdata))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dws/select-shape (:id shape))))))

(def hide-context-menu
  (ptk/reify ::hide-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :context-menu] nil))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clipboard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def copy-selected
  (letfn [(prepare-selected [objects selected]
            (let [data (reduce #(prepare %1 objects %2) {} selected)]
              {:type :copied-shapes
               :selected selected
               :objects data}))

          (prepare [result objects id]
            (let [obj (get objects id)]
              (as-> result $$
                (assoc $$ id obj)
                (reduce #(prepare %1 objects %2) $$ (:shapes obj)))))

          (on-copy-error [error]
            (js/console.error "Clipboard blocked:" error)
            (rx/empty))]

    (ptk/reify ::copy-selected
      ptk/WatchEvent
      (watch [_ state stream]
        (let [objects  (dwc/lookup-page-objects state)
              selected (get-in state [:workspace-local :selected])
              cdata    (prepare-selected objects selected)]
          (->> (t/encode cdata)
               (wapi/write-to-clipboard)
               (rx/from)
               (rx/catch on-copy-error)
               (rx/ignore)))))))

(defn- paste-impl
  [{:keys [selected objects] :as data}]
  (ptk/reify ::paste-impl
    ptk/WatchEvent
    (watch [_ state stream]
      (let [selected-objs (map #(get objects %) selected)
            wrapper   (geom/selection-rect selected-objs)
            orig-pos  (gpt/point (:x1 wrapper) (:y1 wrapper))
            mouse-pos @ms/mouse-position
            delta     (gpt/subtract mouse-pos orig-pos)

            page-id   (:current-page-id state)
            unames    (-> (dwc/lookup-page-objects state page-id)
                          (dwc/retrieve-used-names))

            rchanges  (dws/prepare-duplicate-changes objects page-id unames selected delta)
            uchanges  (mapv #(array-map :type :del-obj :page-id page-id :id (:id %))
                            (reverse rchanges))

            selected (->> rchanges
                          (filter #(selected (:old-id %)))
                          (map #(get-in % [:obj :id]))
                          (into (d/ordered-set)))]
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
               (dws/select-shapes selected))))))

(defn- image-uploaded
  [image]
  (let [{:keys [x y]} @ms/mouse-position
        {:keys [width height]} image
        shape {:name (:name image)
               :width width
               :height height
               :x (- x (/ width 2))
               :y (- y (/ height 2))
               :metadata {:width width
                          :height height
                          :id (:id image)
                          :path (:path image)}}]
    (st/emit! (create-and-add-shape :image shape))))

(defn- paste-image-impl
  [image]
  (ptk/reify ::paste-bin-impl
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file-id (get-in state [:workspace-file :id])
            params  {:file-id file-id
                     :local? true
                     :js-files [image]}]
        (rx/of (dwp/upload-media-objects
                (with-meta params
                  {:on-success image-uploaded})))))))

(declare paste-text)

(def paste
  (ptk/reify ::paste
    ptk/WatchEvent
    (watch [_ state stream]
      (try
        (let [clipboard-str (wapi/read-from-clipboard)

              paste-transit-str
              (->> clipboard-str
                   (rx/filter t/transit?)
                   (rx/map t/decode)
                   (rx/filter #(= :copied-shapes (:type %)))
                   (rx/map #(select-keys % [:selected :objects]))
                   (rx/map paste-impl))

              paste-plain-text-str
              (->> clipboard-str
                   (rx/filter (comp not empty?))
                   (rx/map paste-text))

              paste-image-str
              (->> (wapi/read-image-from-clipboard)
                   (rx/map paste-image-impl))]

          (->> (rx/concat paste-transit-str
                          paste-plain-text-str
                          paste-image-str)
               (rx/first)
               (rx/catch
                   (fn [err]
                     (js/console.error "Clipboard error:" err)
                     (rx/empty)))))
        (catch :default e
          (.error js/console "ERROR" e))))))

(defn as-content [text]
  (let [paragraphs (->> (str/lines text)
                        (map str/trim)
                        (mapv #(hash-map :type "paragraph"
                                         :children [{:text %}])))]
    {:type "root"
     :children [{:type "paragraph-set" :children paragraphs}]}))

(defn paste-text [text]
  (s/assert string? text)
  (ptk/reify ::paste-text
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (uuid/next)
            {:keys [x y]} @ms/mouse-position
            width (min (* 7 (count text)) 700)
            height 16
            shape (geom/setup-selrect
                   {:id id
                    :type :text
                    :name "Text"
                    :x x
                    :y y
                    :width width
                    :height height
                    :grow-type (if (> (count text) 100) :auto-height :auto-width)
                    :content (as-content text)})]
        (rx/of dwc/start-undo-transaction
               (dws/deselect-all)
               (add-shape shape)
               (dwc/rehash-shape-frame-relationship [id])
               dwc/commit-undo-transaction)))))

(defn update-shape-flags
  [id {:keys [blocked hidden] :as flags}]
  (s/assert ::us/uuid id)
  (s/assert ::shape-attrs flags)
  (ptk/reify ::update-shape-flags
    ptk/WatchEvent
    (watch [_ state stream]
      (letfn [(update-fn [obj]
                (cond-> obj
                  (boolean? blocked) (assoc :blocked blocked)
                  (boolean? hidden) (assoc :hidden hidden)))]
        (rx/of (dwc/update-shapes-recursive [id] update-fn))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GROUPS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def group-selected
  (ptk/reify ::group-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected (get-in state [:workspace-local :selected])
            shapes   (dws/shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [[group rchanges uchanges]
                (dws/prepare-create-group page-id shapes "Group-" false)]
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (dws/select-shapes (d/ordered-set (:id group))))))))))

(def ungroup-selected
  (ptk/reify ::ungroup-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected (get-in state [:workspace-local :selected])
            group-id (first selected)
            group    (get objects group-id)]
        (when (and (= 1 (count selected))
                   (= (:type group) :group))
          (let [[rchanges uchanges]
                (dws/prepare-remove-group page-id group objects)]
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))))

(def mask-group
  (ptk/reify ::mask-group
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected (get-in state [:workspace-local :selected])
            shapes   (dws/shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [;; If the selected shape is a group, we can use it. If not,
                ;; create a new group and set it as masked.
                [group rchanges uchanges]
                (if (and (= (count shapes) 1)
                         (= (:type (first shapes)) :group))
                  [(first shapes) [] []]
                  (dws/prepare-create-group page-id shapes "Group-" true))

                rchanges (d/concat rchanges
                          [{:type :mod-obj
                            :page-id page-id
                            :id (:id group)
                            :operations [{:type :set
                                          :attr :masked-group?
                                          :val true}]}
                           {:type :reg-objects
                            :page-id page-id
                            :shapes [(:id group)]}])

                uchanges (conj rchanges
                          {:type :mod-obj
                           :page-id page-id
                           :id (:id group)
                           :operations [{:type :set
                                         :attr :masked-group?
                                         :val nil}]})

                ;; If the mask has the default color, change it automatically
                ;; to white, to have an opaque mask by default (user may change
                ;; it later to have different degrees of transparency).
                mask (first shapes)
                rchanges (if (not= (:fill-color mask) cp/default-color)
                           rchanges
                           (conj rchanges
                                 {:type :mod-obj
                                  :page-id page-id
                                  :id (:id mask)
                                  :operations [{:type :set
                                                :attr :fill-color
                                                :val "#ffffff"}]}))

                uchanges (if (not= (:fill-color mask) cp/default-color)
                           uchanges
                           (conj uchanges
                                 {:type :mod-obj
                                  :page-id page-id
                                  :id (:id mask)
                                  :operations [{:type :set
                                                :attr :fill-color
                                                :val (:fill-color mask)}]}))]

            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (dws/select-shapes (d/ordered-set (:id group))))))))))

(def unmask-group
  (ptk/reify ::unmask-group
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected (get-in state [:workspace-local :selected])]
        (when (= (count selected) 1)
          (let [group (get objects (first selected))

                rchanges [{:type :mod-obj
                           :page-id page-id
                           :id (:id group)
                           :operations [{:type :set
                                         :attr :masked-group?
                                         :val nil}]}]

                uchanges [{:type :mod-obj
                           :page-id page-id
                           :id (:id group)
                           :operations [{:type :set
                                         :attr :masked-group?
                                         :val (:masked-group? group)}]}]]

            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (dws/select-shapes (d/ordered-set (:id group))))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interactions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare move-create-interaction)
(declare finish-create-interaction)

(defn start-create-interaction
  []
  (ptk/reify ::start-create-interaction
    ptk/WatchEvent
    (watch [_ state stream]
      (let [initial-pos @ms/mouse-position
            selected (get-in state [:workspace-local :selected])
            stopper (rx/filter ms/mouse-up? stream)]
        (when (= 1 (count selected))
          (rx/concat
            (->> ms/mouse-position
                 (rx/take-until stopper)
                 (rx/map #(move-create-interaction initial-pos %)))
            (rx/of (finish-create-interaction initial-pos))))))))

(defn move-create-interaction
  [initial-pos position]
  (ptk/reify ::move-create-interaction
    ptk/UpdateEvent
    (update [_ state]
      (let [page-id (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected-shape-id (-> state (get-in [:workspace-local :selected]) first)
            selected-shape (get objects selected-shape-id)
            selected-shape-frame-id (:frame-id selected-shape)
            start-frame (get objects selected-shape-frame-id)
            end-frame   (dwc/get-frame-at-point objects position)]
        (cond-> state
          (not= position initial-pos) (assoc-in [:workspace-local :draw-interaction-to] position)
          (not= start-frame end-frame) (assoc-in [:workspace-local :draw-interaction-to-frame] end-frame))))))

(defn finish-create-interaction
  [initial-pos]
  (ptk/reify ::finish-create-interaction
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :draw-interaction-to] nil)
          (assoc-in [:workspace-local :draw-interaction-to-frame] nil)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [position @ms/mouse-position
            page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            frame    (dwc/get-frame-at-point objects position)

            shape-id (first (get-in state [:workspace-local :selected]))
            shape    (get objects shape-id)]

        (when-not (= position initial-pos)
          (if (and frame shape-id
                   (not= (:id frame) (:id shape))
                   (not= (:id frame) (:frame-id shape)))
            (rx/of (update-shape shape-id
                                 {:interactions [{:event-type :click
                                                  :action-type :navigate
                                                  :destination (:id frame)}]}))
            (rx/of (update-shape shape-id
                                 {:interactions []}))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CANVAS OPTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn change-canvas-color
  [color]
  ;; TODO: Create a color spec
  #_(s/assert string? color)
  (ptk/reify ::change-canvas-color
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (get state :current-page-id)
            options (dwc/lookup-page-options state page-id)
            previus-color  (:background options)]
        (rx/of (dwc/commit-changes
                [{:type :set-option
                  :page-id page-id
                  :option :background
                  :value (:color color)}]
                [{:type :set-option
                  :page-id page-id
                  :option :background
                  :value previus-color}]
                {:commit-local? true}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exports
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Transform

(def start-rotate dwt/start-rotate)
(def start-resize dwt/start-resize)
(def start-move-selected dwt/start-move-selected)
(def move-selected dwt/move-selected)

(def set-rotation dwt/set-rotation)
(def set-modifiers dwt/set-modifiers)
(def apply-modifiers dwt/apply-modifiers)

;; Persistence

(def set-file-shared dwp/set-file-shared)
(def fetch-shared-files dwp/fetch-shared-files)
(def link-file-to-library dwp/link-file-to-library)
(def unlink-file-from-library dwp/unlink-file-from-library)
(def upload-media-objects dwp/upload-media-objects)
(def delete-media-object dwp/delete-media-object)

;; Selection

(def select-shape dws/select-shape)
(def deselect-all dws/deselect-all)
(def select-shapes dws/select-shapes)
(def duplicate-selected dws/duplicate-selected)
(def handle-selection dws/handle-selection)
(def select-inside-group dws/select-inside-group)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Shortcuts impl https://github.com/ccampbell/mousetrap

(def shortcuts
  {"ctrl+i" #(st/emit! (toggle-layout-flags :assets))
   "ctrl+l" #(st/emit! (toggle-layout-flags :sitemap :layers))
   "ctrl+shift+r" #(st/emit! (toggle-layout-flags :rules))
   "ctrl+a" #(st/emit! (toggle-layout-flags :dynamic-alignment))
   "ctrl+p" #(st/emit! (toggle-layout-flags :colorpalette))
   "ctrl+'" #(st/emit! (toggle-layout-flags :display-grid))
   "ctrl+shift+'" #(st/emit! (toggle-layout-flags :snap-grid))
   "+" #(st/emit! (increase-zoom nil))
   "-" #(st/emit! (decrease-zoom nil))
   "ctrl+g" #(st/emit! group-selected)
   "shift+g" #(st/emit! ungroup-selected)
   "ctrl+m" #(st/emit! mask-group)
   "shift+m" #(st/emit! unmask-group)
   "ctrl+k" #(st/emit! dwl/add-component)
   "shift+0" #(st/emit! reset-zoom)
   "shift+1" #(st/emit! zoom-to-fit-all)
   "shift+2" #(st/emit! zoom-to-selected-shape)
   "ctrl+d" #(st/emit! duplicate-selected)
   "ctrl+z" #(st/emit! dwc/undo)
   "ctrl+shift+z" #(st/emit! dwc/redo)
   "ctrl+y" #(st/emit! dwc/redo)
   "ctrl+q" #(st/emit! dwc/reinitialize-undo)
   "a" #(st/emit! (select-for-drawing :frame))
   "b" #(st/emit! (select-for-drawing :rect))
   "e" #(st/emit! (select-for-drawing :circle))
   "t" #(st/emit! dwtxt/start-edit-if-selected
                  (select-for-drawing :text))
   "ctrl+c" #(st/emit! copy-selected)
   "ctrl+v" #(st/emit! paste)
   "escape" #(st/emit! :interrupt (deselect-all true))
   "del" #(st/emit! delete-selected)
   "backspace" #(st/emit! delete-selected)
   "ctrl+up" #(st/emit! (vertical-order-selected :up))
   "ctrl+down" #(st/emit! (vertical-order-selected :down))
   "ctrl+shift+up" #(st/emit! (vertical-order-selected :top))
   "ctrl+shift+down" #(st/emit! (vertical-order-selected :bottom))
   "shift+up" #(st/emit! (dwt/move-selected :up true))
   "shift+down" #(st/emit! (dwt/move-selected :down true))
   "shift+right" #(st/emit! (dwt/move-selected :right true))
   "shift+left" #(st/emit! (dwt/move-selected :left true))
   "up" #(st/emit! (dwt/move-selected :up false))
   "down" #(st/emit! (dwt/move-selected :down false))
   "right" #(st/emit! (dwt/move-selected :right false))
   "left" #(st/emit! (dwt/move-selected :left false))

   "i" #(st/emit! (mdc/picker-for-selected-shape ))})


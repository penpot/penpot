;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.libraries
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.common.pages-helpers :as cph]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.common.pages :as cp]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.util.color :as color]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

(defn add-color
  [color]
  (us/assert ::us/string color)
  (ptk/reify ::add-color
    ptk/WatchEvent
    (watch [_ state s]
      (let [id   (uuid/next)
            rchg {:type :add-color
                  :color {:id id
                          :name color
                          :value color}}
            uchg {:type :del-color
                  :id id}]
        (rx/of #(assoc-in % [:workspace-local :color-for-rename] id)
               (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(defn add-recent-color
  [color]
  (us/assert ::us/string color)
  (ptk/reify ::add-recent-color
    ptk/WatchEvent
    (watch [_ state s]
      (let [rchg {:type :add-recent-color
                  :color color}]
        (rx/of (dwc/commit-changes [rchg] [] {:commit-local? true}))))))

(def clear-color-for-rename
  (ptk/reify ::clear-color-for-rename
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :color-for-rename] nil))))

(defn update-color
  [{:keys [id] :as color}]
  (us/assert ::cp/color color)
  (ptk/reify ::update-color
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :colors id])
            rchg {:type :mod-color
                  :color color}
            uchg {:type :mod-color
                  :color prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(defn delete-color
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-color
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :colors id])
            rchg {:type :del-color
                  :id id}
            uchg {:type :add-color
                  :color prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(defn add-media
  [{:keys [id] :as media}]
  (us/assert ::cp/media-object media)
  (ptk/reify ::add-media
    ptk/WatchEvent
    (watch [_ state stream]
      (let [rchg {:type :add-media
                  :object media}
            uchg {:type :del-media
                  :id id}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))


(defn delete-media
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-media
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :media id])
            rchg {:type :del-media
                  :id id}
            uchg {:type :add-media
                  :object prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(declare make-component-shape)

(def add-component
  (ptk/reify ::add-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected (get-in state [:workspace-local :selected])
            shapes   (dws/shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [;; If the selected shape is a group, we can use it. If not,
                ;; we need to create a group before creating the component.
                [group rchanges uchanges]
                (if (and (= (count shapes) 1)
                         (= (:type (first shapes)) :group))
                  [(first shapes) [] []]
                  (dws/prepare-create-group page-id shapes "Component-" true))

                [new-shape new-shapes updated-shapes]
                (make-component-shape group nil objects)

                rchanges (conj rchanges
                               {:type :add-component
                                :id (:id new-shape)
                                :name (:name new-shape)
                                :new-shapes new-shapes})

                rchanges (into rchanges
                               (map (fn [updated-shape]
                                      {:type :mod-obj
                                       :page-id page-id
                                       :id (:id updated-shape)
                                       :operations [{:type :set
                                                     :attr :component-id
                                                     :val (:component-id updated-shape)}
                                                    {:type :set
                                                     :attr :component-file
                                                     :val nil}
                                                    {:type :set
                                                     :attr :shape-ref
                                                     :val (:shape-ref updated-shape)}]})
                                    updated-shapes))

                uchanges (conj uchanges
                               {:type :del-component
                                :id (:id new-shape)})

                uchanges (into uchanges
                               (map (fn [updated-shape]
                                      {:type :mod-obj
                                       :page-id page-id
                                       :id (:id updated-shape)
                                       :operations [{:type :set
                                                     :attr :component-id
                                                     :val nil}
                                                    {:type :set
                                                     :attr :component-file
                                                     :val nil}
                                                    {:type :set
                                                     :attr :shape-ref
                                                     :val nil}]})
                                    updated-shapes))]

            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (dws/select-shapes (d/ordered-set (:id group))))))))))

(defn- make-component-shape
  "Clone the shape and all children. Generate new ids and detach
  from parent and frame. Update the original shapes to have links
  to the new ones."
  [shape parent-id objects]
  (let [xf-new-shape (fn [new-shape original-shape]
                       (assoc new-shape :frame-id nil))

        xf-original-shape (fn [original-shape new-shape]
                            (cond-> original-shape
                              true
                              (assoc :shape-ref (:id new-shape))

                              (nil? (:parent-id new-shape))
                              (assoc :component-id (:id new-shape))))]

    (cph/clone-object shape parent-id objects xf-new-shape xf-original-shape)))

(defn delete-component
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [component (get-in state [:workspace-data :components id])

            rchanges [{:type :del-component
                       :id id}]

            uchanges [{:type :add-component
                       :id id
                       :name (:name component)
                       :new-shapes (:objects component)}]]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn instantiate-component
  [file-id component-id]
  (us/assert (s/nilable ::us/uuid) file-id)
  (us/assert ::us/uuid component-id)
  (ptk/reify ::instantiate-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [component (if (nil? file-id)
                        (get-in state [:workspace-data :components component-id])
                        (get-in state [:workspace-libraries file-id :data :components component-id]))
            component-shape (get-in component [:objects (:id component)])

            orig-pos  (gpt/point (:x component-shape) (:y component-shape))
            mouse-pos @ms/mouse-position
            delta     (gpt/subtract mouse-pos orig-pos)

            _ (js/console.log "orig-pos" (clj->js orig-pos))
            _ (js/console.log "mouse-pos" (clj->js mouse-pos))
            _ (js/console.log "delta" (clj->js delta))

            page-id   (:current-page-id state)
            objects   (dwc/lookup-page-objects state page-id)
            unames    (atom (dwc/retrieve-used-names objects))

            all-frames (cph/select-frames objects)

            xf-new-shape
            (fn [new-shape original-shape]
              (let [new-name 
                    (dwc/generate-unique-name @unames (:name new-shape))]

                (swap! unames conj new-name)

                (cond-> new-shape
                  true
                  (as-> $
                    (assoc $ :name new-name)
                    (geom/move $ delta)
                    (assoc $ :frame-id
                           (dwc/calculate-frame-overlap all-frames $))
                    (assoc $ :parent-id
                           (or (:parent-id $) (:frame-id $)))
                    (assoc $ :shape-ref (:id original-shape)))

                  (nil? (:parent-id original-shape))
                  (assoc :component-id (:id original-shape))

                  (and (nil? (:parent-id original-shape)) (some? file-id))
                  (assoc :component-file file-id))))

            [new-shape new-shapes _]
            (cph/clone-object component-shape
                              nil
                              (get component :objects)
                              xf-new-shape)

            rchanges (map (fn [obj]
                            {:type :add-obj
                             :id (:id obj)
                             :page-id page-id
                             :frame-id (:frame-id obj)
                             :parent-id (:parent-id obj)
                             :obj obj})
                          new-shapes)

            uchanges (map (fn [obj]
                            {:type :del-obj
                             :id (:id obj)
                             :page-id page-id})
                          new-shapes)]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
               (dws/select-shapes (d/ordered-set (:id new-shape))))))))

(defn detach-component
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::detach-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)
            root-id (cph/get-root-component id objects)

            shapes (cph/get-object-with-children root-id objects)

            rchanges (map (fn [obj]
                            {:type :mod-obj
                             :page-id page-id
                             :id (:id obj)
                             :operations [{:type :set
                                           :attr :component-id
                                           :val nil}
                                          {:type :set
                                           :attr :component-file
                                           :val nil}
                                          {:type :set
                                           :attr :shape-ref
                                           :val nil}]})
                          shapes)

            uchanges (map (fn [obj]
                            {:type :mod-obj
                             :page-id page-id
                             :id (:id obj)
                             :operations [{:type :set
                                           :attr :component-id
                                           :val (:component-id obj)}
                                          {:type :set
                                           :attr :component-file
                                           :val (:component-file obj)}
                                          {:type :set
                                           :attr :shape-ref
                                           :val (:shape-ref obj)}]})
                          shapes)]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn nav-to-component-file
  [file-id]
  (us/assert ::us/uuid file-id)
  (ptk/reify ::nav-to-component-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file (get-in state [:workspace-libraries file-id])
            pparams {:project-id (:project-id file)
                     :file-id (:id file)}
            qparams {:page-id (first (get-in file [:data :pages]))}]
        (st/emit! (rt/nav-new-window :workspace pparams qparams))))))

(declare generate-sync-file)
(declare generate-sync-page)
(declare generate-sync-shape-and-children)
(declare generate-sync-shape)
(declare remove-component-and-ref)
(declare remove-ref)
(declare update-attrs)
(declare sync-attrs)
(declare calc-new-pos)

(defn reset-component
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::reset-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id        (:current-page-id state)
            page           (get-in state [:workspace-data :pages-index page-id])
            objects        (dwc/lookup-page-objects state page-id)
            root-id        (cph/get-root-component id objects)
            root-shape     (get objects id)
            file-id        (get root-shape :component-file)

            components
            (if (nil? file-id)
              (get-in state [:workspace-data :components])
              (get-in state [:workspace-libraries file-id :data :components]))

            [rchanges uchanges]
            (generate-sync-shape-and-children root-shape page components)]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn update-component
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::update-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id        (:current-page-id state)
            objects        (dwc/lookup-page-objects state page-id)
            root-id        (cph/get-root-component id objects)
            root-shape     (get objects id)

            component-id   (get root-shape :component-id)
            component-objs (dwc/lookup-component-objects state component-id)
            component-obj  (get component-objs component-id)

            ;; Clone again the original shape and its children, maintaing
            ;; the ids of the cloned shapes. If the original shape has some
            ;; new child shapes, the cloned ones will have new generated ids.
            xf-new-shape   (fn [new-shape original-shape]
                             (cond-> new-shape
                               true
                               (assoc :frame-id nil)

                               (some? (:shape-ref original-shape))
                               (assoc :id (:shape-ref original-shape))))

            [new-shape new-shapes _]
            (cph/clone-object root-shape nil objects xf-new-shape)

            rchanges [{:type :update-component
                       :id component-id
                       :name (:name new-shape)
                       :shapes new-shapes}]

            uchanges [{:type :update-component
                       :id component-id
                       :name (:name component-obj)
                       :shapes (vals component-objs)}]]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn sync-file
  [{:keys [file-id] :as params}]
  (us/assert (s/nilable ::us/uuid) file-id)
  (ptk/reify ::sync-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [[rchanges uchanges] (generate-sync-file state file-id)]
        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn- generate-sync-file
  [state file-id]
  (let [components
        (if (nil? file-id)
          (get-in state [:workspace-data :components])
          (get-in state [:workspace-libraries file-id :data :components]))]
    (loop [pages (seq (vals (get-in state [:workspace-data :pages-index])))
           rchanges []
           uchanges []]
      (let [page (first pages)]
        (if (nil? page)
          [rchanges uchanges]
          (let [[page-rchanges page-uchanges]
                (generate-sync-page page components)]
            (recur (next pages)
                   (concat rchanges page-rchanges)
                   (concat uchanges page-uchanges))))))))

(defn- generate-sync-page
  [page components]
  (let [linked-shapes
        (cph/select-objects #(some? (:component-id %)) page)]
    (loop [shapes (seq linked-shapes)
           rchanges []
           uchanges []]
      (let [shape (first shapes)]
        (if (nil? shape)
          [rchanges uchanges]
          (let [[shape-rchanges shape-uchanges]
                (generate-sync-shape-and-children shape page components)]
            (recur (next shapes)
                   (concat rchanges shape-rchanges)
                   (concat uchanges shape-uchanges))))))))

(defn- generate-sync-shape-and-children
  [root-shape page components]
  (let [objects (get page :objects)
        all-shapes (cph/get-object-with-children (:id root-shape) objects)
        component (get components (:component-id root-shape))
        root-component (get-in component [:objects (:shape-ref root-shape)])]
    (loop [shapes (seq all-shapes)
           rchanges []
           uchanges []]
      (let [shape (first shapes)]
        (if (nil? shape)
          [rchanges uchanges]
          (let [[shape-rchanges shape-uchanges]
                (generate-sync-shape shape root-shape root-component page component)]
            (recur (next shapes)
                   (concat rchanges shape-rchanges)
                   (concat uchanges shape-uchanges))))))))

(defn- generate-sync-shape
  [shape root-shape root-component page component]
  (if (nil? component)
    (remove-component-and-ref shape page)
    (let [component-shape (get (:objects component) (:shape-ref shape))]
      (if (nil? component-shape)
        (remove-ref shape page)
        (update-attrs shape component-shape root-shape root-component page)))))

(defn- remove-component-and-ref
  [shape page]
  [[{:type :mod-obj
     :page-id (:id page)
     :id (:id shape)
     :operations [{:type :set
                   :attr :component-id
                   :val nil}
                  {:type :set
                   :attr :component-file
                   :val nil}
                  {:type :set
                   :attr :shape-ref
                   :val nil}]}]
   [{:type :mod-obj
     :page-id (:id page)
     :id (:id shape)
     :operations [{:type :set
                   :attr :component-id
                   :val (:component-id shape)}
                  {:type :set
                   :attr :component-file
                   :val (:component-file shape)}
                  {:type :set
                   :attr :shape-ref
                   :val (:shape-ref shape)}]}]])

(defn- remove-ref
  [shape page]
  [[{:type :mod-obj
     :page-id (:id page)
     :id (:id shape)
     :operations [{:type :set
                   :attr :shape-ref
                   :val nil}]}]
   [{:type :mod-obj
     :page-id (:id page)
     :id (:id shape)
     :operations [{:type :set
                   :attr :shape-ref
                   :val (:shape-ref shape)}]}]])

(defn- update-attrs
  [shape component-shape root-shape root-component page]
  (let [new-pos (calc-new-pos shape component-shape root-shape root-component)]
    (loop [attrs (seq sync-attrs)
           roperations [{:type :set
                         :attr :x
                         :val (:x new-pos)}
                        {:type :set
                         :attr :y
                         :val (:y new-pos)}]
           uoperations [{:type :set
                         :attr :x
                         :val (:x shape)}
                        {:type :set
                         :attr :y
                         :val (:y shape)}]]

      (let [attr (first attrs)]
        (if (nil? attr)
          (let [rchanges [{:type :mod-obj
                           :page-id (:id page)
                           :id (:id shape)
                           :operations roperations}]
                uchanges [{:type :mod-obj
                           :page-id (:id page)
                           :id (:id shape)
                           :operations uoperations}]]
            [rchanges uchanges])
          (if-not (contains? shape attr)
            (recur (next attrs)
                   roperations
                   uoperations)
            (let [roperation {:type :set
                              :attr attr
                              :val (get component-shape attr)}
                  uoperation {:type :set
                              :attr attr
                              :val (get shape attr)}]
              (recur (next attrs)
                     (conj roperations roperation)
                     (conj uoperations uoperation)))))))))

(def sync-attrs [:content
                 :fill-color
                 :fill-color-ref-file
                 :fill-color-ref-id
                 :fill-opacity
                 :font-family
                 :font-size
                 :font-style
                 :font-weight
                 :letter-spacing
                 :line-height
                 :proportion
                 :rx
                 :ry
                 :stroke-color
                 :stroke-color-ref-file
                 :stroke-color-ref-id
                 :stroke-opacity
                 :stroke-style
                 :stroke-width
                 :stroke-alignment
                 :text-align
                 :width
                 :height
                 :interactions
                 :points
                 :transform])

(defn- calc-new-pos
  [shape component-shape root-shape root-component]
  (let [root-pos           (gpt/point (:x root-shape) (:y root-shape))
        root-component-pos (gpt/point (:x root-component) (:y root-component))
        component-pos      (gpt/point (:x component-shape) (:y component-shape))
        delta              (gpt/subtract component-pos root-component-pos)
        shape-pos          (gpt/point (:x shape) (:y shape))
        new-pos            (gpt/add root-pos delta)]
    new-pos))


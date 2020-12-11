(ns app.main.data.workspace.groups
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.selection :as dws]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn shapes-for-grouping
  [objects selected]
  (->> selected
       (map #(get objects %))
       (filter #(not= :frame (:type %)))
       (map #(assoc % ::index (cp/position-on-parent (:id %) objects)))
       (sort-by ::index)))

(defn- make-group
  [shapes prefix keep-name]
  (let [selrect   (gsh/selection-rect shapes)
        frame-id  (-> shapes first :frame-id)
        parent-id (-> shapes first :parent-id)
        group-name (if (and keep-name
                            (= (count shapes) 1)
                            (= (:type (first shapes)) :group))
                     (:name (first shapes))
                     (name (gensym prefix)))]
    (-> (cp/make-minimal-group frame-id selrect group-name)
        (gsh/setup selrect)
        (assoc :shapes (mapv :id shapes)))))

(defn prepare-create-group
  [page-id shapes prefix keep-name]
  (let [group (make-group shapes prefix keep-name)
        rchanges [{:type :add-obj
                   :id (:id group)
                   :page-id page-id
                   :frame-id (:frame-id (first shapes))
                   :parent-id (:parent-id (first shapes))
                   :obj group
                   :index (::index (first shapes))}
                  {:type :mov-objects
                   :page-id page-id
                   :parent-id (:id group)
                   :shapes (mapv :id shapes)}]

        uchanges (conj
                   (mapv (fn [obj] {:type :mov-objects
                                    :page-id page-id
                                    :parent-id (:parent-id obj)
                                    :index (::index obj)
                                    :shapes [(:id obj)]})
                         shapes)
                   {:type :del-obj
                    :id (:id group)
                    :page-id page-id})]
    [group rchanges uchanges]))

(defn prepare-remove-group
  [page-id group objects]
  (let [shapes    (:shapes group)
        parent-id (cp/get-parent (:id group) objects)
        parent    (get objects parent-id)
        index-in-parent (->> (:shapes parent)
                             (map-indexed vector)
                             (filter #(#{(:id group)} (second %)))
                             (ffirst))
        rchanges [{:type :mov-objects
                   :page-id page-id
                   :parent-id parent-id
                   :shapes shapes
                   :index index-in-parent}
                  {:type :del-obj
                   :page-id page-id
                   :id (:id group)}]
        uchanges [{:type :add-obj
                   :page-id page-id
                   :id (:id group)
                   :frame-id (:frame-id group)
                   :obj (assoc group :shapes [])}
                  {:type :mov-objects
                   :page-id page-id
                   :parent-id (:id group)
                   :shapes shapes}
                  {:type :mov-objects
                   :page-id page-id
                   :parent-id parent-id
                   :shapes [(:id group)]
                   :index index-in-parent}]]
    [rchanges uchanges]))


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
            shapes   (shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [[group rchanges uchanges] (prepare-create-group page-id shapes "Group-" false)]
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (dwc/select-shapes (d/ordered-set (:id group))))))))))

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
                (prepare-remove-group page-id group objects)]
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))))

(def mask-group
  (ptk/reify ::mask-group
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected (get-in state [:workspace-local :selected])
            shapes   (shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [;; If the selected shape is a group, we can use it. If not,
                ;; create a new group and set it as masked.
                [group rchanges uchanges]
                (if (and (= (count shapes) 1)
                         (= (:type (first shapes)) :group))
                  [(first shapes) [] []]
                  (prepare-create-group page-id shapes "Group-" true))

                rchanges (d/concat rchanges
                                   [{:type :mod-obj
                                     :page-id page-id
                                     :id (:id group)
                                     :operations [{:type :set
                                                   :attr :masked-group?
                                                   :val true}
                                                  {:type :set
                                                   :attr :selrect
                                                   :val (-> shapes first :selrect)}
                                                  {:type :set
                                                   :attr :points
                                                   :val (-> shapes first :points)}
                                                  {:type :set
                                                   :attr :transform
                                                   :val (-> shapes first :transform)}
                                                  {:type :set
                                                   :attr :transform-inverse
                                                   :val (-> shapes first :transform-inverse)}]}
                                    {:type :reg-objects
                                     :page-id page-id
                                     :shapes [(:id group)]}])

                uchanges (conj uchanges
                               {:type :mod-obj
                                :page-id page-id
                                :id (:id group)
                                :operations [{:type :set
                                              :attr :masked-group?
                                              :val nil}]}
                               {:type :reg-objects
                                :page-id page-id
                                :shapes [(:id group)]})]

            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (dwc/select-shapes (d/ordered-set (:id group))))))))))

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
                                         :val nil}]}
                          {:type :reg-objects
                           :page-id page-id
                           :shapes [(:id group)]}]

                uchanges [{:type :mod-obj
                           :page-id page-id
                           :id (:id group)
                           :operations [{:type :set
                                         :attr :masked-group?
                                         :val (:masked-group? group)}]}
                          {:type :reg-objects
                           :page-id page-id
                           :shapes [(:id group)]}]]

            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (dwc/select-shapes (d/ordered-set (:id group))))))))))



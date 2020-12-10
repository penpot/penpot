;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.libraries-helpers
  (:require
   [cljs.spec.alpha :as s]
   [app.common.spec :as us]
   [app.common.data :as d]
   [app.common.pages :as cph] ;; TODO: remove this namespace
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.pages :as cp]
   [app.util.logging :as log]
   [app.util.text :as ut]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

(defonce empty-changes [[] []])

(defonce color-sync-attrs
  [[:fill-color-ref-id   :color    :fill-color]
   [:fill-color-ref-id   :gradient :fill-color-gradient]
   [:fill-color-ref-id   :opacity  :fill-opacity]

   [:stroke-color-ref-id :color    :stroke-color]
   [:stroke-color-ref-id :gradient :stroke-color-gradient]
   [:stroke-color-ref-id :opacity  :stroke-opacity]])

(declare generate-sync-container)
(declare generate-sync-shape)
(declare has-asset-reference-fn)

(declare get-assets)
(declare generate-sync-shape-direct)
(declare generate-sync-shape-direct-recursive)
(declare generate-sync-shape-inverse)
(declare generate-sync-shape-inverse-recursive)

(declare compare-children)
(declare concat-changes)
(declare add-shape-to-instance)
(declare add-shape-to-master)
(declare remove-shape)
(declare move-shape)
(declare change-touched)
(declare update-attrs)
(declare reposition-shape)


;; ---- Create a new component ----

(defn make-component-shape
  "Clone the shape and all children. Generate new ids and detach
  from parent and frame. Update the original shapes to have links
  to the new ones."
  [shape objects]
  (assert (nil? (:component-id shape)))
  (assert (nil? (:component-file shape)))
  (assert (nil? (:shape-ref shape)))
  (let [;; Ensure that the component root is not an instance and
        ;; it's no longer tied to a frame.
        update-new-shape (fn [new-shape original-shape]
                           (cond-> new-shape
                             true
                             (-> (assoc :frame-id nil)
                                 (dissoc :component-root?))

                             (nil? (:parent-id new-shape))
                             (dissoc :component-id
                                     :component-file
                                     :shape-ref)))

        ;; Make the original shape an instance of the new component.
        ;; If one of the original shape children already was a component
        ;; instance, maintain this instanceness untouched.
        update-original-shape (fn [original-shape new-shape]
                                (cond-> original-shape
                                  (nil? (:shape-ref original-shape))
                                  (-> (assoc :shape-ref (:id new-shape))
                                      (dissoc :touched))

                                  (nil? (:parent-id new-shape))
                                  (assoc :component-id (:id new-shape)
                                         :component-file nil
                                         :component-root? true)

                                  (some? (:parent-id new-shape))
                                  (dissoc :component-root?)))]

    (cph/clone-object shape nil objects update-new-shape update-original-shape)))

(defn duplicate-component
  "Clone the root shape of the component and all children. Generate new
  ids from all of them."
  [component]
  (let [component-root (cph/get-component-root component)]
    (cph/clone-object component-root
                      nil
                      (get component :objects)
                      identity)))


;; ---- General library synchronization functions ----

(defn generate-sync-file
  "Generate changes to synchronize all shapes in all pages of the current file,
  with the given asset of the given library."
  [asset-type library-id state]
  (s/assert #{:colors :components :typographies} asset-type)
  (s/assert (s/nilable ::us/uuid) library-id)

  (log/info :msg "Sync local file with library"
            :asset-type asset-type
            :library (str (or library-id "local")))

  (let [library-items
        (if (nil? library-id)
          (get-in state [:workspace-data asset-type])
          (get-in state [:workspace-libraries library-id :data asset-type]))]

    (if (empty? library-items)
      empty-changes

      (loop [pages (vals (get-in state [:workspace-data :pages-index]))
             rchanges []
             uchanges []]
        (if-let [page (first pages)]
          (let [[page-rchanges page-uchanges]
                (generate-sync-container asset-type
                                         library-id
                                         state
                                         (cph/make-container page :page))]
            (recur (next pages)
                   (d/concat rchanges page-rchanges)
                   (d/concat uchanges page-uchanges)))
          [rchanges uchanges])))))

(defn generate-sync-library
  "Generate changes to synchronize all shapes inside components of the current
  file library, that use the given type of asset of the given library."
  [asset-type library-id state]

  (log/info :msg "Sync local components with library"
            :asset-type asset-type
            :library (str (or library-id "local")))

  (let [library-items
        (if (nil? library-id)
          (get-in state [:workspace-data asset-type])
          (get-in state [:workspace-libraries library-id :data asset-type]))]
    (if (empty? library-items)
      empty-changes

      (loop [local-components (seq (vals (get-in state [:workspace-data :components])))
             rchanges []
             uchanges []]
        (if-let [local-component (first local-components)]
          (let [[comp-rchanges comp-uchanges]
                (generate-sync-container asset-type
                                         library-id
                                         state
                                         (cph/make-container local-component
                                                             :component))]
            (recur (next local-components)
                   (d/concat rchanges comp-rchanges)
                   (d/concat uchanges comp-uchanges)))
          [rchanges uchanges])))))

(defn- generate-sync-container
  "Generate changes to synchronize all shapes in a particular container
  (a page or a component) that are linked to the given library."
  [asset-type library-id state container]

  (if (cph/page? container)
    (log/debug :msg "Sync page in local file" :page-id (:id container))
    (log/debug :msg "Sync component in local library" :component-id (:id container)))

  (let [has-asset-reference? (has-asset-reference-fn asset-type library-id)
        linked-shapes (cph/select-objects has-asset-reference? container)]
    (loop [shapes (seq linked-shapes)
           rchanges []
           uchanges []]
      (if-let [shape (first shapes)]
        (let [[shape-rchanges shape-uchanges]
              (generate-sync-shape asset-type
                                   library-id
                                   state
                                   container
                                   shape)]
          (recur (next shapes)
                 (d/concat rchanges shape-rchanges)
                 (d/concat uchanges shape-uchanges)))
        [rchanges uchanges]))))

(defn- has-asset-reference-fn
  "Gets a function that checks if a shape uses some asset of the given type
  in the given library."
  [asset-type library-id]
  (case asset-type
    :components
    (fn [shape] (and (:component-id shape)
                     (= (:component-file shape) library-id)))

    :colors
    (fn [shape] (if (= (:type shape) :text)
                  (->> shape
                       :content
                       ;; Check if any node in the content has a reference for the library
                       (ut/some-node
                        #(or (and (some? (:stroke-color-ref-id %))
                                  (= library-id (:stroke-color-ref-file %)))
                             (and (some? (:fill-color-ref-id %))
                                  (= library-id (:fill-color-ref-file %))))))
                  (some
                   #(let [attr (name %)
                          attr-ref-id (keyword (str attr "-ref-id"))
                          attr-ref-file (keyword (str attr "-ref-file"))]
                      (and (get shape attr-ref-id)
                           (= library-id (get shape attr-ref-file))))
                   (map #(nth % 2) color-sync-attrs))))

    :typographies
    (fn [shape]
      (and (= (:type shape) :text)
           (->> shape
                :content
                ;; Check if any node in the content has a reference for the library
                (ut/some-node
                 #(and (some? (:typography-ref-id %))
                       (= library-id (:typography-ref-file %)))))))))

(defmulti generate-sync-shape
  "Generate changes to synchronize one shape, that use the given type
  of asset of the given library."
  (fn [type _ _ _ _] type))

(defmethod generate-sync-shape :components
  [_ library-id state container shape]
  (generate-sync-shape-direct container
                              (:id shape)
                              (get state :workspace-data)
                              (get state :workspace-libraries)
                              false))

(defn- generate-sync-text-shape [shape container update-node]
  (let [old-content (:content shape)
        new-content (ut/map-node update-node old-content)
        rchanges [(as-> {:type :mod-obj
                         :id (:id shape)
                         :operations [{:type :set
                                       :attr :content
                                       :val new-content}]} $
                    (if (cph/page? container)
                      (assoc $ :page-id (:id container))
                      (assoc $ :component-id (:id container))))]
        uchanges [(as-> {:type :mod-obj
                         :id (:id shape)
                         :operations [{:type :set
                                       :attr :content
                                       :val old-content}]} $
                    (if (cph/page? container)
                      (assoc $ :page-id (:id container))
                      (assoc $ :component-id (:id container))))]]

    (if (= new-content old-content)
      empty-changes
      [rchanges uchanges])))

(defmethod generate-sync-shape :colors
  [_ library-id state container shape]

  ;; Synchronize a shape that uses some colors of the library. The value of the
  ;; color in the library is copied to the shape.
  (let [colors       (get-assets library-id :colors state)]
    (if (= :text (:type shape))
      (let [update-node (fn [node]
                          (if-let [color (get colors (:fill-color-ref-id node))]
                            (assoc node
                                   :fill-color (:color color)
                                   :fill-opacity (:opacity color)
                                   :fill-color-gradient (:gradient color))
                            node))]
        (generate-sync-text-shape shape container update-node))
      (loop [attrs (seq color-sync-attrs)
             roperations []
             uoperations []]
        (let [[attr-ref-id color-attr attr] (first attrs)]
          (if (nil? attr)
            (if (empty? roperations)
              empty-changes
              (let [rchanges [(as-> {:type :mod-obj
                                     :id (:id shape)
                                     :operations roperations} $
                                (if (cph/page? container)
                                  (assoc $ :page-id (:id container))
                                  (assoc $ :component-id (:id container))))]
                    uchanges [(as-> {:type :mod-obj
                                     :id (:id shape)
                                     :operations uoperations} $
                                (if (cph/page? container)
                                  (assoc $ :page-id (:id container))
                                  (assoc $ :component-id (:id container))))]]
                [rchanges uchanges]))
            (if-not (contains? shape attr-ref-id)
              (recur (next attrs)
                     roperations
                     uoperations)
              (let [color (get colors (get shape attr-ref-id))
                    roperation {:type :set
                                :attr attr
                                :val (color-attr color)
                                :ignore-touched true}
                    uoperation {:type :set
                                :attr attr
                                :val (get shape attr)
                                :ignore-touched true}]
                (recur (next attrs)
                       (conj roperations roperation)
                       (conj uoperations uoperation))))))))))

(defmethod generate-sync-shape :typographies
  [_ library-id state container shape]

  ;; Synchronize a shape that uses some typographies of the library. The attributes
  ;; of the typography are copied to the shape."
  (let [typographies (get-assets library-id :typographies state)
        update-node (fn [node]
                      (if-let [typography (get typographies (:typography-ref-id node))]
                        (merge node (d/without-keys typography [:name :id]))
                        node))]
    (generate-sync-text-shape shape container update-node)))


;; ---- Component synchronization helpers ----

(defn- get-assets
  [library-id asset-type state]
  (if (nil? library-id)
    (get-in state [:workspace-data asset-type])
    (get-in state [:workspace-libraries library-id :data asset-type])))

(defn generate-sync-shape-direct
  "Generate changes to synchronize one shape that the root of a component
  instance, and all its children, from the given component.
  If reset? is false, all atributes of each component shape that have
  changed, and whose group has not been touched in the instance shape will
  be copied to this one.
  If reset? is true, all changed attributes will be copied and the 'touched'
  flags in the instance shape will be cleared."
  [container shape-id local-file libraries reset?]
  (log/debug :msg "Sync shape direct" :shape (str shape-id) :reset? reset?)
  (let [shape-inst   (cph/get-shape container shape-id)
        component    (cph/get-component (:component-id shape-inst)
                                        (:component-file shape-inst)
                                        local-file
                                        libraries)
        shape-master (cph/get-shape component (:shape-ref shape-inst))

        root-inst    shape-inst
        root-master  (cph/get-component-root component)]

    (generate-sync-shape-direct-recursive container
                                          shape-inst
                                          component
                                          shape-master
                                          root-inst
                                          root-master
                                          {:omit-touched? (not reset?)
                                           :reset-touched? reset?
                                           :copy-touched? false})))

(defn- generate-sync-shape-direct-recursive
  [container shape-inst component shape-master root-inst root-master
   {:keys [omit-touched? reset-touched? copy-touched?]
    :as options :or {omit-touched? false
                     reset-touched? false
                     copy-touched? false}}]
  (log/trace :msg "Sync shape direct recursive"
             :shape (str (:name shape-inst))
             :component (:name component)
             :options options)

  (let [root-inst (if (:component-id shape-inst)
                    shape-inst
                    root-inst)
        root-master (if (:component-id shape-inst)
                      shape-master
                      root-master)

        [rchanges uchanges]
        (concat-changes
          (update-attrs shape-inst
                        shape-master
                        root-inst
                        root-master
                        container
                        options)
          (change-touched shape-inst
                          shape-master
                          container
                          options))

        children-inst   (mapv #(cph/get-shape container %)
                              (:shapes shape-inst))
        children-master (mapv #(cph/get-shape component %)
                              (:shapes shape-master))

        only-inst (fn [shape-inst]
                    (remove-shape shape-inst
                                  container
                                  omit-touched?))

        only-master (fn [shape-master]
                      (add-shape-to-instance shape-master
                                             component
                                             container
                                             root-inst
                                             root-master
                                             omit-touched?))

        both (fn [shape-inst shape-master]
               (let [options (if-not (:component-id shape-inst)
                               options
                               {:omit-touched? false
                                :reset-touched? false
                                :copy-touched? true})]

                 (generate-sync-shape-direct-recursive container
                                                       shape-inst
                                                       component
                                                       shape-master
                                                       root-inst
                                                       root-master
                                                       options)))

        moved (fn [shape-inst shape-master]
                (move-shape
                  shape-inst
                  (d/index-of children-inst shape-inst)
                  (d/index-of children-master shape-master)
                  container
                  omit-touched?))

        [child-rchanges child-uchanges]
        (compare-children children-inst
                          children-master
                          only-inst
                          only-master
                          both
                          moved
                          false)]

    [(d/concat rchanges child-rchanges)
     (d/concat uchanges child-uchanges)]))

(defn- generate-sync-shape-inverse
  "Generate changes to update the component a shape is linked to, from
  the values in the shape and all its children.
  All atributes of each instance shape that have changed, will be copied
  to the component shape. Also clears the 'touched' flags in the source
  shapes.
  And if the component shapes are, in turn, instances of a second component,
  their 'touched' flags will be set accordingly."
  [page-id shape-id local-file libraries]
  (log/debug :msg "Sync shape inverse" :shape (str shape-id))
  (let [container      (cph/get-container page-id :page local-file)
        shape-inst     (cph/get-shape container shape-id)
        component      (cph/get-component (:component-id shape-inst)
                                          (:component-file shape-inst)
                                          local-file
                                          libraries)
        shape-master   (cph/get-shape component (:shape-ref shape-inst))

        root-inst      shape-inst
        root-master    (cph/get-component-root component)]

    (generate-sync-shape-inverse-recursive container
                                           shape-inst
                                           component
                                           shape-master
                                           root-inst
                                           root-master
                                           {:reset-touched? false
                                            :set-touched? true
                                            :copy-touched? false})))

(defn- generate-sync-shape-inverse-recursive
  [container shape-inst component shape-master root-inst root-master
   {:keys [reset-touched? set-touched? copy-touched?]
    :as options :or {reset-touched? false
                     set-touched? false
                     copy-touched? false}}]
  (log/trace :msg "Sync shape inverse recursive"
             :shape (str (:name shape-inst))
             :component (:name component)
             :options options)

  (let [root-inst (if (:component-id shape-inst)
                    shape-inst
                    root-inst)
        root-master (if (:component-id shape-inst)
                      shape-master
                      root-master)

        component-container (cph/make-container component :component)

        [rchanges uchanges]
        (concat-changes
          (update-attrs shape-master
                        shape-inst
                        root-master
                        root-inst
                        component-container
                        options)
          (concat-changes
            (change-touched shape-master
                            shape-inst
                            component-container
                            options)
            (if (:set-touched? options)
              (change-touched shape-inst nil container {:reset-touched? true})
              empty-changes)))

        children-inst   (mapv #(cph/get-shape container %)
                              (:shapes shape-inst))
        children-master (mapv #(cph/get-shape component %)
                              (:shapes shape-master))

        only-inst (fn [shape-inst]
                    (add-shape-to-master shape-inst
                                         component
                                         container
                                         root-inst
                                         root-master))

        only-master (fn [shape-master]
                      (remove-shape shape-master
                                    component-container
                                    false))
 
        both (fn [shape-inst shape-master]
               (let [options (if-not (:component-id shape-inst)
                               options
                               {:reset-touched? false
                                :set-touched? false
                                :copy-touched? true})]

                 (generate-sync-shape-inverse-recursive container
                                                        shape-inst
                                                        component
                                                        shape-master
                                                        root-inst
                                                        root-master
                                                        options)))

        moved (fn [shape-inst shape-master]
                (move-shape
                  shape-master
                  (d/index-of children-master shape-master)
                  (d/index-of children-inst shape-inst)
                  component-container
                  false))

        [child-rchanges child-uchanges]
        (compare-children children-inst
                          children-master
                          only-inst
                          only-master
                          both
                          moved
                          true)]

    [(d/concat rchanges child-rchanges)
     (d/concat uchanges child-uchanges)]))


; ---- Operation generation helpers ----

(defn- compare-children
  [children-inst children-master only-inst-cb only-master-cb both-cb moved-cb inverse?]
  (loop [children-inst (seq (or children-inst []))
         children-master (seq (or children-master []))
         [rchanges uchanges] [[] []]]
    (let [child-inst (first children-inst)
          child-master (first children-master)]
      (cond
        (and (nil? child-inst) (nil? child-master))
        [rchanges uchanges]

        (nil? child-inst)
        (reduce (fn [changes child]
                  (concat-changes changes (only-master-cb child)))
                [rchanges uchanges]
                children-master)

        (nil? child-master)
        (reduce (fn [changes child]
                  (concat-changes changes (only-inst-cb child)))
                [rchanges uchanges]
                children-inst)

        :else
        (if (cph/is-master-of child-master child-inst)
          (recur (next children-inst)
                 (next children-master)
                 (concat-changes [rchanges uchanges]
                                 (both-cb child-inst child-master)))

          (let [child-inst'   (d/seek #(cph/is-master-of child-master %)
                                      children-inst)
                child-master' (d/seek #(cph/is-master-of % child-inst)
                                      children-master)]
            (cond
              (nil? child-inst')
              (recur children-inst
                     (next children-master)
                     (concat-changes [rchanges uchanges]
                                     (only-master-cb child-master)))

              (nil? child-master')
              (recur (next children-inst)
                     children-master
                     (concat-changes [rchanges uchanges]
                                     (only-inst-cb child-inst)))

              :else
              (if inverse?
                (recur (next children-inst)
                       (remove #(= (:id %) (:id child-master')) children-master)
                       (-> [rchanges uchanges]
                           (concat-changes (both-cb child-inst' child-master))
                           (concat-changes (moved-cb child-inst child-master'))))
                (recur (remove #(= (:id %) (:id child-inst')) children-inst)
                       (next children-master)
                       (-> [rchanges uchanges]
                           (concat-changes (both-cb child-inst child-master'))
                           (concat-changes (moved-cb child-inst' child-master))))))))))))

(defn concat-changes
  [[rchanges1 uchanges1] [rchanges2 uchanges2]]
  [(d/concat rchanges1 rchanges2)
   (d/concat uchanges1 uchanges2)])

(defn- add-shape-to-instance
  [component-shape component container root-instance root-master omit-touched?]
  (log/info :msg (str "ADD [P] " (:name component-shape)))
  (let [component-parent-shape (cph/get-shape component (:parent-id component-shape))
        parent-shape           (d/seek #(cph/is-master-of component-parent-shape %)
                                       (cph/get-object-with-children (:id root-instance)
                                                                     (:objects container)))
        all-parents  (vec (cons (:id parent-shape)
                                (cph/get-parents parent-shape (:objects container))))

        update-new-shape (fn [new-shape original-shape]
                           (let [new-shape (reposition-shape new-shape
                                                             root-master
                                                             root-instance)]
                             (cond-> new-shape
                               true
                               (assoc :frame-id (:frame-id parent-shape))

                               (nil? (:shape-ref original-shape))
                               (assoc :shape-ref (:id original-shape))

                               (some? (:shape-ref original-shape))
                               (assoc :shape-ref (:shape-ref original-shape))

                               (:component-id original-shape)
                               (assoc :component-id (:component-id original-shape))

                               (:component-file original-shape)
                               (assoc :component-file (:component-file original-shape))

                               (:component-root original-shape)
                               (assoc :component-root (:component-root original-shape))

                               (:touched original-shape)
                               (assoc :touched (:touched original-shape)))))

        update-original-shape (fn [original-shape new-shape]
                                original-shape)

        [new-shape new-shapes _]
        (cph/clone-object component-shape
                          (:id parent-shape)
                          (get component :objects)
                          update-new-shape
                          update-original-shape)

        rchanges (d/concat
                   (mapv (fn [shape']
                           (as-> {:type :add-obj
                                  :id (:id shape')
                                  :parent-id (:parent-id shape')
                                  :ignore-touched true
                                  :obj shape'} $
                             (cond-> $
                               (:frame-id shape')
                               (assoc :frame-id (:frame-id shape')))
                             (if (cph/page? container)
                               (assoc $ :page-id (:id container))
                               (assoc $ :component-id (:id container)))))
                         new-shapes)
                   [(as-> {:type :reg-objects
                           :shapes all-parents} $
                      (if (cph/page? container)
                        (assoc $ :page-id (:id container))
                        (assoc $ :component-id (:id container))))])

        uchanges (d/concat
                   (mapv (fn [shape']
                           (as-> {:type :del-obj
                                  :id (:id shape')
                                  :ignore-touched true} $
                             (if (cph/page? container)
                               (assoc $ :page-id (:id container))
                               (assoc $ :component-id (:id container)))))
                         new-shapes))]

    (if (and (cph/touched-group? parent-shape :shapes-group) omit-touched?)
      empty-changes
      [rchanges uchanges])))

(defn- add-shape-to-master
  [shape component page root-instance root-master]
  (log/info :msg (str "ADD [C] " (:name shape)))
  (let [parent-shape           (cph/get-shape page (:parent-id shape))
        component-parent-shape (d/seek #(cph/is-master-of % parent-shape)
                                       (cph/get-object-with-children (:id root-master)
                                                                     (:objects component)))
        all-parents  (vec (cons (:id component-parent-shape)
                                (cph/get-parents component-parent-shape (:objects component))))

        update-new-shape (fn [new-shape original-shape]
                           (reposition-shape new-shape
                                             root-instance
                                             root-master))

        update-original-shape (fn [original-shape new-shape]
                                (if-not (:shape-ref original-shape)
                                  (assoc original-shape
                                         :shape-ref (:id new-shape))
                                  original-shape))

        [new-shape new-shapes updated-shapes]
        (cph/clone-object shape
                          (:id component-parent-shape)
                          (get page :objects)
                          update-new-shape
                          update-original-shape)

        rchanges (d/concat
                   (mapv (fn [shape']
                           {:type :add-obj
                            :id (:id shape')
                            :component-id (:id component)
                            :parent-id (:parent-id shape')
                            :ignore-touched true
                            :obj shape'})
                         new-shapes)
                   [{:type :reg-objects
                     :component-id (:id component)
                     :shapes all-parents}]
                   (mapv (fn [shape']
                           {:type :mod-obj
                            :page-id (:id page)
                            :id (:id shape')
                            :operations [{:type :set
                                          :attr :component-id
                                          :val (:component-id shape')}
                                         {:type :set
                                          :attr :component-file
                                          :val (:component-file shape')}
                                         {:type :set
                                          :attr :component-root?
                                          :val (:component-root? shape')}
                                         {:type :set
                                          :attr :shape-ref
                                          :val (:shape-ref shape')}
                                         {:type :set
                                          :attr :touched
                                          :val (:touched shape')}]})
                         updated-shapes))

        uchanges (d/concat
                   (mapv (fn [shape']
                           {:type :del-obj
                            :id (:id shape')
                            :page-id (:id page)
                            :ignore-touched true})
                         new-shapes))]

    [rchanges uchanges]))

(defn- remove-shape
  [shape container omit-touched?]
  (log/info :msg (str "REMOVE-SHAPE "
                      (if (cph/page? container) "[P] " "[C] ")
                      (:name shape)))
  (let [objects    (get container :objects)
        parents    (cph/get-parents (:id shape) objects)
        parent     (first parents)
        children   (cph/get-children (:id shape) objects)

        rchanges [(as-> {:type :del-obj
                         :id (:id shape)
                         :ignore-touched true} $
                    (if (cph/page? container)
                      (assoc $ :page-id (:id container))
                      (assoc $ :component-id (:id container))))]

        add-change (fn [id]
                     (let [shape' (get objects id)]
                       (as-> {:type :add-obj
                              :id id
                              :index (cph/position-on-parent id objects)
                              :parent-id (:parent-id shape')
                              :ignore-touched true
                              :obj shape'} $
                         (cond-> $
                           (:frame-id shape')
                           (assoc :frame-id (:frame-id shape')))
                         (if (cph/page? container)
                           (assoc $ :page-id (:id container))
                           (assoc $ :component-id (:id container))))))

        uchanges (d/concat
                   [(add-change (:id shape))]
                   (map add-change children)
                   [(as-> {:type :reg-objects
                           :shapes (vec parents)} $
                      (if (cph/page? container)
                        (assoc $ :page-id (:id container))
                        (assoc $ :component-id (:id container))))])]

    (if (and (cph/touched-group? parent :shapes-group) omit-touched?)
      empty-changes
      [rchanges uchanges])))

(defn- move-shape
  [shape index-before index-after container omit-touched?]
  (log/info :msg (str "MOVE "
                      (if (cph/page? container) "[P] " "[C] ")
                      (:name shape)
                      " "
                      index-before
                      " -> "
                      index-after))
  (let [parent (cph/get-shape container (:parent-id shape))

        rchanges [(as-> {:type :mov-objects
                         :parent-id (:parent-id shape)
                         :shapes [(:id shape)]
                         :index index-after
                         :ignore-touched true} $
                    (if (cph/page? container)
                      (assoc $ :page-id (:id container))
                      (assoc $ :component-id (:id container))))]
        uchanges [(as-> {:type :mov-objects
                         :parent-id (:parent-id shape)
                         :shapes [(:id shape)]
                         :index index-before
                         :ignore-touched true} $
                    (if (cph/page? container)
                      (assoc $ :page-id (:id container))
                      (assoc $ :component-id (:id container))))]]

    (if (and (cph/touched-group? parent :shapes-group) omit-touched?)
      empty-changes
      [rchanges uchanges])))

(defn- change-touched
  [dest-shape orig-shape container
   {:keys [reset-touched? copy-touched?]
    :as options :or {reset-touched? false
                     copy-touched? false}}]
  (if (or (nil? (:shape-ref dest-shape))
          (not (or reset-touched? copy-touched?)))
    empty-changes
    (do
      (log/info :msg (str "CHANGE-TOUCHED "
                          (if (cph/page? container) "[P] " "[C] ")
                          (:name dest-shape))
                :options options)
      (let [rchanges [(as-> {:type :mod-obj
                             :id (:id dest-shape)
                             :operations
                             [{:type :set-touched
                               :touched
                               (cond reset-touched?
                                     nil
                                     copy-touched?
                                     (:touched orig-shape))}]} $
                        (if (cph/page? container)
                          (assoc $ :page-id (:id container))
                          (assoc $ :component-id (:id container))))]

            uchanges [(as-> {:type :mod-obj
                             :id (:id dest-shape)
                             :operations
                             [{:type :set-touched
                               :touched (:touched dest-shape)}]} $
                        (if (cph/page? container)
                          (assoc $ :page-id (:id container))
                          (assoc $ :component-id (:id container))))]]
        [rchanges uchanges]))))

(defn- set-touched-shapes-group
  [shape container]
  (if-not (:shape-ref shape)
    empty-changes
    (do
      (log/info :msg (str "SET-TOUCHED-SHAPES-GROUP "
                          (if (cph/page? container) "[P] " "[C] ")
                          (:name shape)))
      (let [rchanges [(as-> {:type :mod-obj
                             :id (:id shape)
                             :operations
                             [{:type :set-touched
                               :touched (cph/set-touched-group
                                          (:touched shape)
                                          :shapes-group)}]} $
                        (if (cph/page? container)
                          (assoc $ :page-id (:id container))
                          (assoc $ :component-id (:id container))))]

            uchanges [(as-> {:type :mod-obj
                             :id (:id shape)
                             :operations
                             [{:type :set-touched
                               :touched (:touched shape)}]} $
                        (if (cph/page? container)
                          (assoc $ :page-id (:id container))
                          (assoc $ :component-id (:id container))))]]
        [rchanges uchanges]))))

(defn- update-attrs
  "The main function that implements the sync algorithm. Copy
  attributes that have changed in the origin shape to the dest shape.
  If omit-touched? is true, attributes whose group has been touched
  in the destination shape will be ignored.
  If reset-touched? is true, the 'touched' flags will be cleared in
  the dest shape.
  If set-touched? is true, the corresponding 'touched' flags will be
  set in dest shape if they are different than their current values.
  If copy-touched? is true, the value of 'touched' flags in the
  origin shape will be copied as is to the dest shape."
  [dest-shape origin-shape dest-root origin-root container
   {:keys [omit-touched? reset-touched? set-touched? copy-touched?]
    :as options :or {omit-touched? false
                     reset-touched? false
                     set-touched? false
                     copy-touched? false}}]

  (log/info :msg (str "SYNC "
                      (:name origin-shape)
                      " -> "
                      (if (cph/page? container) "[P] " "[C] ")
                      (:name dest-shape)))

  (let [; To synchronize geometry attributes we need to make a prior
        ; operation, because coordinates are absolute, but we need to
        ; sync only the position relative to the origin of the component.
        ; We solve this by moving the origin shape so it is aligned with
        ; the dest root before syncing.
        origin-shape (reposition-shape origin-shape origin-root dest-root)
        touched      (get dest-shape :touched #{})]

    (loop [attrs (seq (keys cp/component-sync-attrs))
           roperations []
           uoperations []]

      (let [attr (first attrs)]
        (if (nil? attr)
          (let [roperations (cond
                              reset-touched?
                              (conj roperations
                                    {:type :set-touched
                                     :touched nil})
                              copy-touched?
                              (conj roperations
                                    {:type :set-touched
                                     :touched (:touched origin-shape)})
                              :else
                              roperations)

                uoperations (cond
                              (or reset-touched? copy-touched?)
                              (conj uoperations
                                    {:type :set-touched
                                     :touched (:touched dest-shape)})
                              :else
                              uoperations)

                rchanges [(as-> {:type :mod-obj
                                 :id (:id dest-shape)
                                 :operations roperations} $
                            (if (cph/page? container)
                              (assoc $ :page-id (:id container))
                              (assoc $ :component-id (:id container))))]
                uchanges [(as-> {:type :mod-obj
                                 :id (:id dest-shape)
                                 :operations uoperations} $
                            (if (cph/page? container)
                              (assoc $ :page-id (:id container))
                              (assoc $ :component-id (:id container))))]]
            [rchanges uchanges])

          (let [roperation {:type :set
                            :attr attr
                            :val (get origin-shape attr)
                            :ignore-touched (not set-touched?)}
                uoperation {:type :set
                            :attr attr
                            :val (get dest-shape attr)
                            :ignore-touched (not set-touched?)}

                attr-group (get cp/component-sync-attrs attr)]
            (if (and (touched attr-group) omit-touched?)
              (recur (next attrs)
                     roperations
                     uoperations)
              (recur (next attrs)
                     (conj roperations roperation)
                     (conj uoperations uoperation)))))))))

(defn- reposition-shape
  [shape origin-root dest-root]
  (let [shape-pos (fn [shape]
                    (gpt/point (get-in shape [:selrect :x])
                               (get-in shape [:selrect :y])))

        origin-root-pos (shape-pos origin-root)
        dest-root-pos   (shape-pos dest-root)
        delta           (gpt/subtract dest-root-pos origin-root-pos)]
    (geom/move shape delta)))

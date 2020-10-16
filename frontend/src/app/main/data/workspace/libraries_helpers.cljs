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
   [app.common.pages-helpers :as cph]
   [app.common.geom.point :as gpt]
   [app.common.pages :as cp]
   [app.util.text :as ut]))

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
(declare resolve-shapes-and-components)
(declare generate-sync-shape-and-children-components)
(declare generate-sync-shape-inverse)
(declare generate-sync-shape<-component)
(declare generate-sync-shape->component)
(declare remove-component-and-ref)
(declare remove-ref)
(declare reset-touched)
(declare update-attrs)
(declare calc-new-pos)


;; ---- Create a new component ----

(defn make-component-shape
  "Clone the shape and all children. Generate new ids and detach
  from parent and frame. Update the original shapes to have links
  to the new ones."
  [shape objects]
  (assert (nil? (:component-id shape)))
  (assert (nil? (:component-file shape)))
  (assert (nil? (:shape-ref shape)))
  (let [update-new-shape (fn [new-shape original-shape]
                           (cond-> new-shape
                             true
                             (assoc :frame-id nil)

                             (nil? (:parent-id new-shape))
                             (dissoc :component-id
                                     :component-file
                                     :component-root?
                                     :shape-ref)))

        ;; Make the original shape an instance of the new component.
        ;; If one of the original shape children already was a component
        ;; instance, the 'instanceness' is copied into the new component.
        update-original-shape (fn [original-shape new-shape]
                                (cond-> original-shape
                                  true
                                  (-> (assoc :shape-ref (:id new-shape))
                                      (dissoc :touched))

                                  (nil? (:parent-id new-shape))
                                  (assoc :component-id (:id new-shape)
                                         :component-file nil
                                         :component-root? true)

                                  (some? (:parent-id new-shape))
                                  (dissoc :component-root?)))]

    (cph/clone-object shape nil objects update-new-shape update-original-shape)))


;; ---- General library synchronization functions ----

(defn generate-sync-file
  "Generate changes to synchronize all shapes in all pages of the current file,
  with the given asset of the given library."
  [asset-type library-id state]

  (s/assert #{:colors :components :typographies} asset-type)
  (s/assert (s/nilable ::us/uuid) library-id)

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
                                         page
                                         (:id page)
                                         nil)]
            (recur (next pages)
                   (d/concat rchanges page-rchanges)
                   (d/concat uchanges page-uchanges)))
          [rchanges uchanges])))))

(defn generate-sync-library
  "Generate changes to synchronize all shapes inside components of the current
  file library, that use the given type of asset of the given library."
  [asset-type library-id state]
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
                                         local-component
                                         nil
                                         (:id local-component))]
            (recur (next local-components)
                   (d/concat rchanges comp-rchanges)
                   (d/concat uchanges comp-uchanges)))
          [rchanges uchanges])))))

(defn- generate-sync-container
  "Generate changes to synchronize all shapes in a particular container
  (a page or a component) that are linked to the given library."
  [asset-type library-id state container page-id component-id]
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
                                   (get container :objects)
                                   page-id
                                   component-id
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
    (fn [shape] (and (:component-root? shape)
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
  (fn [type _ _ _ _ _ _ _] type))

(defmethod generate-sync-shape :components
  [_ library-id state objects page-id component-id shape]
  (let [[all-shapes component root-component]
        (resolve-shapes-and-components shape
                                       objects
                                       state
                                       false)]

    (generate-sync-shape-and-children-components shape
                                                 all-shapes
                                                 component
                                                 root-component
                                                 page-id
                                                 component-id
                                                 false)))

(defn- generate-sync-text-shape [shape page-id component-id update-node]
  (let [old-content (:content shape)
        new-content (ut/map-node update-node old-content)
        rchanges [(d/without-nils {:type :mod-obj
                                   :page-id page-id
                                   :component-id component-id
                                   :id (:id shape)
                                   :operations [{:type :set
                                                 :attr :content
                                                 :val new-content}]})]
        lchanges [(d/without-nils {:type :mod-obj
                                   :page-id page-id
                                   :component-id component-id
                                   :id (:id shape)
                                   :operations [{:type :set
                                                 :attr :content
                                                 :val old-content}]})]]
    (if (= new-content old-content)
      empty-changes
      [rchanges lchanges])))


(defmethod generate-sync-shape :colors
  [_ library-id state _ page-id component-id shape]

  ;; Synchronize a shape that uses some colors of the library. The value of the
  ;; color in the library is copied to the shape.
  (let [colors (get-assets library-id :colors state)]
    (if (= :text (:type shape))
      (let [update-node (fn [node]
                          (if-let [color (get colors (:fill-color-ref-id node))]
                            (assoc node
                                   :fill-color (:color color)
                                   :fill-opacity (:opacity color)
                                   :fill-color-gradient (:gradient color))
                            node))]
        (generate-sync-text-shape shape page-id component-id update-node))
      (loop [attrs (seq color-sync-attrs)
             roperations []
             uoperations []]
        (let [[attr-ref-id color-attr attr] (first attrs)]
          (if (nil? attr)
            (if (empty? roperations)
              empty-changes
              (let [rchanges [(d/without-nils {:type :mod-obj
                                               :page-id page-id
                                               :component-id component-id
                                               :id (:id shape)
                                               :operations roperations})]
                    uchanges [(d/without-nils {:type :mod-obj
                                               :page-id page-id
                                               :component-id component-id
                                               :id (:id shape)
                                               :operations uoperations})]]
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
  [_ library-id state _ page-id component-id shape]

  ;; Synchronize a shape that uses some typographies of the library. The attributes
  ;; of the typography are copied to the shape."
  (let [typographies (get-assets library-id :typographies state)
        update-node (fn [node]
                      (if-let [typography (get typographies (:typography-ref-id node))]
                        (merge node (d/without-keys typography [:name :id]))
                        node))]
    (generate-sync-text-shape shape page-id component-id update-node)))


;; ---- Component synchronization helpers ----

(defn- get-assets
  [library-id asset-type state]
  (if (nil? library-id)
    (get-in state [:workspace-data asset-type])
    (get-in state [:workspace-libraries library-id :data asset-type])))

(defn- get-component
  [state file-id component-id]
  (let [components (if (nil? file-id)
                     (get-in state [:workspace-data :components])
                     (get-in state [:workspace-libraries file-id :data :components]))]
    (get components component-id)))

(defn resolve-shapes-and-components
  "Get all shapes inside a component instance, and the component they are
  linked with. If follow-indirection? is true, and the shape corresponding
  to the root shape is also a component instance, follow the link and get
  the final component."
  [shape objects state follow-indirection?]
  (loop [all-shapes (cph/get-object-with-children (:id shape) objects)
         local-objects objects
         local-shape shape]

    (let [root-shape (cph/get-root-shape local-shape local-objects)
          component (get-component state
                                   (get root-shape :component-file)
                                   (get root-shape :component-id))
          component-shape (get-in component [:objects (:shape-ref local-shape)])]

      (if (or (nil? (:component-id component-shape))
              (not follow-indirection?))
        [all-shapes component component-shape]
        (let [resolve-indirection
              (fn [shape]
                (let [component-shape (get-in component [:objects (:shape-ref shape)])]
                  (-> shape
                      (assoc :shape-ref (:shape-ref component-shape))
                      (d/assoc-when :component-id (:component-id component-shape))
                      (d/assoc-when :component-file (:component-file component-shape)))))
              new-shapes (map resolve-indirection all-shapes)]
          (recur new-shapes
                 (:objects component)
                 component-shape))))))

(defn generate-sync-shape-and-children-components
  "Generate changes to synchronize one shape that the root of a component
  instance, and all its children, from the given component.
  If reset? is false, all atributes of each component shape that have
  changed, and whose group has not been touched in the instance shape will
  be copied to this one.
  If reset? is true, all changed attributes will be copied and the 'touched'
  flags in the instance shape will be cleared."
  [root-shape all-shapes component root-component page-id component-id reset?]
  (loop [shapes (seq all-shapes)
         rchanges []
         uchanges []]
    (let [shape (first shapes)]
      (if (nil? shape)
        [rchanges uchanges]
        (let [[shape-rchanges shape-uchanges]
              (generate-sync-shape<-component
                shape
                root-shape
                root-component
                component
                page-id
                component-id
                reset?)]
          (recur (next shapes)
                 (d/concat rchanges shape-rchanges)
                 (d/concat uchanges shape-uchanges)))))))

(defn- generate-sync-shape-inverse
  "Generate changes to update the component a shape is linked to, from
  the values in the shape and all its children.
  All atributes of each instance shape that have changed, will be copied
  to the component shape. Also clears the 'touched' flags in the source
  shapes.
  And if the component shapes are, in turn, instances of a second component,
  their 'touched' flags will be set accordingly."
  [root-shape all-shapes component root-component page-id]
  (loop [shapes (seq all-shapes)
         rchanges []
         uchanges []]
    (let [shape (first shapes)]
      (if (nil? shape)
        [rchanges uchanges]
        (let [[shape-rchanges shape-uchanges]
              (generate-sync-shape->component
                shape
                root-shape
                root-component
                component
                page-id)]
          (recur (next shapes)
                 (d/concat rchanges shape-rchanges)
                 (d/concat uchanges shape-uchanges)))))))

(defn- generate-sync-shape<-component
  "Generate changes to synchronize one shape that is linked to other shape
  inside a component. Same considerations as above about reset-touched?"
  [shape root-shape root-component component page-id component-id reset?]
  (if (nil? component)
    (remove-component-and-ref shape page-id component-id)
    (let [component-shape (get (:objects component) (:shape-ref shape))]
      (if (nil? component-shape)
        (remove-ref shape page-id component-id)
        (update-attrs shape
                      component-shape
                      root-shape
                      root-component
                      page-id
                      component-id
                      {:omit-touched? (not reset?)
                       :reset-touched? reset?
                       :set-touched? false})))))

(defn- generate-sync-shape->component
  "Generate changes to synchronize one shape inside a component, with other
  shape that is linked to it."
  [shape root-shape root-component component page-id]
  ;; ===== Uncomment this to debug =====
  ;; (js/console.log "component" (clj->js component))
  (if (nil? component)
    empty-changes
    (let [component-shape (get (:objects component) (:shape-ref shape))]
      ;; ===== Uncomment this to debug =====
      ;; (js/console.log "component-shape" (clj->js component-shape))
      (if (nil? component-shape)
        empty-changes
        (let [;; ===== Uncomment this to debug =====
              ;; _(js/console.info "update" (:name shape) "->" (:name component-shape))
              [rchanges1 uchanges1]
              (update-attrs component-shape
                            shape
                            root-component
                            root-shape
                            nil
                            (:id root-component)
                            {:omit-touched? false
                             :reset-touched? false
                             :set-touched? true})
              [rchanges2 uchanges2]
              (reset-touched shape
                             page-id
                             nil)]
          [(d/concat rchanges1 rchanges2)
           (d/concat uchanges2 uchanges2)])))))


; ---- Operation generation helpers ----

(defn- remove-component-and-ref
  [shape page-id component-id]
  [[(d/without-nils {:type :mod-obj
                     :id (:id shape)
                     :page-id page-id
                     :component-id component-id
                     :operations [{:type :set
                                   :attr :component-root?
                                   :val nil}
                                  {:type :set
                                   :attr :component-id
                                   :val nil}
                                  {:type :set
                                   :attr :component-file
                                   :val nil}
                                  {:type :set
                                   :attr :shape-ref
                                   :val nil}
                                  {:type :set-touched
                                   :touched nil}]})]
   [(d/without-nils {:type :mod-obj
                     :id (:id shape)
                     :page-id page-id
                     :component-id component-id
                     :operations [{:type :set
                                   :attr :component-root?
                                   :val (:component-root? shape)}
                                  {:type :set
                                   :attr :component-id
                                   :val (:component-id shape)}
                                  {:type :set
                                   :attr :component-file
                                   :val (:component-file shape)}
                                  {:type :set
                                   :attr :shape-ref
                                   :val (:shape-ref shape)}
                                  {:type :set-touched
                                   :touched (:touched shape)}]})]])

(defn- -remove-ref
  [shape page-id component-id]
  [[(d/without-nils {:type :mod-obj
                     :id (:id shape)
                     :page-id page-id
                     :component-id component-id
                     :operations [{:type :set
                                   :attr :shape-ref
                                   :val nil}
                                  {:type :set-touched
                                   :touched nil}]})]
   [(d/without-nils {:type :mod-obj
                     :id (:id shape)
                     :page-id page-id
                     :component-id component-id
                     :operations [{:type :set
                                   :attr :shape-ref
                                   :val (:shape-ref shape)}
                                  {:type :set-touched
                                   :touched (:touched shape)}]})]])

(defn- reset-touched
  [shape page-id component-id]
  [[(d/without-nils {:type :mod-obj
                     :id (:id shape)
                     :page-id page-id
                     :component-id component-id
                     :operations [{:type :set-touched
                                   :touched nil}]})]
   [(d/without-nils {:type :mod-obj
                     :id (:id shape)
                     :page-id page-id
                     :component-id component-id
                     :operations [{:type :set-touched
                                   :touched (:touched shape)}]})]])

(defn- update-attrs
  "The main function that implements the sync algorithm. Copy
  attributes that have changed in the origin shape to the dest shape.
  If omit-touched? is true, attributes whose group has been touched
  in the destination shape will be ignored.
  If reset-touched? is true, the 'touched' flags will be cleared in
  the dest shape.
  If set-touched? is true, the corresponding 'touched' flags will be
  set in dest shape if they are different than their current values."
  [dest-shape origin-shape dest-root origin-root page-id component-id
   {:keys [omit-touched? reset-touched? set-touched?] :as options}]

  ;; === Uncomment this to debug synchronization ===
  ;; (println "SYNC"
  ;;          "[C]" (:name origin-shape)
  ;;          "->"
  ;;          (if page-id "[W]" ["C"])
  ;;          (:name dest-shape)
  ;;          (str options))

  (let [; The position attributes need a special sync algorith, because we do
        ; not synchronize the absolute position, but the position relative of
        ; the container shape of the component.
        new-pos   (calc-new-pos dest-shape origin-shape dest-root origin-root)
        touched   (get dest-shape :touched #{})]

    (loop [attrs (seq (keys (dissoc cp/component-sync-attrs :x :y)))
           roperations (if (or (not= (:x new-pos) (:x dest-shape))
                               (not= (:y new-pos) (:y dest-shape)))
                         [{:type :set :attr :x :val (:x new-pos)}
                          {:type :set :attr :y :val (:y new-pos)}]
                         [])
           uoperations (if (or (not= (:x new-pos) (:x dest-shape))
                               (not= (:y new-pos) (:y dest-shape)))
                         [{:type :set :attr :x :val (:x dest-shape)}
                          {:type :set :attr :y :val (:y dest-shape)}]
                         [])]

      (let [attr (first attrs)]
        (if (nil? attr)
          (let [roperations (if reset-touched?
                              (conj roperations
                                    {:type :set-touched
                                     :touched nil})
                              roperations)

                uoperations (if reset-touched?
                              (conj uoperations
                                    {:type :set-touched
                                     :touched (:touched dest-shape)})
                              uoperations)

                rchanges [(d/without-nils {:type :mod-obj
                                           :id (:id dest-shape)
                                           :page-id page-id
                                           :component-id component-id
                                           :operations roperations})]
                uchanges [(d/without-nils {:type :mod-obj
                                           :id (:id dest-shape)
                                           :page-id page-id
                                           :component-id component-id
                                           :operations uoperations})]]
            [rchanges uchanges])

          (if-not (contains? dest-shape attr)
            (recur (next attrs)
                   roperations
                   uoperations)
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
                       (conj uoperations uoperation))))))))))

(defn- calc-new-pos
  [dest-shape origin-shape dest-root origin-root]
  (let [root-pos        (gpt/point (:x dest-root) (:y dest-root))
        origin-root-pos (gpt/point (:x origin-root) (:y origin-root))
        origin-pos      (gpt/point (:x origin-shape) (:y origin-shape))
        delta           (gpt/subtract origin-pos origin-root-pos)
        shape-pos       (gpt/point (:x dest-shape) (:y dest-shape))
        new-pos         (gpt/add root-pos delta)]
    new-pos))


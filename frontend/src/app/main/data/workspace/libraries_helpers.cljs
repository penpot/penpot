;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.libraries-helpers
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.text :as txt]
   [app.main.data.workspace.groups :as dwg]
   [app.util.logging :as log]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]))

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
(declare add-shape-to-instance)
(declare add-shape-to-main)
(declare remove-shape)
(declare move-shape)
(declare change-touched)
(declare change-remote-synced)
(declare update-attrs)
(declare reposition-shape)
(declare make-change)

(defn concat-changes
  [[rchanges1 uchanges1] [rchanges2 uchanges2]]
  [(d/concat rchanges1 rchanges2)
   (d/concat uchanges1 uchanges2)])

(defn get-local-file
  [state]
  (get state :workspace-data))

(defn get-file
  [state file-id]
  (if (= file-id (:current-file-id state))
    (get state :workspace-data)
    (get-in state [:workspace-libraries file-id :data])))

(defn get-libraries
  [state]
  (get state :workspace-libraries))

(defn pretty-file
  [file-id state]
  (if (= file-id (:current-file-id state))
    "<local>"
    (str "<" (get-in state [:workspace-libraries file-id :name]) ">")))


;; ---- Create a new component ----

(defn make-component-shape
  "Clone the shape and all children. Generate new ids and detach
  from parent and frame. Update the original shapes to have links
  to the new ones."
  [shape objects file-id]
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
                                         :component-file file-id
                                         :component-root? true)

                                  (some? (:parent-id new-shape))
                                  (dissoc :component-root?)))]

    (cp/clone-object shape nil objects update-new-shape update-original-shape)))

(defn generate-add-component
  "If there is exactly one id, and it's a group, use it as root. Otherwise,
  create a group that contains all ids. Then, make a component with it,
  and link all shapes to their corresponding one in the component."
  [ids objects page-id file-id]
  (let [shapes (dwg/shapes-for-grouping objects ids)

        [group rchanges uchanges]
        (if (and (= (count shapes) 1)
                 (= (:type (first shapes)) :group))
          [(first shapes) [] []]
          (dwg/prepare-create-group page-id shapes "Component-" true))

        [new-shape new-shapes updated-shapes]
        (make-component-shape group objects file-id)

        rchanges (conj rchanges
                       {:type :add-component
                        :id (:id new-shape)
                        :name (:name new-shape)
                        :shapes new-shapes})

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
                                             :val (:component-file updated-shape)}
                                            {:type :set
                                             :attr :component-root?
                                             :val (:component-root? updated-shape)}
                                            {:type :set
                                             :attr :shape-ref
                                             :val (:shape-ref updated-shape)}
                                            {:type :set
                                             :attr :touched
                                             :val (:touched updated-shape)}]})
                            updated-shapes))

        uchanges (conj uchanges
                       {:type :del-component
                        :id (:id new-shape)})

        uchanges (into uchanges
                       (map (fn [updated-shape]
                              (let [original-shape (get objects (:id updated-shape))]
                                {:type :mod-obj
                                 :page-id page-id
                                 :id (:id updated-shape)
                                 :operations [{:type :set
                                               :attr :component-id
                                               :val (:component-id original-shape)}
                                              {:type :set
                                               :attr :component-file
                                               :val (:component-file original-shape)}
                                              {:type :set
                                               :attr :component-root?
                                               :val (:component-root? original-shape)}
                                              {:type :set
                                               :attr :shape-ref
                                               :val (:shape-ref original-shape)}
                                              {:type :set
                                               :attr :touched
                                               :val (:touched original-shape)}]}))
                            updated-shapes))]
    [group rchanges uchanges]))

(defn duplicate-component
  "Clone the root shape of the component and all children. Generate new
  ids from all of them."
  [component]
  (let [component-root (cp/get-component-root component)]
    (cp/clone-object component-root
                     nil
                     (get component :objects)
                     identity)))


;; ---- General library synchronization functions ----

(defn generate-sync-file
  "Generate changes to synchronize all shapes in all pages of the given file,
  that use assets of the given type in the given library."
  [file-id asset-type library-id state]
  (s/assert #{:colors :components :typographies} asset-type)
  (s/assert ::us/uuid file-id)
  (s/assert ::us/uuid library-id)

  (log/info :msg "Sync file with library"
            :asset-type asset-type
            :file (pretty-file file-id state)
            :library (pretty-file library-id state))

  (let [file          (get-file state file-id)
        library       (get-file state library-id)
        library-items (get library asset-type)]

    (if (empty? library-items)
      empty-changes

      (loop [pages (vals (get file :pages-index))
             rchanges []
             uchanges []]
        (if-let [page (first pages)]
          (let [[page-rchanges page-uchanges]
                (generate-sync-container asset-type
                                         library-id
                                         state
                                         (cp/make-container page :page))]
            (recur (next pages)
                   (d/concat rchanges page-rchanges)
                   (d/concat uchanges page-uchanges)))
          [rchanges uchanges])))))

(defn generate-sync-library
  "Generate changes to synchronize all shapes in all components of the
  local library of the given file, that use assets of the given type in
  the given library."
  [file-id asset-type library-id state]

  (log/info :msg "Sync local components with library"
            :asset-type asset-type
            :file (pretty-file file-id state)
            :library (pretty-file library-id state))

  (let [file          (get-file state file-id)
        library       (get-file state library-id)
        library-items (get library asset-type)]

    (if (empty? library-items)
      empty-changes

      (loop [local-components (vals (get file :components))
             rchanges []
             uchanges []]
        (if-let [local-component (first local-components)]
          (let [[comp-rchanges comp-uchanges]
                (generate-sync-container asset-type
                                         library-id
                                         state
                                         (cp/make-container local-component
                                                            :component))]
            (recur (next local-components)
                   (d/concat rchanges comp-rchanges)
                   (d/concat uchanges comp-uchanges)))
          [rchanges uchanges])))))

(defn- generate-sync-container
  "Generate changes to synchronize all shapes in a particular container (a page
  or a component) that use assets of the given type in the given library."
  [asset-type library-id state container]

  (if (cp/page? container)
    (log/debug :msg "Sync page in local file" :page-id (:id container))
    (log/debug :msg "Sync component in local library" :component-id (:id container)))

  (let [has-asset-reference? (has-asset-reference-fn asset-type library-id (cp/page? container))
        linked-shapes (cp/select-objects has-asset-reference? container)]
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
  [asset-type library-id page?]
  (case asset-type
    :components
    (fn [shape] (and (:component-id shape)
                     (or (:component-root? shape) (not page?))
                     (= (:component-file shape) library-id)))

    :colors
    (fn [shape]
      (if (= (:type shape) :text)
        (->> shape
             :content
             ;; Check if any node in the content has a reference for the library
             (txt/node-seq
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
                (txt/node-seq
                 #(and (some? (:typography-ref-id %))
                       (= library-id (:typography-ref-file %)))))))))

(defmulti generate-sync-shape
  "Generate changes to synchronize one shape with all assets of the given type
  that is using, in the given library."
  (fn [type library-id state container shape] type))

(defmethod generate-sync-shape :components
  [_ library-id state container shape]
  (generate-sync-shape-direct container
                              (:id shape)
                              (get-local-file state)
                              (get-libraries state)
                              false))

(defn- generate-sync-text-shape
  [shape container update-node]
  (let [old-content (:content shape)
        new-content (txt/transform-nodes update-node old-content)
        rchanges [(make-change
                    container
                    {:type :mod-obj
                     :id (:id shape)
                     :operations [{:type :set
                                   :attr :content
                                   :val new-content}]})]
        uchanges [(make-change
                    container
                    {:type :mod-obj
                     :id (:id shape)
                     :operations [{:type :set
                                   :attr :content
                                   :val old-content}]})]]

    (if (= new-content old-content)
      empty-changes
      [rchanges uchanges])))

(defmethod generate-sync-shape :colors
  [_ library-id state container shape]
  (log/debug :msg "Sync colors of shape" :shape (:name shape))

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
        (generate-sync-text-shape shape container update-node))
      (loop [attrs (seq color-sync-attrs)
             roperations []
             uoperations []]
        (let [[attr-ref-id color-attr attr] (first attrs)]
          (if (nil? attr)
            (if (empty? roperations)
              empty-changes
              (let [rchanges [(make-change
                                container
                                {:type :mod-obj
                                 :id (:id shape)
                                 :operations roperations})]
                    uchanges [(make-change
                                container
                                {:type :mod-obj
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
  [_ library-id state container shape]
  (log/debug :msg "Sync typographies of shape" :shape (:name shape))

  ;; Synchronize a shape that uses some typographies of the library. The attributes
  ;; of the typography are copied to the shape."
  (let [typographies (get-assets library-id :typographies state)
        update-node (fn [node]
                      (if-let [typography (get typographies (:typography-ref-id node))]
                        (merge node (d/without-keys typography [:name :id]))
                        node))]
    (generate-sync-text-shape shape container update-node)))

(defn- get-assets
  [library-id asset-type state]
  (if (= library-id (:current-file-id state))
    (get-in state [:workspace-data asset-type])
    (get-in state [:workspace-libraries library-id :data asset-type])))


;; ---- Component synchronization helpers ----

;; Three sources of component synchronization:
;;
;;  - NORMAL SYNC: when a component is updated, any shape that use it,
;;    must be synchronized. All attributes that have changed in the
;;    component and whose attr group has not been "touched" in the dest
;;    shape are copied.
;;
;;      generate-sync-shape-direct (reset = false)
;;
;;  - FORCED SYNC: when the "reset" command is applied to some shape,
;;    all attributes that have changed in the component are copied, and
;;    the "touched" flags are cleared.
;;
;;      generate-sync-shape-direct (reset = true)
;;
;;  - INVERSE SYNC: when the "update component" command is used in some
;;    shape, all the attributes that have changed in the shape are copied
;;    into the linked component. The "touched" flags are also cleared in
;;    the origin shape.
;;
;;      generate-sync-shape-inverse
;;
;;  The initial shape is always a group (a root instance), so all the
;;  children are recursively synced, too. A root instance is a group shape
;;  that has the "component-id" attribute and also "component-root?" is true.
;;
;;  The children lists of the instance and the component shapes are compared
;;  side-by-side. Any new, deleted or moved child modifies (and "touches")
;;  the parent shape.
;;
;;  When a shape inside a component is in turn an instance of another
;;  component, the synchronization is more complex:
;;
;;    [Page]
;;    Instance-2         #--> Component-2          (#--> = root instance)
;;      IShape-2-1        -->   Shape-2-1          (@--> = nested instance)
;;      Subinstance-2-2  @-->   Component-1        ( --> = shape ref)
;;        IShape-2-2-1    -->     Shape-1-1
;;
;;    [Component-1]
;;    Component-1
;;      Shape-1-1
;;
;;    [Component-2]
;;    Component-2
;;      Shape-2-1
;;      Subcomponent-2-2 @--> Component-1
;;        Shape-2-2-1     -->   Shape-1-1
;;
;;   * A SUBINSTANCE ACTUALLY HAS TWO MAINS. For example IShape-2-2-1
;;     depends on Shape-2-2-1 (in the "near" component) but also on
;;     Shape-1-1-1 (in the "remote" component). The "shape-ref" attribute
;;     always refer to the remote shape, and it's guaranteed that it's
;;     always a final shape, not an instance. The relationship between the
;;     shape and the near shape is that both point to the same remote.
;;
;;   * THE INITIAL VALUE of IShape-2-2-1 comes from the near component
;;     Shape-2-2-1 (although the shape-ref attribute points to the direct
;;     component Shape-1-1). The touched flags of IShape-2-2-1 start
;;     cleared at first, and activate on any attribute change onwards.
;;
;;   * IN A NORMAL SYNC, the sync process starts in the root instance and
;;     continues recursively with the children of the root instance and
;;     the component. Therefore, IShape-2-2-1 is synced with Shape-2-2-1.
;;
;;   * IN A FORCED SYNC, IF THE INITIAL SHAPE IS THE ROOT INSTANCE, the
;;     order is the same, and IShape-2-2-1 is reset from Shape-2-2-1 and
;;     marked as not touched.
;;
;;   * IF THE INITIAL SHAPE IS THE SUBINSTANCE, the sync is done against
;;     the remote component. Therefore, IShape-2-2-1 is synched with
;;     Shape-1-1. Then the "touched" flags are reset, and the
;;     "remote-synced?" flag is set (it will be set until the shape is
;;     touched again or it's synced forced normal or inverse with the
;;     near component).
;;
;;   * IN AN INVERSE SYNC, IF THE INITIAL SHAPE IS THE ROOT INSTANCE, the
;;     order is the same as in the normal sync. Therefore, IShape-2-2-1
;;     values are copied into Shape-2-2-1, and then its touched flags are
;;     cleared. Then, the "touched" flags THAT ARE TRUE are copied to
;;     Shape-2-2-1. This may cause that Shape-2-2-1 is now touched respect
;;     to Shape-1-1, and so, some attributes are not copied in a subsequent
;;     normal sync. Or, if "remote-synced?" flag is set in IShape-2-2-1,
;;     all touched flags are cleared in Shape-2-2-1 and "remote-synced?"
;;     is removed.
;;
;;   * IN AN INVERSE SYNC INITIATED IN THE SUBINSTANCE, the update is done
;;     to the remote component. E.g. IShape-2-2-1 attributes are copied into
;;     Shape-1-1, and then touched cleared and "remote-synced?" flag set.
;;
;;     #### WARNING: there are two conditions that are invisible to user:
;;       - When the near shape (Shape-2-2-1) is touched respect the remote
;;         one (Shape-1-1), there is no asterisk displayed anywhere.
;;       - When the instance shape (IShape-2-2-1) is synced with the remote
;;         shape (remote-synced? = true), the user will see that this shape
;;         is different than the one in the near component (Shape-2-2-1)
;;         but it's not touched.

(defn generate-sync-shape-direct
  "Generate changes to synchronize one shape that the root of a component
  instance, and all its children, from the given component."
  [container shape-id local-library libraries reset?]
  (log/debug :msg "Sync shape direct" :shape (str shape-id) :reset? reset?)
  (let [shape-inst    (cp/get-shape container shape-id)
        component     (cp/get-component (:component-id shape-inst)
                                        (:component-file shape-inst)
                                        local-library
                                        libraries)
        shape-main    (cp/get-shape component (:shape-ref shape-inst))

        initial-root? (:component-root? shape-inst)

        root-inst     shape-inst
        root-main     (cp/get-component-root component)]

    (if component
      (generate-sync-shape-direct-recursive container
                                            shape-inst
                                            component
                                            shape-main
                                            root-inst
                                            root-main
                                            reset?
                                            initial-root?)
      empty-changes)))

(defn- generate-sync-shape-direct-recursive
  [container shape-inst component shape-main root-inst root-main reset? initial-root?]
  (log/debug :msg "Sync shape direct recursive"
             :shape (str (:name shape-inst))
             :component (:name component))

  (let [omit-touched?        (not reset?)
        clear-remote-synced? (and initial-root? reset?)
        set-remote-synced?   (and (not initial-root?) reset?)

        [rchanges uchanges]
        (concat-changes
          (update-attrs shape-inst
                        shape-main
                        root-inst
                        root-main
                        container
                        omit-touched?)
          (concat-changes
            (if reset?
              (change-touched shape-inst
                              shape-main
                              container
                              {:reset-touched? true})
              empty-changes)
            (concat-changes
              (if clear-remote-synced?
                (change-remote-synced shape-inst container nil)
                empty-changes)
              (if set-remote-synced?
                (change-remote-synced shape-inst container true)
                empty-changes))))

        children-inst   (mapv #(cp/get-shape container %)
                              (:shapes shape-inst))
        children-main   (mapv #(cp/get-shape component %)
                              (:shapes shape-main))

        only-inst (fn [child-inst]
                    (when-not (and omit-touched?
                                   (contains? (:touched shape-inst)
                                              :shapes-group))
                      (remove-shape child-inst
                                    container
                                    omit-touched?)))

        only-main (fn [child-main]
                    (when-not (and omit-touched?
                                   (contains? (:touched shape-inst)
                                              :shapes-group))
                      (add-shape-to-instance child-main
                                             (d/index-of children-main
                                                         child-main)
                                             component
                                             container
                                             root-inst
                                             root-main
                                             omit-touched?
                                             set-remote-synced?)))

        both (fn [child-inst child-main]
               (let [sub-root? (and (:component-id shape-inst)
                                    (not (:component-root? shape-inst)))]
                 (generate-sync-shape-direct-recursive container
                                                       child-inst
                                                       component
                                                       child-main
                                                       (if sub-root?
                                                         shape-inst
                                                         root-inst)
                                                       (if sub-root?
                                                         shape-main
                                                         root-main)
                                                       reset?
                                                       initial-root?)))

        moved (fn [child-inst child-main]
                (move-shape
                  child-inst
                  (d/index-of children-inst child-inst)
                  (d/index-of children-main child-main)
                  container
                  omit-touched?))

        [child-rchanges child-uchanges]
        (compare-children children-inst
                          children-main
                          only-inst
                          only-main
                          both
                          moved
                          false)]

    [(d/concat rchanges child-rchanges)
     (d/concat uchanges child-uchanges)]))

(defn- generate-sync-shape-inverse
  "Generate changes to update the component a shape is linked to, from
  the values in the shape and all its children."
  [page-id shape-id local-library libraries]
  (log/debug :msg "Sync shape inverse" :shape (str shape-id))
  (let [container     (cp/get-container page-id :page local-library)
        shape-inst    (cp/get-shape container shape-id)
        component     (cp/get-component (:component-id shape-inst)
                                        (:component-file shape-inst)
                                        local-library
                                        libraries)
        shape-main    (cp/get-shape component (:shape-ref shape-inst))

        initial-root? (:component-root? shape-inst)

        root-inst     shape-inst
        root-main     (cp/get-component-root component)]

    (if component
      (generate-sync-shape-inverse-recursive container
                                             shape-inst
                                             component
                                             shape-main
                                             root-inst
                                             root-main
                                             initial-root?)
      empty-changes)))

(defn- generate-sync-shape-inverse-recursive
  [container shape-inst component shape-main root-inst root-main initial-root?]
  (log/trace :msg "Sync shape inverse recursive"
             :shape (str (:name shape-inst))
             :component (:name component))

  (let [component-container  (cp/make-container component :component)

        omit-touched?        false
        set-remote-synced?   (not initial-root?)
        clear-remote-synced? initial-root?

        [rchanges uchanges]
        (concat-changes
          (update-attrs shape-main
                        shape-inst
                        root-main
                        root-inst
                        component-container
                        omit-touched?)
          (concat-changes
            (change-touched shape-inst
                            shape-main
                            container
                            {:reset-touched? true})
            (concat-changes
              (change-touched shape-main
                              shape-inst
                              component-container
                              {:copy-touched? true})
              (concat-changes
                (if clear-remote-synced?
                  (change-remote-synced shape-inst container nil)
                  empty-changes)
                (if set-remote-synced?
                  (change-remote-synced shape-inst container true)
                  empty-changes)))))

        children-inst   (mapv #(cp/get-shape container %)
                              (:shapes shape-inst))
        children-main   (mapv #(cp/get-shape component %)
                              (:shapes shape-main))

        only-inst (fn [child-inst]
                    (add-shape-to-main child-inst
                                       (d/index-of children-inst
                                                   child-inst)
                                       component
                                       container
                                       root-inst
                                       root-main))

        only-main (fn [child-main]
                    (remove-shape child-main
                                  component-container
                                  false))

        both (fn [child-inst child-main]
               (let [sub-root? (and (:component-id shape-inst)
                                    (not (:component-root? shape-inst)))]

                 (generate-sync-shape-inverse-recursive container
                                                        child-inst
                                                        component
                                                        child-main
                                                        (if sub-root?
                                                          shape-inst
                                                          root-inst)
                                                        (if sub-root?
                                                          shape-main
                                                          root-main)
                                                        initial-root?)))

        moved (fn [child-inst child-main]
                (move-shape
                  child-main
                  (d/index-of children-main child-main)
                  (d/index-of children-inst child-inst)
                  component-container
                  false))

        [child-rchanges child-uchanges]
        (compare-children children-inst
                          children-main
                          only-inst
                          only-main
                          both
                          moved
                          true)

        ;; The inverse sync may be made on a component that is inside a
        ;; remote library. We need to separate changes that are from
        ;; local and remote files.
        check-local (fn [change]
                      (cond-> change
                        (= (:id change) (:id shape-inst))
                        (assoc :local-change? true)))

        rchanges (mapv check-local rchanges)
        uchanges (mapv check-local uchanges)]

    [(d/concat rchanges child-rchanges)
     (d/concat uchanges child-uchanges)]))


; ---- Operation generation helpers ----

(defn- compare-children
  [children-inst children-main only-inst-cb only-main-cb both-cb moved-cb inverse?]
  (loop [children-inst (seq (or children-inst []))
         children-main (seq (or children-main []))
         [rchanges uchanges] [[] []]]
    (let [child-inst (first children-inst)
          child-main (first children-main)]
      (cond
        (and (nil? child-inst) (nil? child-main))
        [rchanges uchanges]

        (nil? child-inst)
        (reduce (fn [changes child]
                  (concat-changes changes (only-main-cb child)))
                [rchanges uchanges]
                children-main)

        (nil? child-main)
        (reduce (fn [changes child]
                  (concat-changes changes (only-inst-cb child)))
                [rchanges uchanges]
                children-inst)

        :else
        (if (cp/is-main-of child-main child-inst)
          (recur (next children-inst)
                 (next children-main)
                 (concat-changes [rchanges uchanges]
                                 (both-cb child-inst child-main)))

          (let [child-inst' (d/seek #(cp/is-main-of child-main %)
                                      children-inst)
                child-main' (d/seek #(cp/is-main-of % child-inst)
                                      children-main)]
            (cond
              (nil? child-inst')
              (recur children-inst
                     (next children-main)
                     (concat-changes [rchanges uchanges]
                                     (only-main-cb child-main)))

              (nil? child-main')
              (recur (next children-inst)
                     children-main
                     (concat-changes [rchanges uchanges]
                                     (only-inst-cb child-inst)))

              :else
              (if inverse?
                (recur (next children-inst)
                       (remove #(= (:id %) (:id child-main')) children-main)
                       (-> [rchanges uchanges]
                           (concat-changes (both-cb child-inst' child-main))
                           (concat-changes (moved-cb child-inst child-main'))))
                (recur (remove #(= (:id %) (:id child-inst')) children-inst)
                       (next children-main)
                       (-> [rchanges uchanges]
                           (concat-changes (both-cb child-inst child-main'))
                           (concat-changes (moved-cb child-inst' child-main))))))))))))

(defn- add-shape-to-instance
  [component-shape index component container root-instance root-main omit-touched? set-remote-synced?]
  (log/info :msg (str "ADD [P] " (:name component-shape)))
  (let [component-parent-shape (cp/get-shape component (:parent-id component-shape))
        parent-shape           (d/seek #(cp/is-main-of component-parent-shape %)
                                       (cp/get-object-with-children (:id root-instance)
                                                                    (:objects container)))
        all-parents  (vec (cons (:id parent-shape)
                                (cp/get-parents (:id parent-shape)
                                                (:objects container))))

        update-new-shape (fn [new-shape original-shape]
                           (let [new-shape (reposition-shape new-shape
                                                             root-main
                                                             root-instance)]
                             (cond-> new-shape
                               true
                               (assoc :frame-id (:frame-id parent-shape))

                               (nil? (:shape-ref original-shape))
                               (assoc :shape-ref (:id original-shape))

                               set-remote-synced?
                               (assoc :remote-synced? true))))

        update-original-shape (fn [original-shape new-shape]
                                original-shape)

        [new-shape new-shapes _]
        (cp/clone-object component-shape
                         (:id parent-shape)
                         (get component :objects)
                         update-new-shape
                         update-original-shape)

        rchanges (d/concat
                   (mapv (fn [shape']
                           (make-change
                             container
                             (as-> {:type :add-obj
                                    :id (:id shape')
                                    :parent-id (:parent-id shape')
                                    :index index
                                    :ignore-touched true
                                    :obj shape'} $
                               (cond-> $
                                 (:frame-id shape')
                                 (assoc :frame-id (:frame-id shape'))))))
                         new-shapes)
                   [(make-change
                      container
                      {:type :reg-objects
                       :shapes all-parents})])

        uchanges (d/concat
                   (mapv (fn [shape']
                           (make-change
                             container
                             {:type :del-obj
                              :id (:id shape')
                              :ignore-touched true}))
                         new-shapes))]

    (if (and (cp/touched-group? parent-shape :shapes-group) omit-touched?)
      empty-changes
      [rchanges uchanges])))

(defn- add-shape-to-main
  [shape index component page root-instance root-main]
  (log/info :msg (str "ADD [C] " (:name shape)))
  (let [parent-shape           (cp/get-shape page (:parent-id shape))
        component-parent-shape (d/seek #(cp/is-main-of % parent-shape)
                                       (cp/get-object-with-children (:id root-main)
                                                                    (:objects component)))
        all-parents  (vec (cons (:id component-parent-shape)
                                (cp/get-parents (:id component-parent-shape)
                                                (:objects component))))

        update-new-shape (fn [new-shape original-shape]
                           (reposition-shape new-shape
                                             root-instance
                                             root-main))

        update-original-shape (fn [original-shape new-shape]
                                (if-not (:shape-ref original-shape)
                                  (assoc original-shape
                                         :shape-ref (:id new-shape))
                                  original-shape))

        [new-shape new-shapes updated-shapes]
        (cp/clone-object shape
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
                            :index index
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
                      (if (cp/page? container) "[P] " "[C] ")
                      (:name shape)))
  (let [objects    (get container :objects)
        parents    (cp/get-parents (:id shape) objects)
        parent     (first parents)
        children   (cp/get-children (:id shape) objects)

        rchanges [(make-change
                    container
                    {:type :del-obj
                     :id (:id shape)
                     :ignore-touched true})
                  (make-change
                    container
                    {:type :reg-objects
                     :shapes (vec parents)})]

        add-change (fn [id]
                     (let [shape' (get objects id)]
                       (make-change
                         container
                         (as-> {:type :add-obj
                                :id id
                                :index (cp/position-on-parent id objects)
                                :parent-id (:parent-id shape')
                                :ignore-touched true
                                :obj shape'} $
                           (cond-> $
                             (:frame-id shape')
                             (assoc :frame-id (:frame-id shape')))))))

        uchanges (d/concat
                   [(add-change (:id shape))]
                   (map add-change children)
                   [(make-change
                      container
                      {:type :reg-objects
                       :shapes (vec parents)})])]

    (if (and (cp/touched-group? parent :shapes-group) omit-touched?)
      empty-changes
      [rchanges uchanges])))

(defn- move-shape
  [shape index-before index-after container omit-touched?]
  (log/info :msg (str "MOVE "
                      (if (cp/page? container) "[P] " "[C] ")
                      (:name shape)
                      " "
                      index-before
                      " -> "
                      index-after))
  (let [parent (cp/get-shape container (:parent-id shape))

        rchanges [(make-change
                    container
                    {:type :mov-objects
                     :parent-id (:parent-id shape)
                     :shapes [(:id shape)]
                     :index index-after
                     :ignore-touched true})]
        uchanges [(make-change
                    container
                    {:type :mov-objects
                     :parent-id (:parent-id shape)
                     :shapes [(:id shape)]
                     :index index-before
                     :ignore-touched true})]]

    (if (and (cp/touched-group? parent :shapes-group) omit-touched?)
      empty-changes
      [rchanges uchanges])))

(defn- change-touched
  [dest-shape origin-shape container
   {:keys [reset-touched? copy-touched?] :as options}]
  (if (or (nil? (:shape-ref dest-shape))
          (not (or reset-touched? copy-touched?)))
    empty-changes
    (do
      (log/info :msg (str "CHANGE-TOUCHED "
                          (if (cp/page? container) "[P] " "[C] ")
                          (:name dest-shape))
                :options options)
      (let [new-touched (cond
                          reset-touched?
                          nil
                          copy-touched?
                          (if (:remote-synced? origin-shape)
                            nil
                            (set/union
                              (:touched dest-shape)
                              (:touched origin-shape))))

            rchanges [(make-change
                        container
                        {:type :mod-obj
                         :id (:id dest-shape)
                         :operations
                         [{:type :set-touched
                           :touched new-touched}]})]

            uchanges [(make-change
                        container
                        {:type :mod-obj
                         :id (:id dest-shape)
                         :operations
                         [{:type :set-touched
                           :touched (:touched dest-shape)}]})]]
        [rchanges uchanges]))))

(defn- change-remote-synced
  [shape container remote-synced?]
  (if (nil? (:shape-ref shape))
    empty-changes
    (do
      (log/info :msg (str "CHANGE-REMOTE-SYNCED? "
                          (if (cp/page? container) "[P] " "[C] ")
                          (:name shape))
                :remote-synced? remote-synced?)
      (let [rchanges [(make-change
                        container
                        {:type :mod-obj
                         :id (:id shape)
                         :operations
                         [{:type :set-remote-synced
                           :remote-synced? remote-synced?}]})]

            uchanges [(make-change
                        container
                        {:type :mod-obj
                         :id (:id shape)
                         :operations
                         [{:type :set-remote-synced
                           :remote-synced? (:remote-synced? shape)}]})]]
        [rchanges uchanges]))))

(defn- set-touched-shapes-group
  [shape container]
  (if-not (:shape-ref shape)
    empty-changes
    (do
      (log/info :msg (str "SET-TOUCHED-SHAPES-GROUP "
                          (if (cp/page? container) "[P] " "[C] ")
                          (:name shape)))
      (let [rchanges [(make-change
                        container
                        {:type :mod-obj
                         :id (:id shape)
                         :operations
                         [{:type :set-touched
                           :touched (cp/set-touched-group
                                      (:touched shape)
                                      :shapes-group)}]})]

            uchanges [(make-change
                        container
                        {:type :mod-obj
                         :id (:id shape)
                         :operations
                         [{:type :set-touched
                           :touched (:touched shape)}]})]]
        [rchanges uchanges]))))

(defn- update-attrs
  "The main function that implements the attribute sync algorithm. Copy
  attributes that have changed in the origin shape to the dest shape.

  If omit-touched? is true, attributes whose group has been touched
  in the destination shape will not be copied."
  [dest-shape origin-shape dest-root origin-root container omit-touched?]

  (log/info :msg (str "SYNC "
                      (:name origin-shape)
                      " -> "
                      (if (cp/page? container) "[P] " "[C] ")
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
          (let [all-parents (vec (or (cp/get-parents (:id dest-shape)
                                                     (:objects container)) []))
                rchanges [(make-change
                            container
                            {:type :mod-obj
                             :id (:id dest-shape)
                             :operations roperations})
                          (make-change
                            container
                            {:type :reg-objects
                             :shapes all-parents})]
                uchanges [(make-change
                            container
                            {:type :mod-obj
                             :id (:id dest-shape)
                             :operations uoperations})
                          (make-change
                            container
                            {:type :reg-objects
                             :shapes all-parents})]]
            (if (seq roperations)
              [rchanges uchanges]
              empty-changes))

          (let [roperation {:type :set
                            :attr attr
                            :val (get origin-shape attr)
                            :ignore-touched true}
                uoperation {:type :set
                            :attr attr
                            :val (get dest-shape attr)
                            :ignore-touched true}

                attr-group (get cp/component-sync-attrs attr)

                root-name? (and (= attr-group :name-group)
                                (:component-root? dest-shape))]

            (if (or (= (get origin-shape attr) (get dest-shape attr))
                    (and (touched attr-group) omit-touched?)
                    root-name?)
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

(defn- make-change
  [container change]
  (if (cp/page? container)
    (assoc change :page-id (:id container))
    (assoc change :component-id (:id container))))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.libraries-helpers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logging :as log]
   [app.common.pages :as cp]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.text :as txt]
   [app.common.types.color :as ctc]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.typography :as cty]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.state-helpers :as wsh]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]))

;; Change this to :info :debug or :trace to debug this module, or :warn to reset to default
(log/set-level! :warn)

(declare generate-sync-container)
(declare generate-sync-shape)
(declare generate-sync-text-shape)
(declare uses-assets?)

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

(defn pretty-file
  [file-id state]
  (if (= file-id (:current-file-id state))
    "<local>"
    (str "<" (get-in state [:workspace-libraries file-id :name]) ">")))

;; ---- Components and instances creation ----

(defn generate-add-component
  "If there is exactly one id, and it's a group or a frame, and not already a component,
  use it as root. Otherwise, create a group that contains all ids. Then, make a
  component with it, and link all shapes to their corresponding one in the component."
  [it shapes objects page-id file-id components-v2]
  (let [[group changes]
        (if (and (= (count shapes) 1)
                 (or (= (:type (first shapes)) :group)
                     (= (:type (first shapes)) :frame))
                 (not (ctk/instance-root? (first shapes))))
          [(first shapes) (-> (pcb/empty-changes it page-id)
                              (pcb/with-objects objects))]
          (let [group-name (if (= 1 (count shapes))
                             (:name (first shapes))
                             "Component 1")]
            (dwg/prepare-create-group it
                                      objects
                                      page-id
                                      shapes
                                      group-name
                                      (not (ctk/instance-root? (first shapes))))))

        name (:name group)
        [path name] (cph/parse-path-name name)

        [root-shape new-shapes updated-shapes]
        (if-not components-v2
          (ctn/make-component-shape group objects file-id components-v2)
          (let [new-id (uuid/next)]
            [(assoc group :id new-id)
             nil
             [(assoc group
                     :component-id new-id
                     :component-file file-id
                     :component-root? true
                     :main-instance? true)]]))

        changes (-> changes
                    (pcb/add-component (:id root-shape)
                                       path
                                       name
                                       new-shapes
                                       updated-shapes
                                       (:id group)
                                        page-id))]
    [group (:id root-shape) changes]))

(defn duplicate-component
  "Clone the root shape of the component and all children. Generate new
  ids from all of them."
  [component library-data]
  (let [components-v2 (dm/get-in library-data [:options :components-v2])]
    (if components-v2

      (let [main-instance-page  (ctf/get-component-page library-data component)
            main-instance-shape (ctf/get-component-root library-data component)

            position (gpt/add (gpt/point (:x main-instance-shape) (:y main-instance-shape))
                              (gpt/point (+ (:width main-instance-shape) 50) 0))

            component-instance-extra-data (if components-v2 {:main-instance? true} {})

            [new-instance-shape new-instance-shapes]
            (when (and (some? main-instance-page) (some? main-instance-shape))
              (ctn/make-component-instance main-instance-page
                                           component
                                           library-data
                                           position
                                           true
                                           component-instance-extra-data))]

        [nil nil new-instance-shape new-instance-shapes])

      (let [component-root (d/seek #(nil? (:parent-id %)) (vals (:objects component)))

            [new-component-shape new-component-shapes _]
            (ctst/clone-object component-root
                               nil
                               (get component :objects)
                               identity)]

        [new-component-shape new-component-shapes nil nil]))))

(defn generate-instantiate-component
  "Generate changes to create a new instance from a component."
  [it file-id component-id position page libraries]
  (let [component     (ctf/get-component libraries file-id component-id)
        library       (get libraries file-id)

        components-v2 (dm/get-in library [:data :options :components-v2])

        [new-shape new-shapes]
        (ctn/make-component-instance page
                                     component
                                     (:data library)
                                     position
                                     components-v2)

        changes (reduce #(pcb/add-object %1 %2 {:ignore-touched true})
                        (pcb/empty-changes it (:id page))
                        new-shapes)]

    [new-shape changes]))

(defn generate-detach-instance
  "Generate changes to remove the links between a shape and all its children
  with a component."
  [changes container shape-id]
  (log/debug :msg "Detach instance" :shape-id shape-id :container (:id container))
  (let [shapes  (->> (cph/get-children-with-self (:objects container) shape-id)
                     (map :id))

        update-fn
        (fn [shape]
          (assoc shape
                 :component-id nil
                 :component-file nil
                 :component-root? nil
                 :remote-synced? nil
                 :shape-ref nil
                 :touched nil))]

    (pcb/update-shapes changes shapes update-fn)))

;; ---- General library synchronization functions ----

(defn generate-sync-file
  "Generate changes to synchronize all shapes in all pages of the given file,
  that use assets of the given type in the given library.

  If an asset id is given, only shapes linked to this particular asset will
  be synchronized."
  [it file-id asset-type asset-id library-id state]
  (s/assert #{:colors :components :typographies} asset-type)
  (s/assert (s/nilable ::us/uuid) asset-id)
  (s/assert ::us/uuid file-id)
  (s/assert ::us/uuid library-id)

  (log/info :msg "Sync file with library"
            :asset-type asset-type
            :asset-id asset-id
            :file (pretty-file file-id state)
            :library (pretty-file library-id state))

  (let [file          (wsh/get-file state file-id)
        components-v2 (get-in file [:options :components-v2])]
    (loop [pages (vals (get file :pages-index))
           changes (pcb/empty-changes it)]
      (if-let [page (first pages)]
        (recur (next pages)
               (pcb/concat-changes
                 changes
                 (generate-sync-container it
                                          asset-type
                                          asset-id
                                          library-id
                                          state
                                          (cph/make-container page :page)
                                          components-v2)))
        changes))))

(defn generate-sync-library
  "Generate changes to synchronize all shapes in all components of the
  local library of the given file, that use assets of the given type in
  the given library.

  If an asset id is given, only shapes linked to this particular asset will
  be synchronized."
  [it file-id asset-type asset-id library-id state]
  (s/assert #{:colors :components :typographies} asset-type)
  (s/assert (s/nilable ::us/uuid) asset-id)
  (s/assert ::us/uuid file-id)
  (s/assert ::us/uuid library-id)

  (log/info :msg "Sync local components with library"
            :asset-type asset-type
            :asset-id asset-id
            :file (pretty-file file-id state)
            :library (pretty-file library-id state))

  (let [file          (wsh/get-file state file-id)
        components-v2 (get-in file [:options :components-v2])]
    (loop [local-components (ctkl/components-seq file)
           changes (pcb/empty-changes it)]
      (if-let [local-component (first local-components)]
        (recur (next local-components)
               (pcb/concat-changes
                 changes
                 (generate-sync-container it
                                          asset-type
                                          asset-id
                                          library-id
                                          state
                                          (cph/make-container local-component :component)
                                          components-v2)))
        changes))))

(defn- generate-sync-container
  "Generate changes to synchronize all shapes in a particular container (a page
  or a component) that use assets of the given type in the given library."
  [it asset-type asset-id library-id state container components-v2]

  (if (cph/page? container)
    (log/debug :msg "Sync page in local file" :page-id (:id container))
    (log/debug :msg "Sync component in local library" :component-id (:id container)))

  (let [linked-shapes (->> (vals (:objects container))
                           (filter #(uses-assets? asset-type asset-id % library-id)))]
    (loop [shapes (seq linked-shapes)
           changes (-> (pcb/empty-changes it)
                       (pcb/with-container container)
                       (pcb/with-objects (:objects container)))]
      (if-let [shape (first shapes)]
        (recur (next shapes)
               (generate-sync-shape asset-type
                                    changes
                                    library-id
                                    state
                                    container
                                    shape
                                    components-v2))
        changes))))

(defmulti uses-assets?
  "Checks if a shape uses some asset of the given type in the given library."
  (fn [asset-type _ _ _] asset-type))

(defmethod uses-assets? :components
  [_ component-id shape library-id]
  (if (nil? component-id)
    (ctk/uses-library-components? shape library-id)
    (ctk/instance-of? shape library-id component-id)))

(defmethod uses-assets? :colors
  [_ color-id shape library-id]
  (if (nil? color-id)
    (ctc/uses-library-colors? shape library-id)
    (ctc/uses-library-color? shape library-id color-id)))

(defmethod uses-assets? :typographies
  [_ typography-id shape library-id]
  (if (nil? typography-id)
    (cty/uses-library-typographies? shape library-id)
    (cty/uses-library-typography? shape library-id typography-id)))

(defmulti generate-sync-shape
  "Generate changes to synchronize one shape from all assets of the given type
  that is using, in the given library."
  (fn [asset-type _changes _library-id _state _container _shape _components-v2] asset-type))

(defmethod generate-sync-shape :components
  [_ changes _library-id state container shape components-v2]
  (let [shape-id  (:id shape)
        libraries (wsh/get-libraries state)]
    (generate-sync-shape-direct changes libraries container shape-id false components-v2)))

(defmethod generate-sync-shape :colors
  [_ changes library-id state _ shape _]
  (log/debug :msg "Sync colors of shape" :shape (:name shape))

  ;; Synchronize a shape that uses some colors of the library. The value of the
  ;; color in the library is copied to the shape.
  (let [library-colors (get-assets library-id :colors state)]
    (pcb/update-shapes changes
                       [(:id shape)]
                       #(ctc/sync-shape-colors % library-id library-colors))))

(defmethod generate-sync-shape :typographies
  [_ changes library-id state container shape _]
  (log/debug :msg "Sync typographies of shape" :shape (:name shape))

  ;; Synchronize a shape that uses some typographies of the library. The attributes
  ;; of the typography are copied to the shape."
  (let [typographies (get-assets library-id :typographies state)
        update-node (fn [node]
                      (if-let [typography (get typographies (:typography-ref-id node))]
                        (merge node (dissoc typography :name :id))
                        (dissoc node :typography-ref-id
                                     :typography-ref-file)))]
    (generate-sync-text-shape changes shape container update-node)))

(defn- get-assets
  [library-id asset-type state]
  (if (= library-id (:current-file-id state))
    (get-in state [:workspace-data asset-type])
    (get-in state [:workspace-libraries library-id :data asset-type])))

(defn- generate-sync-text-shape
  [changes shape container update-node]
  (let [old-content (:content shape)
        new-content (txt/transform-nodes update-node old-content)
        changes' (-> changes
                     (update :redo-changes conj (make-change
                                                  container
                                                  {:type :mod-obj
                                                   :id (:id shape)
                                                   :operations [{:type :set
                                                                 :attr :content
                                                                 :val new-content}]}))
                     (update :undo-changes d/preconj (make-change
                                                       container
                                                       {:type :mod-obj
                                                        :id (:id shape)
                                                        :operations [{:type :set
                                                                      :attr :content
                                                                      :val old-content}]})))]
    (if (= new-content old-content)
      changes
      changes')))


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
  "Generate changes to synchronize one shape that is the root of a component
  instance, and all its children, from the given component."
  [changes libraries container shape-id reset? components-v2]
  (log/debug :msg "Sync shape direct" :shape (str shape-id) :reset? reset?)
  (let [shape-inst     (ctn/get-shape container shape-id)]
    (if (ctk/in-component-instance? shape-inst)
      (let [library        (dm/get-in libraries [(:component-file shape-inst) :data])
            component      (or (ctkl/get-component library (:component-id shape-inst))
                               (and reset?
                                    (ctkl/get-deleted-component library (:component-id shape-inst))))

            shape-main     (when component
                             (ctf/get-ref-shape library component shape-inst))

            initial-root?  (:component-root? shape-inst)

            root-inst      shape-inst
            root-main      (when component
                             (ctf/get-component-root library component))]

        (if component
          (generate-sync-shape-direct-recursive changes
                                                container
                                                shape-inst
                                                component
                                                library
                                                shape-main
                                                root-inst
                                                root-main
                                                reset?
                                                initial-root?
                                                components-v2)
      ; If the component is not found, because the master component has been
      ; deleted or the library unlinked, do nothing in v2 or detach in v1.
          (if components-v2
            changes
            (generate-detach-instance changes container shape-id))))
      changes)))

(defn- generate-sync-shape-direct-recursive
  [changes container shape-inst component library shape-main root-inst root-main reset? initial-root? components-v2]
  (log/debug :msg "Sync shape direct recursive"
             :shape (str (:name shape-inst))
             :component (:name component))

  (if (nil? shape-main)
    ;; This should not occur, but protect against it in any case
    (if components-v2
      changes
      (generate-detach-instance changes container (:id shape-inst)))
    (let [omit-touched?        (not reset?)
          clear-remote-synced? (and initial-root? reset?)
          set-remote-synced?   (and (not initial-root?) reset?)

          changes (cond-> changes
                    :always
                    (update-attrs shape-inst
                                  shape-main
                                  root-inst
                                  root-main
                                  container
                                  omit-touched?)

                    reset?
                    (change-touched shape-inst
                                    shape-main
                                    container
                                    {:reset-touched? true})

                    clear-remote-synced?
                    (change-remote-synced shape-inst container nil)

                    set-remote-synced?
                    (change-remote-synced shape-inst container true))

          component-container (ctf/get-component-container library component)

          children-inst       (mapv #(ctn/get-shape container %)
                                    (:shapes shape-inst))
          children-main       (mapv #(ctn/get-shape component-container %)
                                    (:shapes shape-main))

          only-inst (fn [changes child-inst]
                      (if-not (and omit-touched?
                                        (contains? (:touched shape-inst)
                                                   :shapes-group))
                             (remove-shape changes
                                           child-inst
                                           container
                                           omit-touched?)
                             changes))

          only-main (fn [changes child-main]
                      (if-not (and omit-touched?
                                   (contains? (:touched shape-inst)
                                              :shapes-group))
                        (add-shape-to-instance changes
                                               child-main
                                               (d/index-of children-main
                                                           child-main)
                                               component-container
                                               container
                                               root-inst
                                               root-main
                                               omit-touched?
                                               set-remote-synced?)
                        changes))

          both (fn [changes child-inst child-main]
                 (generate-sync-shape-direct-recursive changes
                                                       container
                                                       child-inst
                                                       component
                                                       library
                                                       child-main
                                                       root-inst
                                                       root-main
                                                       reset?
                                                       initial-root?
                                                       components-v2))

          moved (fn [changes child-inst child-main]
                  (move-shape
                   changes
                   child-inst
                   (d/index-of children-inst child-inst)
                   (d/index-of children-main child-main)
                   container
                   omit-touched?))]

      (compare-children changes
                        children-inst
                        children-main
                        only-inst
                        only-main
                        both
                        moved
                        false))))

(defn generate-sync-shape-inverse
  "Generate changes to update the component a shape is linked to, from
  the values in the shape and all its children."
  [changes libraries container shape-id]
  (log/debug :msg "Sync shape inverse" :shape (str shape-id))
  (let [shape-inst     (ctn/get-shape container shape-id)
        library        (dm/get-in libraries [(:component-file shape-inst) :data])
        component      (ctkl/get-component library (:component-id shape-inst))
        shape-main     (ctf/get-ref-shape library component shape-inst)

        initial-root?  (:component-root? shape-inst)

        root-inst      shape-inst
        root-main      (ctf/get-component-root library component)]

    (if component
      (generate-sync-shape-inverse-recursive changes
                                             container
                                             shape-inst
                                             component
                                             library
                                             shape-main
                                             root-inst
                                             root-main
                                             initial-root?)
      changes)))

(defn- generate-sync-shape-inverse-recursive
  [changes container shape-inst component library shape-main root-inst root-main initial-root?]
  (log/trace :msg "Sync shape inverse recursive"
             :shape (str (:name shape-inst))
             :component (:name component))

  (if (nil? shape-main)
    ;; This should not occur, but protect against it in any case
    changes
    (let [component-container  (ctf/get-component-container library component)

          omit-touched?        false
          set-remote-synced?   (not initial-root?)
          clear-remote-synced? initial-root?

          changes (cond-> changes
                    :always
                    (-> (update-attrs shape-main
                                      shape-inst
                                      root-main
                                      root-inst
                                      component-container
                                      omit-touched?)
                        (change-touched shape-inst
                                        shape-main
                                        container
                                        {:reset-touched? true})
                        (change-touched shape-main
                                        shape-inst
                                        component-container
                                        {:copy-touched? true}))

                    clear-remote-synced?
                    (change-remote-synced shape-inst container nil)

                    set-remote-synced?
                    (change-remote-synced shape-inst container true))

          children-inst   (mapv #(ctn/get-shape container %)
                                (:shapes shape-inst))
          children-main   (mapv #(ctn/get-shape component-container %)
                                (:shapes shape-main))

          only-inst (fn [changes child-inst]
                      (add-shape-to-main changes
                                         child-inst
                                         (d/index-of children-inst
                                                     child-inst)
                                         component
                                         component-container
                                         container
                                         root-inst
                                         root-main))

          only-main (fn [changes child-main]
                      (remove-shape changes
                                    child-main
                                    component-container
                                    false))

          both (fn [changes child-inst child-main]
                 (generate-sync-shape-inverse-recursive changes
                                                        container
                                                        child-inst
                                                        component
                                                        library
                                                        child-main
                                                        root-inst
                                                        root-main
                                                        initial-root?))

          moved (fn [changes child-inst child-main]
                  (move-shape
                   changes
                   child-main
                   (d/index-of children-main child-main)
                   (d/index-of children-inst child-inst)
                   component-container
                   false))

          changes
          (compare-children changes
                            children-inst
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
                          (assoc :local-change? true)))]

      (-> changes
          (update :redo-changes (partial mapv check-local))
          (update :undo-changes (partial mapv check-local))))))


; ---- Operation generation helpers ----

(defn- compare-children
  [changes children-inst children-main only-inst-cb only-main-cb both-cb moved-cb inverse?]
  (loop [children-inst (seq (or children-inst []))
         children-main (seq (or children-main []))
         changes       changes]
    (let [child-inst (first children-inst)
          child-main (first children-main)]
      (cond
        (and (nil? child-inst) (nil? child-main))
        changes

        (nil? child-inst)
        (reduce only-main-cb changes children-main)

        (nil? child-main)
        (reduce only-inst-cb changes children-inst)

        :else
        (if (ctk/is-main-of? child-main child-inst)
          (recur (next children-inst)
                 (next children-main)
                 (both-cb changes child-inst child-main))

          (let [child-inst' (d/seek #(ctk/is-main-of? child-main %) children-inst)
                child-main' (d/seek #(ctk/is-main-of? % child-inst) children-main)]
            (cond
              (nil? child-inst')
              (recur children-inst
                     (next children-main)
                     (only-main-cb changes child-main))

              (nil? child-main')
              (recur (next children-inst)
                     children-main
                     (only-inst-cb changes child-inst))

              :else
              (if inverse?
                (recur (next children-inst)
                       (remove #(= (:id %) (:id child-main')) children-main)
                       (-> changes
                           (both-cb child-inst' child-main)
                           (moved-cb child-inst child-main')))
                (recur (remove #(= (:id %) (:id child-inst')) children-inst)
                       (next children-main)
                       (-> changes
                           (both-cb child-inst child-main')
                           (moved-cb child-inst' child-main)))))))))))

(defn- add-shape-to-instance
  [changes component-shape index component-page container root-instance root-main omit-touched? set-remote-synced?]
  (log/info :msg (str "ADD [P] " (:name component-shape)))
  (let [component-parent-shape (ctn/get-shape component-page (:parent-id component-shape))
        parent-shape           (d/seek #(ctk/is-main-of? component-parent-shape %)
                                       (cph/get-children-with-self (:objects container)
                                                                   (:id root-instance)))
        all-parents            (into [(:id parent-shape)]
                                     (cph/get-parent-ids (:objects container)
                                                         (:id parent-shape)))

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

        update-original-shape (fn [original-shape _new-shape]
                                original-shape)

        [_ new-shapes _]
        (ctst/clone-object component-shape
                          (:id parent-shape)
                          (get component-page :objects)
                          update-new-shape
                          update-original-shape)

        add-obj-change (fn [changes shape']
                         (update changes :redo-changes conj
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
                                       (assoc :frame-id (:frame-id shape')))))))

        del-obj-change (fn [changes shape']
                         (update changes :undo-changes d/preconj
                                 (make-change
                                   container
                                   {:type :del-obj
                                    :id (:id shape')
                                    :ignore-touched true})))

        changes' (reduce add-obj-change changes new-shapes)
        changes' (update changes' :redo-changes conj (make-change
                                                       container
                                                       {:type :reg-objects
                                                        :shapes all-parents}))
        changes' (reduce del-obj-change changes' new-shapes)]

    (if (and (cph/touched-group? parent-shape :shapes-group) omit-touched?)
      changes
      changes')))

(defn- add-shape-to-main
  [changes shape index component component-container page root-instance root-main]
  (log/info :msg (str "ADD [C] " (:name shape)))
  (let [parent-shape           (ctn/get-shape page (:parent-id shape))
        component-parent-shape (d/seek #(ctk/is-main-of? % parent-shape)
                                       (cph/get-children-with-self (:objects component-container)
                                                                   (:id root-main)))
        all-parents  (into [(:id component-parent-shape)]
                           (cph/get-parent-ids (:objects component-container)
                                               (:id component-parent-shape)))

        update-new-shape (fn [new-shape _original-shape]
                           (reposition-shape new-shape
                                             root-instance
                                             root-main))

        update-original-shape (fn [original-shape new-shape]
                                (if-not (:shape-ref original-shape)
                                  (assoc original-shape
                                         :shape-ref (:id new-shape))
                                  original-shape))

        [_new-shape new-shapes updated-shapes]
        (ctst/clone-object shape
                           (:id component-parent-shape)
                           (get page :objects)
                           update-new-shape
                           update-original-shape)

        add-obj-change (fn [changes shape']
                         (update changes :redo-changes conj
                                 (cond-> (make-change
                                          component-container
                                          {:type :add-obj
                                           :id (:id shape')
                                           :parent-id (:parent-id shape')
                                           :index index
                                           :ignore-touched true
                                           :obj shape'})

                                         (ctn/page? component-container)
                                         (assoc :frame-id (:frame-id shape')))))

        mod-obj-change (fn [changes shape']
                         (update changes :redo-changes conj
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
                                                :val (:touched shape')}]}))

        del-obj-change (fn [changes shape']
                         (update changes :undo-changes d/preconj
                                 {:type :del-obj
                                  :id (:id shape')
                                  :page-id (:id page)
                                  :ignore-touched true}))

        changes' (reduce add-obj-change changes new-shapes)
        changes' (update changes' :redo-changes conj {:type :reg-objects
                                                      :component-id (:id component)
                                                      :shapes all-parents})
        changes' (reduce mod-obj-change changes' updated-shapes)
        changes' (reduce del-obj-change changes' new-shapes)]

    changes'))

(defn- remove-shape
  [changes shape container omit-touched?]
  (log/info :msg (str "REMOVE-SHAPE "
                      (if (cph/page? container) "[P] " "[C] ")
                      (:name shape)))
  (let [objects    (get container :objects)
        parents    (cph/get-parent-ids objects (:id shape))
        parent     (first parents)
        children   (cph/get-children-ids objects (:id shape))
        ids        (into [(:id shape)] children)

        add-redo-change (fn [changes id]
                          (update changes :redo-changes conj
                                  (make-change
                                   container
                                   {:type :del-obj
                                    :id id
                                    :ignore-touched true})))

        add-undo-change (fn [changes id]
                          (let [shape' (get objects id)]
                            (update changes :undo-changes d/preconj
                                    (make-change
                                     container
                                     (as-> {:type :add-obj
                                            :id id
                                            :index (cph/get-position-on-parent objects id)
                                            :parent-id (:parent-id shape')
                                            :ignore-touched true
                                            :obj shape'} $
                                       (cond-> $
                                         (:frame-id shape')
                                         (assoc :frame-id (:frame-id shape'))))))))

        changes' (-> (reduce add-redo-change changes ids)
                     (update :redo-changes conj (make-change
                                                 container
                                                 {:type :reg-objects
                                                  :shapes (vec parents)}))
                     (add-undo-change (:id shape)))

        changes' (reduce add-undo-change
                         changes'
                         children)]

    (if (and (cph/touched-group? parent :shapes-group) omit-touched?)
      changes
      changes')))

(defn- move-shape
  [changes shape index-before index-after container omit-touched?]
  (log/info :msg (str "MOVE "
                      (if (cph/page? container) "[P] " "[C] ")
                      (:name shape)
                      " "
                      index-before
                      " -> "
                      index-after))
  (let [parent (ctn/get-shape container (:parent-id shape))

        changes' (-> changes
                     (update :redo-changes conj (make-change
                                                  container
                                                  {:type :mov-objects
                                                   :parent-id (:parent-id shape)
                                                   :shapes [(:id shape)]
                                                   :index index-after
                                                   :ignore-touched true}))
                     (update :undo-changes d/preconj (make-change
                                                       container
                                                       {:type :mov-objects
                                                        :parent-id (:parent-id shape)
                                                        :shapes [(:id shape)]
                                                        :index index-before
                                                        :ignore-touched true})))]

    (if (and (cph/touched-group? parent :shapes-group) omit-touched?)
      changes
      changes')))

(defn- change-touched
  [changes dest-shape origin-shape container
   {:keys [reset-touched? copy-touched?] :as options}]
  (if (or (nil? (:shape-ref dest-shape))
          (not (or reset-touched? copy-touched?)))
    changes
    (do
      (log/info :msg (str "CHANGE-TOUCHED "
                          (if (cph/page? container) "[P] " "[C] ")
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
                              (:touched origin-shape))))]

        (-> changes
            (update :redo-changes conj (make-change
                                         container
                                         {:type :mod-obj
                                          :id (:id dest-shape)
                                          :operations
                                          [{:type :set-touched
                                            :touched new-touched}]}))
            (update :undo-changes d/preconj (make-change
                                              container
                                              {:type :mod-obj
                                               :id (:id dest-shape)
                                               :operations
                                               [{:type :set-touched
                                                 :touched (:touched dest-shape)}]})))))))

(defn- change-remote-synced
  [changes shape container remote-synced?]
  (if (nil? (:shape-ref shape))
    changes
    (do
      (log/info :msg (str "CHANGE-REMOTE-SYNCED? "
                          (if (cph/page? container) "[P] " "[C] ")
                          (:name shape))
                :remote-synced? remote-synced?)
      (-> changes
          (update :redo-changes conj (make-change
                                       container
                                       {:type :mod-obj
                                        :id (:id shape)
                                        :operations
                                        [{:type :set-remote-synced
                                          :remote-synced? remote-synced?}]}))
          (update :undo-changes d/preconj (make-change
                                            container
                                            {:type :mod-obj
                                             :id (:id shape)
                                             :operations
                                             [{:type :set-remote-synced
                                               :remote-synced? (:remote-synced? shape)}]}))))))

(defn- update-attrs
  "The main function that implements the attribute sync algorithm. Copy
  attributes that have changed in the origin shape to the dest shape.

  If omit-touched? is true, attributes whose group has been touched
  in the destination shape will not be copied."
  [changes dest-shape origin-shape dest-root origin-root container omit-touched?]

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
        ; In case of subinstances, the comparison is always done with the
        ; near component, because this is that we are syncing with.
        origin-shape (reposition-shape origin-shape origin-root dest-root)
        touched      (get dest-shape :touched #{})]

    (loop [attrs (seq (keys cp/component-sync-attrs))
           roperations []
           uoperations []]

      (let [attr (first attrs)]
        (if (nil? attr)
          (if (empty? roperations)
            changes
            (let [all-parents (cph/get-parent-ids (:objects container)
                                                  (:id dest-shape))]
              (-> changes
                  (update :redo-changes conj (make-change
                                               container
                                               {:type :mod-obj
                                                :id (:id dest-shape)
                                                :operations roperations}))
                  (update :redo-changes conj (make-change
                                               container
                                               {:type :reg-objects
                                                :shapes all-parents}))
                  (update :undo-changes d/preconj (make-change
                                                    container
                                                    {:type :mod-obj
                                                     :id (:id dest-shape)
                                                     :operations uoperations}))
                  (update :undo-changes conj (make-change
                                               container
                                               {:type :reg-objects
                                                :shapes all-parents})))))
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
                     (d/preconj uoperations uoperation)))))))))

(defn- reposition-shape
  [shape origin-root dest-root]
  (let [shape-pos (fn [shape]
                    (gpt/point (get-in shape [:selrect :x])
                               (get-in shape [:selrect :y])))

        origin-root-pos (shape-pos origin-root)
        dest-root-pos   (shape-pos dest-root)
        delta           (gpt/subtract dest-root-pos origin-root-pos)]
    (gsh/move shape delta)))

(defn- make-change
  [container change]
  (if (cph/page? container)
    (assoc change :page-id (:id container))
    (assoc change :component-id (:id container))))


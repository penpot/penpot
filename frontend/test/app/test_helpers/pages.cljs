(ns app.test-helpers.pages
  (:require
   [cljs.test :as t :include-macros true]
   [cljs.pprint :refer [pprint]]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.uuid :as uuid]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape :as cts]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.layout :as layout]
   [app.main.data.workspace.libraries-helpers :as dwlh]
   [app.main.data.workspace.state-helpers :as wsh]))

;; ---- Helpers to manage pages and objects

(def current-file-id (uuid/next))

(def initial-state
  {:current-file-id current-file-id
   :current-page-id nil
   :workspace-layout layout/default-layout
   :workspace-global layout/default-global
   :workspace-data {:id current-file-id
                    :components {}
                    :pages []
                    :pages-index {}}
   :workspace-libraries {}})

(def ^:private idmap (atom {}))

(defn reset-idmap! []
  (reset! idmap {}))

(defn current-page
  [state]
  (let [page-id (:current-page-id state)]
    (get-in state [:workspace-data :pages-index page-id])))

(defn id
  [label]
  (get @idmap label))

(defn get-shape
  [state label]
  (let [page (current-page state)]
    (get-in page [:objects (id label)])))

(defn sample-page
  ([state] (sample-page state {}))
  ([state {:keys [id name] :as props
           :or {id (uuid/next)
                name "page1"}}]

   (swap! idmap assoc :page id)
   (-> state
       (assoc :current-page-id id)
       (update :workspace-data
               cp/process-changes
               [{:type :add-page
                 :id id
                 :name name}]))))

(defn sample-shape
  ([state label type] (sample-shape state type {}))
  ([state label type props]
   (let [page  (current-page state)
         frame (cph/get-frame (:objects page))
         shape (cts/make-shape type {:x 0 :y 0 :width 1 :height 1} props)]
     (swap! idmap assoc label (:id shape))
     (update state :workspace-data
             cp/process-changes
             [{:type :add-obj
               :id (:id shape)
               :page-id (:id page)
               :frame-id (:id frame)
               :obj shape}]))))

(defn group-shapes
  ([state label ids] (group-shapes state label ids "Group-1"))
  ([state label ids prefix]
   (let [page  (current-page state)
         shapes (dwg/shapes-for-grouping (:objects page) ids)]
     (if (empty? shapes)
       state
       (let [[group changes]
             (dwg/prepare-create-group nil (:objects page) (:id page) shapes prefix true)]

         (swap! idmap assoc label (:id group))
         (update state :workspace-data
                 cp/process-changes (:redo-changes changes)))))))

(defn make-component
  [state instance-label component-label shape-ids]
  (let [page    (current-page state)
        objects (wsh/lookup-page-objects state (:id page))
        shapes  (dwg/shapes-for-grouping objects shape-ids)

        [group component-root changes]
        (dwlh/generate-add-component nil
                                     shapes
                                     (:objects page)
                                     (:id page)
                                     current-file-id
                                     true)]

    (swap! idmap assoc instance-label (:id group)
                       component-label (:id component-root))
    (update state :workspace-data
            cp/process-changes (:redo-changes changes))))

(defn instantiate-component
  ([state label component-id]
   (instantiate-component state label component-id current-file-id))
  ([state label component-id file-id]
   (let [page      (current-page state)
         libraries (wsh/get-libraries state)

         [new-shape changes]
         (dwlh/generate-instantiate-component nil
                                              file-id
                                              component-id
                                              (gpt/point 100 100)
                                              page
                                              libraries)]

     (swap! idmap assoc label (:id new-shape))
     (update state :workspace-data
             cp/process-changes (:redo-changes changes)))))

(defn move-to-library
  [state label name]
  (let [library-id (uuid/next)
        data       (get state :workspace-data)]
    (swap! idmap assoc label library-id)
    (-> state
        (update :workspace-libraries
                assoc library-id {:id library-id
                                  :name name
                                  :data {:id library-id
                                         :components (:components data)}})
        (update :workspace-data
                assoc :components {} :pages [] :pages-index {}))))


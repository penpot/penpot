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
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.libraries-helpers :as dwlh]))

;; ---- Helpers to manage pages and objects

(def current-file-id (uuid/next))

(def initial-state
  {:current-file-id current-file-id
   :current-page-id nil
   :workspace-local dw/workspace-local-default
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
         frame (cph/get-top-frame (:objects page))
         shape (-> (cp/make-minimal-shape type)
                   (gsh/setup {:x 0 :y 0 :width 1 :height 1})
                   (merge props))]
     (swap! idmap assoc label (:id shape))
     (update state :workspace-data
             cp/process-changes
             [{:type :add-obj
               :id (:id shape)
               :page-id (:id page)
               :frame-id (:id frame)
               :obj shape}]))))

(defn group-shapes
  ([state label ids] (group-shapes state label ids "Group-"))
  ([state label ids prefix]
   (let [page  (current-page state)
         shapes (dwg/shapes-for-grouping (:objects page) ids)]
     (if (empty? shapes)
       state
       (let [[group rchanges uchanges]
             (dwg/prepare-create-group (:objects page) (:id page) shapes prefix true)]

         (swap! idmap assoc label (:id group))
         (update state :workspace-data
                 cp/process-changes rchanges))))))

(defn make-component
  [state label ids]
  (let [page (current-page state)
        objects  (wsh/lookup-page-objects state page-id)
        shapes (dwg/shapes-for-grouping objects ids)

        [group rchanges uchanges]
        (dwlh/generate-add-component shapes
                                     (:objects page)
                                     (:id page)
                                     current-file-id)]

    (swap! idmap assoc label (:id group))
    (update state :workspace-data
            cp/process-changes rchanges)))

